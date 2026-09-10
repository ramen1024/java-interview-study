---
slug: distributed-lock
title: Redis 分布式锁怎么实现？有哪些坑？
module: framework-redis
tags: [Redis, 分布式锁, Lua, Redisson, Redlock]
difficulty: 3
frequency: 3
related:
  - slug: cache-penetration-breakdown-avalanche
    type: RELATED
  - slug: cache-consistency
    type: DEEPEN
---

## 电梯版回答

最简版本是 SET key value NX PX 毫秒，一条命令同时完成「不存在才设置」和「设置过期时间」，不能拆成 SETNX 加 EXPIRE 两条命令，否则中间宕机会造成永久死锁。value 必须放一个唯一标识，解锁时用 Lua 脚本「比较 value 相等才删除」，否则会误删别人的锁——自己的锁超时释放后，别的线程拿到了锁，自己执行完又把别人的锁删了。锁的租期要大于业务耗时，超过就需要看门狗自动续期，Redisson 内置了这个机制。此外还要考虑可重入、主从切换丢锁导致的互斥失效，以及锁的粒度是不是该用分段锁或者干脆换成状态机。

## 展开讲解

### 最小可用实现

**加锁**：一条命令完成两件事

```java
// ✅ 正确：SET key value NX PX ttl 是原子的
SET lock:order:123  "uuid-abc"  NX  PX  30000

// ❌ 错误：两条命令，中间宕机就死锁
SETNX lock:order:123 "uuid-abc"
EXPIRE lock:order:123 30        // 如果这行没执行，锁永不过期
```

`SET` 的 `NX PX` 选项在 Redis 2.6.12 起支持，
**一条命令同时表达「仅当 key 不存在时设置」和「设置毫秒级过期」**，
天然原子。

**解锁**：必须比对持有者

```lua
-- 错误的写法：直接删
DEL lock:order:123
-- 场景：A 的业务超时，锁自动过期；B 拿到锁；
--       A 执行完来删锁，删掉的是 B 的锁
--       → 互斥性被破坏，B 和 C 可能同时进入临界区
```

正确做法是「比较并删除」，而且**这两步必须原子**，
所以要用 Lua 脚本（Redis 单线程执行脚本，不会被穿插）：

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

本项目 `RedisLock` 就是这么实现的：

```java
private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
        Long.class
);

public String tryLock(String key, Duration ttl) {
    String token = UUID.randomUUID().toString();
    boolean acquired = Boolean.TRUE.equals(
            stringRedisTemplate.opsForValue().setIfAbsent(key, token, ttl)
    );
    return acquired ? token : null;
}

public void unlock(String key, String token) {
    if (token == null) {
        return;
    }
    stringRedisTemplate.execute(UNLOCK_SCRIPT, List.of(key), token);
}
```

**为什么用 UUID 而不是线程 ID**：分布式环境下可能有多个 JVM、
每个 JVM 里线程 ID 会重复。必须全局唯一，
UUID 是最省事的选择。

### 锁的租期该设多长

这是分布式锁最难的工程问题：**租期必须大于业务执行时间，
但你无法预知业务会执行多久**。

```
租期 = 30s，业务跑了 35s
→ 30s 时锁自动释放，其他线程进入临界区
→ 同时有两个线程在临界区，互斥性失效
```

三种应对：

**① 看门狗自动续期（Redisson 方案）**

```java
RLock lock = redissonClient.getLock("lock:order:123");
lock.lock();          // 不指定 leaseTime，启用看门狗
try {
    // 业务逻辑
} finally {
    lock.unlock();
}
```

Redisson 的机制：

```
默认 leaseTime = 30s（lockWatchdogTimeout 可配）
后台定时任务每 10s（租期 / 3）检查一次：
  如果 JVM 还活着且锁还被持有 → 续期到 30s
```

**关键在于「JVM 还活着」的判断**：看门狗是**客户端**的定时任务，
所以 JVM 崩溃或进程被杀后，续期停止，锁会在 30s 内自动释放。
这比「无限期持有」安全得多。

**必须注意的坑**：`lock(leaseTime)` 传了 leaseTime 就**不会启用看门狗**。
很多线上问题源于此——开发随手写了个 `lock(10, TimeUnit.SECONDS)`，
以为有自动续期，实际业务超过 10s 就并发进入了。

**② 把长任务拆短**

如果业务确实要跑几分钟（比如批量计算），
更好的做法是不要一次持有锁那么久：

```
拿到锁 → 读取待处理任务列表 → 释放锁
循环：拿到锁 → 处理一小批 → 释放锁
```

**③ 用状态机替代长事务锁**

如果场景是「一个订单只能被处理一次」，
与其用锁，不如用数据库的**状态流转 + CAS**：

```sql
UPDATE order_info
SET status = 'PROCESSING', version = version + 1
WHERE id = ? AND status = 'PENDING'
-- 影响行数为 1 才说明抢到了处理权
```

这比分布式锁更可靠（不依赖 Redis 可用性），
也不会因为业务耗时长而失效。

### Redlock 与它的争议

单节点 Redis 锁有个致命问题：**主从切换会丢锁**。

```
① 客户端 A 在 master 上拿到锁
② master 在把锁同步到 slave 之前挂了
③ slave 提升为新 master，锁没了
④ 客户端 B 在「新 master」上也能拿到同一把锁
→ A 和 B 同时持有锁，互斥性失效
```

Martin Kleppmann 和 Redis 作者 antirez 有过一场著名的争论。
核心分歧：

| | antirez（Redlock 支持方） | Kleppmann（质疑方） |
|---|---|---|
| 思路 | 向 N 个独立 Redis 节点申请，多数派成功才算拿到锁 | 依赖时钟的锁不适合做正确性保证 |
| 前提 | 需要 N 个独立部署的 master，成本高 | 时钟漂移、GC 停顿、网络延迟都能打破假设 |
| 结论 | Redlock 能提供较好的互斥性 | 有 GC 停顿或时钟跳变时仍会失效，应改用 fencing token |

**Kleppmann 的核心论点是 fencing token**：

```
① A 拿到锁，token = 33
② A 发生长时间 GC 停顿，锁过期
③ B 拿到锁，token = 34，写入数据
④ A 恢复，用 token 33 写入 —— 覆盖了 B 的数据

解法：存储层拒绝 token 更小的写入
     （每次写入带上 token，存储端记录已见过的最大 token）
```

**结论**：分布式锁**很难做到绝对正确**。
工程上的选择是：

- **绝大多数业务**：单节点 Redis 锁 + 看门狗足够，
  因为短暂的双持有不会造成灾难（业务本身有幂等或唯一约束兜底）
- **强一致要求**：用 etcd / ZooKeeper（基于共识算法），
  或直接用数据库的唯一索引/状态机，而不是 Redis 锁

## 追问链

### Q1: 为什么 value 一定要放唯一标识，不能放固定值？

因为**解锁时必须能区分「这把锁是不是我的」**。

反例时序：

```
t0  A 拿锁（value = "1"），租期 10s
t1  A 业务卡住（GC 停顿 / 下游超时）
t10 锁自动过期
t11 B 拿锁（value = "1"）
t12 A 恢复执行完，执行解锁：DEL lock
    → 删掉的是 B 的锁！
t13 C 来拿锁，成功（因为锁被 A 删了）
    → B 和 C 同时进入临界区
```

如果 value 是 UUID，第 t12 步的 Lua 脚本会发现
「当前值 `uuid-B` ≠ 我的 `uuid-A`」，于是**不删除**，
B 的锁仍然有效，互斥性保住了。

这就是「比较并删除」的意义——它不是为了日志好看，
而是**互斥性的必要保证**。

#### Q1.1: 那为什么用 Lua 而不是先 GET 再 DEL？

因为 **GET 和 DEL 之间有一个时间窗口**：

```
线程 A：GET lock → "uuid-A"（相等，是我的锁）
        ← 此时锁过期了，B 拿到了锁（value 变成 uuid-B）
线程 A：DEL lock → 删掉了 B 的锁
```

时序上依然是误删。Lua 脚本在 Redis 里**整体原子执行**
（Redis 单线程模型下，脚本执行期间不会处理其他命令），
所以「比较」和「删除」之间不可能被插入其他操作。

**这里体现的是一个通用原则**：任何「检查后动作」
（check-then-act）如果跨越两次网络往返，就不具备原子性。
换成 Redis 场景可以用 Lua / `MULTI-EXEC` / 单条命令解决；
换成数据库场景则是用乐观锁的 `WHERE version = ?` 或者
`SELECT ... FOR UPDATE`。

##### Q1.1.1: 那 Redisson 的可重入是怎么做的？

用 **Hash 结构**记录「持有者 + 重入次数」：

```lua
-- 加锁（简化的核心逻辑）
if redis.call('exists', KEYS[1]) == 0 then
    -- 锁不存在，直接创建 hash 并置重入次数为 1
    redis.call('hincrby', KEYS[1], ARGV[2], 1)
    redis.call('pexpire', KEYS[1], ARGV[1])
    return nil
end
if redis.call('hexists', KEYS[1], ARGV[2]) == 1 then
    -- 锁存在且持有者是自己，重入次数 +1
    redis.call('hincrby', KEYS[1], ARGV[2], 1)
    redis.call('pexpire', KEYS[1], ARGV[1])
    return nil
end
return redis.call('pttl', KEYS[1])   -- 被他人持有，返回剩余时间
```

```
key    = lock:order:123
field  = uuid:threadId           ← 区分不同 JVM 的不同线程
value  = 重入次数
```

解锁则递减，**减到 0 才真正删除 key**：

```lua
if redis.call('hexists', KEYS[1], ARGV[3]) == 0 then
    return nil                    -- 不是自己的锁，不处理
end
local counter = redis.call('hincrby', KEYS[1], ARGV[3], -1)
if counter > 0 then
    redis.call('pexpire', KEYS[1], ARGV[2])   -- 仍被重入持有，只续期
    return 0
else
    redis.call('del', KEYS[1])                 -- 重入归零，真删除
    redis.call('publish', ...)                 -- 发布通知唤醒等待者
    return 1
end
```

**用 Hash 而不是 String 的关键原因**：String 的 value 只能存一个标识，
没法表达「同一个持有者拿了两次」。Hash 让
「持有者标识」和「重入次数」各占一个维度。

顺带一提，`field` 用 `uuid:threadId` 而不是单纯 threadId：
同一台机器上的不同 JVM 线程 ID 会重复，
加上 UUID 才能全局唯一。

### Q2: 主从切换丢锁的问题该怎么处理？

分三个层次，取决于业务对正确性的要求。

**层次一：接受它，用业务约束兜底（最常见的选择）**

绝大多数业务场景下，短暂的双持有不会造成灾难，
因为业务本身通常有幂等或唯一约束：

```sql
-- 唯一索引：并发插入只会成功一次
ALTER TABLE seckill_record ADD UNIQUE KEY uk_user_goods (user_id, goods_id);

-- 乐观锁版本号
UPDATE stock SET count = count - 1, version = version + 1
WHERE goods_id = ? AND count > 0 AND version = ?
```

**这才是第一道防线**。分布式锁的作用是「减少无谓的竞争、
提升性能」，而不是「唯一的正确性保证」。
把锁当唯一保证的系统，一旦锁失效就是数据错乱。

**层次二：Redlock（多节点多数派）**

```
向 N（通常 5）个独立 Redis 节点申请锁
在「锁总有效期」内（比如 100ms）拿到多数派（≥ 3）成功
才算真正获得锁
```

问题也很明显：

- 需要 **N 个独立部署**的 Redis master，运维成本高
- 依赖**时钟假设**（各节点的过期时间不能漂移太多）
- 有 **GC 停顿**时仍可能失效（Kleppmann 的核心质疑）
- Redisson 提供了 `RedissonRedLock`，但官方文档也标注它已不推荐
  （`RedissonMultiLock` 更贴合实际需求）

**层次三：换掉 Redis（强一致要求）**

| 方案 | 一致性基础 | 特点 |
|---|---|---|
| ZooKeeper | ZAB 共识，临时顺序节点 | 强一致，锁释放靠会话超时，性能低于 Redis |
| etcd | Raft 共识，Lease + Revision | 强一致，支持 revision 做 fencing token |
| 数据库唯一索引 | 单机/主库的原子性 | 最可靠，但性能差、有连接开销 |
| 数据库状态机 CAS | 单条 UPDATE 的原子性 | 适合「一次性执行」语义，无锁化 |

**推荐的判断标准**：

```
锁失效会导致数据错乱吗？
  不会（有幂等/唯一约束兜底）→ 单节点 Redis 锁
  会                         → 优先考虑不用锁的方案
                              （状态机、唯一索引、幂等设计）
                              实在要用锁 → etcd / ZooKeeper
```

**「能不能不用锁」永远值得先问一遍**。
很多「需要分布式锁」的场景其实可以改造成
「状态机 + CAS」或「唯一约束 + 幂等」。

## 常见坑

- **用 `SETNX` + `EXPIRE` 两条命令加锁** —— 中间宕机则锁永不过期，
  直接永久死锁。必须用 `SET key value NX PX ttl` 一条命令
- **解锁时直接 `DEL`** —— 业务超时后锁被他人持有，
  `DEL` 会误删他人的锁。必须用 Lua 比较持有者
- **value 用固定字符串** —— 无法区分持有者，误删问题依然存在
- **先 `GET` 再 `DEL`** —— 两次网络往返之间仍有窗口，
  依然可能误删。必须用 Lua / 单条命令保证原子
- **`lock(leaseTime)` 后以为有看门狗续期** ——
  **传了 leaseTime 就不会启用看门狗**，
  业务超时即并发进入，这是线上最常见的一类误用
- **把分布式锁当成正确性的唯一保证** ——
  主从切换、GC 停顿都会让锁失效。
  必须有幂等或唯一约束兜底
- **锁粒度太粗** —— 用 `lock:order` 锁住所有订单，
  并发直接退化成串行。应该按业务主键分段
  （`lock:order:{orderId}`）
- **不设超时时间** —— 必须有过期时间兜底，
  否则持有者崩溃就永久死锁
- **在事务方法内部用锁** —— 锁在事务开启后获取、
  事务提交前释放，会出现「锁已释放但事务未提交」的窗口，
  其他线程拿到锁读到旧数据。**锁必须在事务外层**

## 加分点

- 能把**「锁必须在事务外层」**这个坑讲清楚：
  典型错误写法是 `@Transactional` 方法内加锁，
  锁在事务提交前就释放了，导致「脏读 + 重复执行」。
  正确结构是「先加锁 → 再开事务（通过独立方法调用）→ 提交 → 释放锁」
- 提到 **fencing token** 这个根本性思路：
  即使锁失效，存储层拒绝过期租约的写入，
  把正确性保证下沉到有状态的存储
- 知道 Redisson 的**看门狗只在「不传 leaseTime」时生效**，
  并能说出其默认参数（30s 租期、每 10s 续期一次）
- 能把「锁应该尽量不用」讲明白，并举出**状态机 CAS**、
  **唯一索引**、**幂等设计**这三种替代方案各自的适用场景
- 提到**锁分段**（类似 `ConcurrentHashMap` 的分段思路）：
  把一把大锁拆成 N 把，按 ID 取模选择，提升并发度
- 知道 Redisson 解锁时会 `publish` 消息通知等待者，
  避免等待方纯轮询（这是性能优化的细节）
- 能对比 **etcd 的 Lease + Revision** 天然适合做 fencing，
  而 Redis 没有单调递增的版本号可以用

## 版本差异

| 版本 | 差异 |
|---|---|
| Redis 2.6.12 | `SET` 支持 `NX` + `PX` 选项，一条命令完成加锁 |
| Redis 2.6.0 | 支持 EVAL 执行 Lua 脚本，解锁的原子比较删除成为可能 |
| Redis 4.0 | `UNLINK` 异步删除，避免大 key 的 `DEL` 阻塞主线程 |
| Redis 5.0 | `Stream` 可用于锁等待通知的可靠投递（替代 pub/sub 的不可靠性） |
| Redis 7.0 | Function 可替代部分 Lua 脚本场景；Cluster 环境下多 key 脚本需同槽 |
| Redisson 3.x | 看门狗默认 `lockWatchdogTimeout = 30s`，每 1/3 租期续期一次；`RedissonRedLock` 已不推荐，建议用 `RedissonMultiLock` |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 使用 Redis 实现分布式锁时，加锁应该怎么做？
    options:
      A: 先 SETNX，成功后再 EXPIRE
      B: 用 SET key value NX PX ttl 一条命令
      C: 用 SET 不加过期时间，靠程序主动删除
      D: 用 INCR 计数判断是否为 1
    answer: B
    analysis: A 的两条命令之间如果进程宕机，锁永不过期造成永久死锁。C 缺少兜底，持有者崩溃则死锁。D 无法正确表达锁语义（计数还需要额外管理过期与持有者）。SET 的 NX + PX 一条命令同时满足「不存在才设置」与「有超时兜底」。

  - type: MULTI
    stem: 解锁时使用「比较 value 相等才删除」的 Lua 脚本，解决了哪些问题？
    options:
      A: 防止误删其他线程持有的锁
      B: 保证「比较」和「删除」两步的原子性
      C: 让锁支持可重入
      D: 避免持有者业务超时后删除他人的锁
    answer: ABD
    analysis: C 错误，可重入需要 Hash 结构记录持有者与重入次数，是另一套机制。A、B、D 都是这个脚本要解决的核心问题：value 作为持有者标识，Lua 保证「检查后删除」不被穿插。

  - type: JUDGE
    stem: 调用 Redisson 的 lock(10, TimeUnit.SECONDS) 后，看门狗会自动为锁续期。
    answer: F
    analysis: 显式传入 leaseTime 会关闭看门狗，锁会在 10 秒后自动过期。只有调用不带 leaseTime 的 lock() 时才启用看门狗（默认租期 30 秒，每 10 秒续期一次）。这是线上非常常见的一类误用。

  - type: JUDGE
    stem: Redis 主从架构下，即使使用分布式锁，也可能出现两个客户端同时持有同一把锁的情况。
    answer: T
    analysis: 主从异步复制的窗口内，master 在把锁同步到 slave 前宕机，slave 提升后锁信息丢失，其他客户端可在新 master 上获取同一把锁。因此分布式锁不能作为正确性的唯一保证，必须有幂等或唯一约束兜底。

  - type: CHOICE
    stem: 在 @Transactional 方法内部获取分布式锁，最可能导致什么问题？
    options:
      A: 锁无法获取
      B: 事务无法回滚
      C: 锁在事务提交前就已释放，其他线程可能读到旧数据并重复执行
      D: Redis 连接泄漏
    answer: C
    analysis: 若在事务方法内加锁并在方法返回前释放（如 finally 中 unlock），锁的释放发生在事务提交之前，存在「锁已释放、数据未提交」的窗口，其他线程拿到锁后读到旧数据。正确结构是锁在事务外层：先加锁 → 独立方法开事务并提交 → 再释放锁。
    difficulty: 3
````
