---
slug: redis-persistence
title: RDB 和 AOF 有什么区别？线上该怎么选？
module: framework-redis
tags: [Redis, RDB, AOF, 持久化, 混合持久化]
difficulty: 3
frequency: 3
related:
  - slug: redis-data-structures
    type: RELATED
  - slug: redis-expiration-eviction
    type: RELATED
  - slug: cache-consistency
    type: RELATED
---

## 电梯版回答

RDB 是某一时刻的全量二进制快照，体积小、恢复快，但两次快照之间的数据会丢。AOF 是追加写的命令日志，可读、可修复，持久性由 appendfsync 决定，默认 everysec 最多丢一秒。RDB 靠 BGSAVE 起一个子进程写盘，子进程由 fork 产生，靠写时复制共享父进程内存，fork 本身会短暂阻塞且页表越大阻塞越久；AOF 的命令先写进 aof_buf，再由 always、everysec、no 三种刷盘策略决定什么时候 fsync。AOF 文件会膨胀，靠 BGREWRITEAOF 重写成「当前数据对应的最小命令集」，触发条件是 auto-aof-rewrite-percentage 和 auto-aof-rewrite-min-size 两个参数。Redis 4.0 起支持混合持久化，AOF 文件前半段是 RDB 格式的全量快照、后半段是增量命令，兼顾恢复速度和丢失窗口。同时开启 RDB 和 AOF 时，重启优先用 AOF 恢复，因为它通常更完整；AOF 结尾被截断可以自动丢弃，中间损坏可以用 redis-check-aof --fix 修复。

## 展开讲解

### RDB：时间点快照

RDB 把某一时刻的全部数据序列化成一个二进制文件（默认 `dump.rdb`）。
它的定位是**快照**，不是日志——两次快照之间的写入在宕机时会丢。

触发方式有三类：

```
SAVE      ：当前进程直接写盘，会阻塞所有客户端（生产不要用）
BGSAVE    ：fork 出子进程写盘，父进程继续服务（默认方式）
save 配置 ：满足「N 秒内至少 M 个键变化」时自动 BGSAVE
```

```conf
# redis.conf 里的自动快照点（旧版本默认含 60 10000，Redis 7.x 已调整）
save 3600 1
save 300 100
save 60 10000
```

**BGSAVE 的工作流程**：

```
① 父进程 fork() 出子进程
   - fork 会复制父进程的页表，页表越大复制越慢
   - 这段时间父进程阻塞（毫秒级到秒级），是 RDB 的主要抖动来源
② 子进程把内存数据写成临时文件
③ 写完后用 rename() 原子替换成 dump.rdb
```

**写时复制（Copy-On-Write，COW）**是关键：fork 之后父子进程共享物理内存页，
页被标记为只读。父进程（Redis 主线程）收到写请求要修改某个页时，
内核为它复制一份新页，父子各用一份。所以：

- 子进程看到的是 fork 那一刻的完整快照，不受父进程后续写入影响
- 父进程的写入越频繁，被复制的页越多，**额外内存占用越高**，
  最坏情况接近翻倍
- 因此 `maxmemory` 不能设成等于机器内存，必须给 COW 留出余量
- fork 期间如果开启透明大页（THP），COW 的复制单位从 4KB 变成 2MB，
  延迟和内存放大都会恶化，Redis 官方建议关闭 THP

相关监控：

```redis
INFO persistence
-- rdb_bgsave_in_progress      是否正在 BGSAVE
-- rdb_last_bgsave_status      上次 BGSAVE 是否成功
-- rdb_last_cow_size           上次 BGSAVE 期间 COW 复制的字节数
-- rdb_changes_since_last_save 距上次快照的改动数
-- latest_fork_usec            最近一次 fork 耗时（微秒）
```

`latest_fork_usec` 是排查 latency 抖动最直接的指标。

### AOF：追加写命令日志

开启 `appendonly yes` 后，每条写命令都会按 Redis 协议格式追加到 AOF。
写入路径分两步：

```
写命令执行 → 追加到 aof_buf（内存缓冲区）
          → write() 写入内核页缓存
          → fsync() 刷到磁盘（时机由 appendfsync 决定）
```

**三种刷盘策略**：

| `appendfsync` | 行为 | 数据安全 | 性能 |
|---|---|---|---|
| `always` | 每条命令执行后都 fsync | 最安全，几乎不丢 | 最慢，受磁盘 fsync 能力限制 |
| `everysec` | 每秒 fsync 一次（默认） | 最多丢约 1 秒数据 | 很高 |
| `no` | 不主动 fsync，交给操作系统 | 可能丢更多（Linux 通常约 30 秒回写一次，取决于内核参数） | 最快 |

几点补充：

- `everysec` 的 fsync 由后台线程执行，主线程只在没有 fsync 在跑时尽力写，
  所以正常情况下不影响主线程；极端情况下（磁盘卡住）会延迟。
- `always` 也支持**组提交**：多个并发请求的 fsync 会合并成一次，所以它没有
  「每条命令一次磁盘往返」那么恐怖，但仍是三种里最慢的。
- `no` 的"约 30 秒"是 Linux 脏页回写周期，**不是 Redis 的承诺**，
  不同内核参数（`vm.dirty_expire_centisecs` 等）下会变。

### AOF 重写

AOF 越写越大，很多命令是冗余的（同一个 key 被 SET 了一百次，
只有最后一次有意义）。重写就是**用当前数据集对应的最小命令集生成新 AOF**：

```redis
BGREWRITEAOF      -- 手动触发，异步执行，不阻塞主线程
```

重写同样用 fork + COW，因为它是"遍历当前内存数据、生成等价命令"。
自动触发由两个参数控制：

```conf
auto-aof-rewrite-percentage 100   -- 相比上次重写后，文件增长超过 100% 就触发
auto-aof-rewrite-min-size 64mb    -- 但文件至少要到 64MB 才触发
```

**重写期间的新写入怎么办**（这是最容易被追问的点）：

```
Redis >= 7.0（Multi Part AOF）
  子进程写新的 base 文件，父进程同时打开新的增量（incr）AOF 继续追加
  子进程写完后，用临时 manifest 原子替换，把 base + incr 变成新数据集
  重写失败也没关系：旧 base + 旧/新 incr 仍是完整数据

Redis < 7.0
  子进程写新文件；父进程把新写入同时缓冲在内存里、也追加到旧 AOF
  子进程写完后，父进程把内存缓冲追到新文件末尾，再原子 rename
```

所以**重写不会丢数据**，也不会阻塞写入。代价是重写期间内存和磁盘 IO 都有额外开销。

### 混合持久化

Redis 4.0 引入 `aof-use-rdb-preamble`（默认 yes）。开启后，
AOF 重写生成的新文件是**混合格式**：

```
[前半段：RDB 格式的全量快照][后半段：AOF 格式的增量命令]
```

这样做的好处：

- 恢复时先加载 RDB 快照（比逐条回放 AOF 快），再回放尾部增量命令
- 文件体积比纯 AOF 小（全量部分压缩成二进制）
- 保留 AOF「最多丢 1 秒」的丢失窗口

Redis 7.0 的 Multi Part AOF 把这件事做得更明确：
base 文件本身就可以是 RDB 或 AOF 格式，增量写在 incr 文件里，
由 manifest 管理，重写变成 base 的原子替换。

### 重启恢复顺序与 AOF 修复

**恢复顺序**：只要 `appendonly yes`，重启就**用 AOF 恢复**，
即使 RDB 也开着。原因很直接——AOF 通常更完整（每 1 秒一个检查点），
而 RDB 可能是几分钟前的快照。

**AOF 出问题的两类情况**：

```conf
aof-load-truncated yes   -- 默认 yes
```

- **尾部截断**（宕机时最后一条命令写了一半）：默认会被自动丢弃，
  服务器继续启动，日志里提示 `Truncating the AOF at offset ...`。
- **中间损坏**（文件中间出现非法字节）：Redis 直接报错退出，需要人工修复：

```bash
# 先备份！
cp appendonly.aof appendonly.aof.bak
redis-check-aof --fix appendonly.aof
```

注意 `--fix` 的语义：它会**从损坏点开始丢弃到文件末尾**。
如果损坏发生在文件前部，等于丢掉绝大部分数据——所以先备份，
能用 RDB 备份恢复就不要依赖 `--fix`。

### 线上怎么选

| 场景 | 建议 |
|---|---|
| 纯缓存，数据丢了能从 DB 重建 | 可以关持久化；但建议至少留 RDB 供快速恢复 |
| 一般业务（常见答案） | RDB + AOF 同时开，配合 `aof-use-rdb-preamble yes`（混合持久化） |
| 能容忍几分钟丢失、追求最高性能 | 只用 RDB |
| 数据重要、丢失窗口要求秒级 | AOF（everysec）+ RDB 备份；只开 AOF 不推荐 |

最后一条认知很重要：**持久化不等于备份，主从复制也不等于备份**。
误删（`FLUSHALL`）、逻辑错误会同步到从库、AOF 也会被重写掉；
真正兜底的是定期把 RDB 文件复制到异地/对象存储并保留多个版本。

## 追问链

### Q1: BGSAVE 的 fork 为什么会阻塞？写时复制到底复制了什么？

fork 阻塞的根源是**复制页表**，不是复制数据。

进程的内存映射记录在页表里：每个虚拟页对应哪个物理页。fork 要为子进程
复制一份页表，让子进程拥有独立的地址空间映射，但**物理内存页先不复制**，
父子共享且都标记为只读。页表项数量与内存大小和页大小相关（4KB 页时
每 GB 内存约 26 万个页表项），页表越大、CPU 越慢，复制耗时越长，
这段时间 Redis 主线程完全停止服务。所以：

- `latest_fork_usec` 监控的就是这个耗时
- 内存几十 GB 的实例 fork 抖动可能到几十甚至上百毫秒，
  这是 RDB 方式最主要的延迟来源
- 关闭 THP 能让 fork 和后续 COW 的开销可控

COW（Copy-On-Write）发生在 **fork 之后**：当父进程要修改某个共享页时，
内核先把这一页复制一份给父进程，然后父进程改自己的副本；子进程仍看到原页。
于是子进程视角的数据始终是 fork 那一刻的快照。

#### Q1.1: fork 之后父进程大量写入会发生什么？

会产生大量 COW 复制，**额外内存占用可能接近翻倍**。

```
fork 时：所有页共享，几乎不占额外内存
父进程每写一个之前只读的页：
  内核分配新页 + 复制内容 + 更新页表 → 该页变成父子各一份
父进程写入量越大，被"分家"的页越多
```

叠加 AOF 重写、主从全量同步（BGSAVE 生成 RDB 发给从库）同时跑，
内存压力会更明显。风险有两个：内存不够触发 swap（性能崩塌）或 OOM Killer
杀掉 Redis；以及 COW 本身消耗 CPU。

所以运维上有几条硬规矩：

- `maxmemory` 要留出余量（不能等于机器内存）
- Linux 建议 `vm.overcommit_memory = 1`，允许内存超额分配，
  否则 fork 在内存吃紧时可能直接失败
- 监控 `rdb_last_cow_size` / `aof_last_cow_size`，估算最坏内存占用
- 尽量避免 RDB 快照、AOF 重写、主从全量同步同时发生
  （Redis 内部会互斥 BGSAVE 和 BGREWRITEAOF，但主从全量同步也会触发 BGSAVE）

##### Q1.1.1: 内存不够时 fork 会失败吗？失败了会怎样？

会。`fork()` 返回失败（错误类似 `Cannot allocate memory`），
Redis 会记录 `BGSAVE` / `BGREWRITEAOF` 失败：

```redis
INFO persistence
-- rdb_last_bgsave_status:err
-- aof_last_bgrewrite_status:err
```

后果是**持久化停摆**：新的快照写不出来，如果此时实例挂掉，
只能恢复到上一次成功的快照，丢失窗口被放大。自动快照点会持续重试，
重试也失败就一直是 `err`。

避免手段：

```
① vm.overcommit_memory = 1（官方明确建议，Redis 启动时也会检查并 warn）
② maxmemory 留足 COW 余量，不要贴机器内存上限
③ 关闭 THP（透明大页会让 COW 的复制单位从 4KB 变 2MB）
④ 监控 latest_fork_usec、rdb_last_cow_size，必要时把实例拆小
⑤ 主从架构下用从库做持久化/备份，主库少 fork
```

关于 THP 的细节值得记一下：THP 开启时内核会尽量用 2MB 大页，
COW 的复制单位随之变大，不仅放大内存，还让 fork 和写时复制的延迟变差，
所以 Redis 官方建议 `echo never > /sys/kernel/mm/transparent_hugepage/enabled`。

### Q2: AOF 的三种刷盘策略怎么选？丢数据的窗口到底多大？

先明确一个容易混淆的点：**`write()` 和 `fsync()` 是两件事**。

```
write()  ：把 aof_buf 写进内核页缓存（page cache）——数据还在内存里
fsync()  ：命令内核把页缓存刷到磁盘——这之后断电才不丢
```

`appendfsync` 控制的是 fsync 的时机：

| 策略 | 丢失窗口 | 代价 |
|---|---|---|
| `always` | 理论上不丢（每条命令后 fsync，支持组提交合并） | fsync 次数最多，吞吐受磁盘限制 |
| `everysec`（默认） | 约 1 秒 | 每秒一次 fsync，由后台线程执行 |
| `no` | 由 OS 决定，Linux 通常约 30 秒 | 几乎无额外开销，但窗口不可控 |

大多数业务选 `everysec`：丢掉 1 秒数据通常可接受，而性能几乎无损。
`always` 适合"一条都不能丢"的强一致场景，但要先压测确认磁盘
能承受相应的 fsync 频率（机械盘几乎必然成为瓶颈）。
`no` 基本只用在"性能优先、丢了能重建"的缓存场景。

#### Q2.1: 既然 everysec 最多只丢 1 秒，为什么线上还常同时开 RDB 或混用?

因为 **AOF 恢复慢、文件大、重写有成本**，而 RDB 恰好补上这几点：

- **恢复速度**：AOF 是逐条回放命令，数据量大时恢复可能要很久；
  RDB 直接反序列化快照，快得多。混合持久化（`aof-use-rdb-preamble yes`）
  就是让 AOF 的前半段用 RDB 格式，重启时先加载快照再回放尾部增量。
- **文件体积**：同等数据集，AOF 通常明显大于 RDB。
- **快照兜底**：定期 RDB 快照是"如果 AOF 引擎有 bug 或文件损坏"的退路，
  Redis 官方也建议即使只用 AOF 也保留 RDB 快照。
- **备份方便**：RDB 是单个紧凑文件，适合复制到异地/对象存储做版本保留。

所以常见组合是「AOF（everysec）为主 + RDB 快照定期备份」，
再叠加 `aof-use-rdb-preamble yes` 得到混合格式的 AOF。

##### Q2.1.1: AOF 重写期间的新写入会丢吗？重写失败了怎么办？

不会丢，但要区分版本理解机制。

**Redis 7.0 之前**：

```
父进程把重写期间的新写命令同时做两件事：
  ① 追加到旧的 AOF 文件（所以重写失败也不丢）
  ② 缓冲在内存里
子进程写完新文件后，父进程把内存缓冲追加到新文件末尾，再原子 rename
```

重写失败（子进程出错/被杀）时旧 AOF 始终完整，数据安全。
代价是重写期间父进程要额外维护内存缓冲，数据量大时缓冲可能很大。

**Redis 7.0 起（Multi Part AOF）**：

```
子进程写新的 base 文件
父进程立即打开一个新的增量（incr）AOF 继续追加新命令
子进程写完后，用临时 manifest 原子替换，把 base + incr 组合成新数据集
```

重写失败时，旧 base 和 incr 文件仍然构成完整数据，同样不丢。
Multi Part AOF 还引入了失败重试的退避机制，避免反复失败反复重试拖垮实例。

无论哪个版本，**自动重写只是优化体积和恢复速度，不是持久化的一部分**——
不存在"重写期间丢数据"这种设计。

### Q3: AOF 文件损坏了怎么办？重启时到底加载哪个文件？

先看加载顺序：**只要 `appendonly yes`，重启就用 AOF 恢复**，
即使 RDB 文件也在。Redis 认为 AOF 通常是最完整的
（默认每秒一个检查点，而 RDB 可能是几分钟前的快照）。

损坏分两类：

**① 尾部截断**（宕机时最后一条命令写到一半）：默认 `aof-load-truncated yes`
会丢弃最后一个不完整的命令并继续启动，日志出现
`Truncating the AOF at offset ...`。这是设计行为，不代表数据异常。

**② 中间损坏**（非法字节序列）：Redis 启动时直接报错退出，需要修复：

```bash
cp appendonly.aof appendonly.aof.bak     # 先备份！
redis-check-aof appendonly.aof           # 先看报告，不加 --fix
redis-check-aof --fix appendonly.aof     # 确认后再修
```

#### Q3.1: 直接 redis-check-aof --fix 有什么风险？

它会**从损坏点开始把后面的内容全部丢弃**。

```
[AOF 开头 ... 正常 ... ][损坏点][... 后面本来正常的数据 ...]
                        └─ --fix 从这里全部截断丢掉
```

如果损坏发生在文件靠前的位置，等于丢掉绝大部分数据。
官方文档也提示：先不加 `--fix` 运行一次，根据报告的 offset
跳到文件对应位置，看看能不能手工修复（AOF 就是 Redis 协议文本，边界清楚），
实在修不了再用 `--fix`。

更稳的恢复路径是：**用最近一次的 RDB 备份恢复，再接受丢失窗口**，
而不是对着一个中段损坏的 AOF 做截断。所以"有 RDB 备份"本身
就是 AOF 损坏时的保险。

##### Q3.1.1: 那生产上怎么降低 AOF 损坏带来的风险？

把风险拆成"少损坏"和"坏了好恢复"两部分：

**少损坏**：

```conf
appendfsync everysec          # 权衡点，always 更安全但慢
aof-load-truncated yes        # 尾部半条命令自动丢弃，保证能启动
```

- 磁盘空间和 inode 监控。磁盘写满写不进去是 AOF 出问题的常见原因；
  `INFO persistence` 的 `aof_last_write_status` 变成 `err` 就要告警。
- `no-appendfsync-on-rewrite` 要谨慎：设成 yes 可以在重写期间避免
  fsync 阻塞，但会放大丢失窗口，默认 no 是有道理的，改之前要清楚代价。

**坏了好恢复**：

```
① 定期 RDB 快照 + 复制到异地/对象存储，保留多个版本（这才是真正的备份）
② 至少一个从库，且从库不要和主库共享同一块故障磁盘
③ 监控 aof_last_bgrewrite_status、rdb_last_bgsave_status、aof_last_write_status
④ 演练恢复流程：确认 RDB/AOF 真能加载、恢复耗时是否可接受
```

顺带一个常被忽略的点：**`FLUSHALL` 也会被写进 AOF**。
如果没来得及重写，可以停服、手工删掉 AOF 末尾那条 `FLUSHALL`、再启动救回来——
这是 AOF 可读带来的一个真实好处，也是"可读"这个特性在应急时的价值。

## 常见坑

- **说「RDB 是实时的，AOF 是定时的」** ——
  恰好相反：RDB 是**定时快照**（丢两次快照之间的数据），
  AOF 是**追加写命令**（appendfsync 决定丢失窗口，默认约 1 秒）
- **说「SAVE 和 BGSAVE 只是同步异步的区别，随便用」** ——
  `SAVE` 在当前进程写盘，**全程阻塞所有客户端**，生产禁用；
  要用 `BGSAVE`
- **说「fork 会把内存数据复制一份，所以内存翻倍」** ——
  fork 只复制**页表**，数据页靠 COW 按需复制；
  内存是否接近翻倍取决于 fork 后父进程的写入量
- **说「AOF 一定比 RDB 安全，所以只开 AOF 就行」** ——
  AOF 文件更大、恢复更慢，且官方明确不建议只用 AOF
  （没有 RDB 快照就少了备份和 AOF 引擎出 bug 时的退路）；
  同时 AOF 的 `everysec` 仍会丢约 1 秒，不是零丢失
- **说「appendfsync always 就是完全零丢失且性能可接受」** ——
  它支持组提交所以没想象中慢，但仍是三种里最慢、
  受磁盘 fsync 能力限制，上生产前要压测
- **说「每次 BGSAVE 都会让内存翻倍，所以不能开 RDB」** ——
  只有 fork 后父进程大量写入时才接近翻倍；
  常态下 COW 复制的页很少，看 `rdb_last_cow_size` 才有意义
- **说「AOF 重写会阻塞写入 / 重写期间新写入会丢」** ——
  重写是 fork 子进程异步做的，父进程继续接写入；
  无论 7.0 前后都有完整机制保证不丢
- **说「AOF 损坏用 redis-check-aof --fix 修一下就好了」** ——
  `--fix` 会**从损坏点丢弃到文件末尾**，损坏靠前时等于丢掉大部分数据；
  应先备份、先不加 `--fix` 看报告，优先用 RDB 备份恢复
- **说「主从复制就是备份」** ——
  误删（`FLUSHALL`）和逻辑错误会同步到从库；
  真正的备份是异地保留的 RDB 快照
- **说「maxmemory 设成机器内存的 100% 就行」** ——
  BGSAVE/AOF 重写时 COW 需要额外内存，贴满上限会 fork 失败甚至 OOM；
  必须留余量

## 加分点

- 能说清 **fork 复制的是页表不是数据**，并由此推出
  「阻塞时长与内存/页表大小相关」以及 `latest_fork_usec` 这个观测点——
  这是"真读过实现"和"背概念"的分界
- 知道 **COW 按需复制**，能解释 fork 后内存什么时候会接近翻倍
  （父进程大量写入时），并把它和 `maxmemory` 留余量、
  `vm.overcommit_memory = 1`、关闭 THP 这几条运维建议串起来
- 能区分 **`write()` 与 `fsync()`**：写进页缓存不等于落盘，
  `everysec` 丢 1 秒的根因就在这里；`no` 的"约 30 秒"来自
  Linux 脏页回写而不是 Redis 的承诺
- 能讲清 **AOF 重写在 7.0 前后的机制差异**
  （<7：父进程缓冲 + 追加旧 AOF；>=7：Multi Part AOF 的 base + incr + manifest
  原子替换），并给出结论「重写不丢数据」
- 知道 **混合持久化 `aof-use-rdb-preamble`** 的动机是
  「RDB 的恢复速度 + AOF 的小丢失窗口」，并能说明 Redis 7.0
  Multi Part AOF 是同一思路的工程化
- 提到 **`FLUSHALL` 会被写进 AOF**，在未重写的情况下
  可以停服手工删掉最后那条命令来救数据——这是 AOF 可读性的实战价值
- 能主动纠正「持久化 = 备份」：指出复制、AOF、RDB 都不防误删，
  真正的备份是**异地、多版本、定期演练恢复**的 RDB 快照
- 知道 `aof-load-truncated yes` 的默认行为和日志特征
  （`Truncating the AOF at offset ...`），能判断"这个报错要不要慌"
- 提到 `no-appendfsync-on-rewrite` 是**用丢失窗口换掉 fsync 抖动**，
  默认 no，改动要清楚代价

## 版本差异

| 版本 | 差异 |
|---|---|
| Redis 1.1 | 引入 AOF |
| Redis 2.4 | 支持自动触发 AOF 重写（此前需手动 `BGREWRITEAOF`）；`everysec` 的 fsync 性能改进 |
| Redis 4.0 | 引入**混合持久化** `aof-use-rdb-preamble`（AOF 前半段 RDB、后半段增量）；引入 `UNLINK` 与 lazyfree 相关配置 |
| Redis 6.0 | 引入网络 IO 多线程（`io-threads`），但命令执行仍是单线程 |
| Redis 7.0 | 引入 **Multi Part AOF**（base + 增量文件 + manifest 管理），重写改为 base 的原子替换并带失败退避；AOF 与 RDB 的目录/文件名配置（`appenddirname`）细化；默认 `save` 快照点调整 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: Redis 的 appendfsync 默认使用哪种刷盘策略？
    options:
      A: always，每条命令都 fsync
      B: everysec，每秒 fsync 一次
      C: no，完全交给操作系统
      D: 默认不开启 AOF，所以没有刷盘策略
    answer: B
    analysis: 默认 appendfsync everysec，每秒 fsync 一次，最多丢失约 1 秒数据，是性能与安全的折中。always 最安全但最慢，no 交给操作系统、丢失窗口通常约 30 秒且不可控。

  - type: JUDGE
    stem: 同时开启 RDB 和 AOF 时，Redis 重启会优先用 RDB 快照恢复，因为 RDB 恢复更快。
    answer: F
    analysis: 只要 appendonly yes，重启就优先用 AOF 恢复，因为它通常更完整（默认每秒一个检查点，而 RDB 可能是几分钟前的快照）。恢复速度是选型时的考虑，不是加载顺序的依据。

  - type: MULTI
    stem: 关于 AOF 重写，下列说法正确的有？
    options:
      A: BGREWRITEAOF 通过 fork 子进程异步执行
      B: 重写的产物是「当前数据集对应的最小命令集」
      C: 重写期间父进程会停止接受写命令，直到重写完成
      D: auto-aof-rewrite-min-size 默认是 64mb
    answer: ABD
    analysis: C 错误。重写期间父进程继续接受写入，不会阻塞；Redis 7.0 前把新写命令追加到旧 AOF 并缓冲在内存，7.0 起打开新的增量 AOF，重写完成后原子替换，两种机制都保证不丢数据。auto-aof-rewrite-percentage 默认 100，auto-aof-rewrite-min-size 默认 64mb。

  - type: CLOZE
    stem: |
      补全 AOF 的三种刷盘策略与其特点：
      appendfsync {{1}} 表示每条命令执行后都 fsync，最安全也最慢；
      appendfsync {{2}} 是默认值，最多丢失约 1 秒数据；
      appendfsync no 则不主动 fsync，交给操作系统决定。
    blanks:
      - ["always"]
      - ["everysec"]
    analysis: always 最安全但 fsync 次数最多（支持组提交可合并）、everysec 每秒一次由后台线程执行、no 不主动 fsync。注意 write() 只是写进内核页缓存，fsync() 才真正落盘，这正是 everysec 会丢约 1 秒的原因。
    difficulty: 2
````
