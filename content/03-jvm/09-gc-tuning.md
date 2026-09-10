---
slug: gc-tuning
title: 线上 GC 问题怎么调优？
module: jvm
tags: [GC, 调优, 排查, jstat, MAT]
difficulty: 3
frequency: 3
related:
  - slug: garbage-collection-algorithms
    type: PREREQUISITE
  - slug: g1-collector
    type: RELATED
  - slug: oom-troubleshooting
    type: DEEPEN
  - slug: runtime-memory-layout
    type: RELATED
---

## 电梯版回答

顺序不能反。第一步先排除内存泄漏：看每次 Full GC 之后老年代占用能不能回落到低位，如果反复 Full GC 后占用仍阶梯式上涨，那就是泄漏，此时调任何参数都是白费，要去做堆转储、用 MAT 找引用链。第二步才是调容量：`-Xms` 和 `-Xmx` 设成相同值避免堆反复伸缩，按存活对象总量给足老年代，必要时调新生代大小和 Survivor 比例。第三步才是调目标：重视延迟就压 `-XX:MaxGCPauseMillis` 或换 ZGC，重视吞吐就别过度追求低停顿。整个过程必须靠数据：JDK 9 起用统一的 GC 日志 `-Xlog:gc*:file=gc.log:time,uptime,level,tags`（之前是 `-XX:+PrintGCDetails` 那一套），配合 `jstat -gcutil` 看频率、`jmap -histo` 看对象、`jmap -dump` 加 MAT 看支配树和泄漏嫌疑。常见病征也好对号入座：Young GC 太频繁说明新生代太小或对象晋升太快；Full GC 频繁先查泄漏；单次停顿长就换收集器或调停顿目标。核心原则是先有数据再动手，不要凭直觉调参。

## 展开讲解

### 第一步：先排除内存泄漏

GC 调优最大也最常见的错误就是跳过这一步。**泄漏和容量不足的表现相似，处理方式却相反**：容量不足可以扩堆，泄漏扩堆只会把 OOM 推后、让 dump 更大更难分析。

判断方法看 **Full GC 之后**的老年代占用：

```
连续几次 Full GC 后，老年代占用：
  回落到低位并稳定    → 不是泄漏，可能是容量或分配速率问题
  不回落、还阶梯式上涨 → 极可能是泄漏，进入 dump 分析
```

为什么必须看「Full GC 之后」而不是「GC 之前」？因为 GC 前的老年代占用包含大量待回垃圾，只要回收正常就会降。**只有 GC 后的占用才约等于真正的存活对象量**。

确认泄漏后的路径：`jmap -dump:live,format=b,file=heap.hprof <pid>` 抓堆（注意 `-live` 会先触发一次 Full GC），用 MAT 打开，看支配树（Dominator Tree）里 retained size 最大的对象，以及 Leak Suspects 报告。常见根因是静态集合只增不减、缓存无上限、ThreadLocal 没清理、监听器注册了没注销、类加载器泄漏。

### 第二步：调容量

确认没有泄漏后，才开始动容量。三条原则：

1. **`-Xms` 与 `-Xmx` 设成相同值**。堆如果在运行中反复扩张和收缩，每次调整都是昂贵的操作，还会让 GC 日志的解读失真；固定堆大小也让容量规划更可预测。
2. **按存活对象总量给老年代留足空间**。老年代应明显大于「Full GC 后老年代占用」，留出以应对流量峰值和晋升。
3. **新生代大小要与分配速率匹配**。分配速率高、对象朝生夕死，就适当加大新生代，让短命对象在 Young GC 里就回收掉、别晋升到老年代。

容器里不要写固定 `-Xmx` 而应使用 `-XX:MaxRAMPercentage` 这类按内存比例设置的参数，避免与 cgroup 限额脱节被 OOM Kill。

### 第三步：调目标

容量合适后，才轮到在**停顿**与**吞吐**之间做取舍：

- 停顿优先：换 G1/ZGC，设置合理的 `-XX:MaxGCPauseMillis`
- 吞吐优先：用 Parallel，别过度追求低停顿
- 两者要平衡：G1 加合适的停顿目标和 IHOP

注意 `-XX:MaxGCPauseMillis` 是**软目标**，调得过小会让每次回收的 Region 太少、收不干净，Young GC 反而更频繁，得不偿失。

### 核心参数速查

| 参数 | 作用 | 注意 |
|---|---|---|
| `-Xms` / `-Xmx` | 堆初始 / 最大大小 | 线上设成相同值，避免动态伸缩 |
| `-Xmn` | 新生代大小 | 显式设置会干扰收集器的自适应；G1 下通常不设 |
| `-XX:MetaspaceSize` | 元空间首次扩容的初始阈值 | **不是上限**，达到后触发首次 Full GC 并动态调整 |
| `-XX:MaxMetaspaceSize` | 元空间上限 | 不设则只受本机内存限制 |
| `-XX:SurvivorRatio` | Eden 与单个 Survivor 的比值（默认 8） | 只在直观分代的收集器下意义明确 |
| `-XX:MaxTenuringThreshold` | 晋升年龄阈值（默认 15） | G1 会动态调整，可能不严格按此值 |
| `-XX:+HeapDumpOnOutOfMemoryError` | OOM 时自动抓堆 | 配合 `-XX:HeapDumpPath`，磁盘要留够 |
| `-XX:HeapDumpPath` | dump 文件路径 | 路径要可写且空间充足 |
| `-XX:+UseG1GC` | 选择收集器 | JDK 9 起为默认 |
| `-XX:MaxGCPauseMillis` | 停顿软目标 | G1/ZGC 用，不是硬保证 |
| `-XX:G1HeapRegionSize` | G1 的 Region 大小 | 1~32MB、2 的幂，影响 Humongous 判定 |

### 怎么看 GC 日志

JDK 9 起统一日志框架（JEP 158）取代了旧的 `PrintGC*` 系列：

```bash
# JDK 9 及以后
-Xlog:gc*:file=gc.log:time,uptime,level,tags

# 带日志轮转（4 个字段：what:output:decorators:output-options）
-Xlog:gc*:file=gc.log:time,uptime,level,tags:filecount=5,filesize=20M

# JDK 8 及以前
-XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:gc.log
```

`gc*` 表示挑选所有以 gc 开头的 tag（含 gc、gc+heap、gc+phases、gc+ergo 等），信息最全；`time,uptime,level,tags` 是每行的装饰信息（墙钟时间、进程运行时间、日志级别、tag），有了 uptime 才能把 GC 事件和其他现象对齐。

日志里重点找这些信号：

- Young GC 的间隔与耗时（频率是否异常）
- `Pause Full` 出现的次数与原因（理想是 0 次）
- `to-space exhausted`（G1 晋升失败）
- Humongous 相关警告（大对象过多）
- 每次 GC 前后各代的占用变化（看老年代是否持续上涨）

### 关键指标

| 指标 | 怎么看 | 关注点 |
|---|---|---|
| GC 频率 | 单位时间内 GC 次数 | Young GC 过密说明新生代小或分配过快 |
| 单次停顿 | 平均 / 最大 / P99 | 长尾停顿比平均值更值得关注 |
| Full GC 次数 | 统计周期内次数 | 理想为 0，频繁出现必须查原因 |
| 晋升速率 | 单位时间晋升到老年代的字节数 | 暴涨往往是对象生命周期变长或泄漏前兆 |
| GC 后老年代占用 | 每次 Full GC 后的水位 | 持续上涨即泄漏信号 |
| 吞吐 | 应用运行时间 / 总时间 | 过低说明 GC 占用了过多 CPU |

### 工具链

| 工具 | 用途 | 注意 |
|---|---|---|
| `jstat -gcutil <pid> 1000 10` | 每秒一次、共 10 次，看各代利用率与 GC 次数 | 生产上开销小，首选 |
| `jmap -histo[:live] <pid>` | 看对象直方图，定位哪个类实例最多 | `:live` 会触发一次 Full GC，慎用 |
| `jmap -dump:live,format=b,file=x.hprof <pid>` | 抓堆转储 | `:live` 会触发 Full GC，评估停顿影响 |
| `jstack <pid>` | 线程栈，排查死锁与卡顿 | 与 GC 日志时间对齐 |
| MAT | 支配树、Leak Suspects、retained size | 分析 hprof 的主力 |
| `jcmd <pid> GC.heap_info` | 查看堆各区域使用情况 | JDK 9+ 推荐的诊断入口 |
| `jcmd <pid> GC.run` | 主动触发一次 Full GC | 验证「Full GC 后是否回落」 |
| `-XX:NativeMemoryTracking=summary` + `jcmd VM.native_memory` | 排查堆外内存 | 定位直接内存/元空间增长 |

`jstat -gcutil` 的列值得记住：`S0 S1 E O M CCS YGC YGCT FGC FGCT GCT`，分别对应 Survivor0/1、Eden、老年代、元空间、压缩类空间利用率，以及 Young GC 次数/总耗时、Full GC 次数/总耗时、GC 总耗时。

### 常见病征与对策

| 病征 | 可能原因 | 对策 |
|---|---|---|
| Young GC 频繁但每次很短 | 新生代偏小 / 分配速率过高 | 加大新生代；减少临时对象（日志拼接、装箱、大数组） |
| Young GC 后存活对象快速上涨、老年代持续增长 | 对象生命周期变长或有泄漏 | 抓 dump 用 MAT 分析引用链 |
| Full GC 频繁 | 老年代不足或有泄漏 | **先排查泄漏**，再考虑扩堆或降低 IHOP 让并发标记早开始 |
| 单次停顿长 | 老年代大、收集器不合适 | 换 G1/ZGC，设停顿目标，减少大对象与 Humongous |
| Metaspace 持续增长 | 类加载器泄漏（动态代理、热部署反复加载） | 检查类加载器数量，dump 后分析 |
| GC 总耗时占比高、吞吐低 | 堆偏小或 GC 线程不足 | 扩堆、调 GC 线程数、评估收集器选择 |

## 追问链

### Q1: 线上 GC 问题，你的排查顺序是什么？

三步，顺序不能反：

1. **排除内存泄漏**：看反复 Full GC 后老年代占用是否回落。不回落就是泄漏，此时任何参数调整都是治标不治本
2. **调容量**：`-Xms = -Xmx` 固定堆，按存活对象总量给足老年代，新生代与分配速率匹配
3. **调目标**：在停顿与吞吐之间做取舍，设停顿目标或换收集器

背后的逻辑是：**泄漏是代码问题，容量是配置问题，目标是策略问题**。用参数去盖住泄漏，等于把 OOM 推后并让问题更难定位。

#### Q1.1: 怎么判断是内存泄漏还是单纯的堆不够？

核心看「Full GC 之后」的老年代水位：

```
情况 A：老年代增长，Full GC 后能回落到某个稳定低位
        → 不是泄漏，是容量不足或分配速率过高

情况 B：老年代持续上涨，Full GC 后也回不到之前的低位，
        每次 Full GC 后的水位都比上一次更高
        → 基本可判定泄漏
```

只做一次 Full GC 后没有回落不能立刻下结论——可能还有软引用、待 finalize 的对象、或本次并发标记没跑完。要**连续观察多次** Full GC 后的水位趋势。确认后抓 dump 用 MAT 找 GC Roots 的引用链，定位「谁一直持有这些对象」。

##### Q1.1.1: 那为什么调大堆有时反而让问题更晚爆发、更难排查？

因为泄漏对象的增长速度基本不变，堆越大，从「开始泄漏」到 OOM 的时间越长：

- 问题暴露更晚，可能跨了好几个版本、更难定位引入时间点
- 到 OOM 那天的 dump 文件里累积的对象更多、结构更复杂，MAT 分析更慢
- 期间 GC 频率和停顿会持续恶化，掩盖了真正的根因

所以「扩堆」只是把爆炸时间往后推。对泄漏而言，唯一有效的动作是修代码。

#### Q1.2: 为什么 `-Xms` 和 `-Xmx` 要设成一样？

因为堆的动态扩张和收缩本身就有成本，而且会让行为不可预测：

- 扩张/收缩涉及向操作系统申请或归还内存，伴随同步成本
- 堆大小变化会改变 GC 的触发时机与停顿，调优结果无法复现
- 固定堆大小后，GC 日志才容易横向对比

代价是常驻内存更高（空闲时也不会归还），这在容器里需要配合 `-XX:MaxRAMPercentage` 或明确的容器内存配额来规划。

##### Q1.2.1: 那 `-Xmn` 要不要显式设？

一般不要，尤其是 G1。G1 会根据 `MaxGCPauseMillis` 在 `G1NewSizePercent`（默认 5%）到 `G1MaxNewSizePercent`（默认 60%）之间**自动调整**新生代大小，显式设 `-Xmn` 等于关掉这个自适应，通常得不偿失。

只有在明确知道分配模式特殊（如大量短命对象需要更大 Eden）并做过压测对照时，才考虑显式指定，而且要意识到这让停顿预测失效。

### Q2: 调完容量之后，停顿还是长怎么办？

先分清停顿长发生在哪一类 GC：

- **Young GC 停顿长**：通常是新生代里存活对象太多，复制成本高；或 Survivor 比例不当导致大量晋升
- **Mixed/Full GC 停顿长**：老年代存活对象多，转移或整理代价高；或退化成 Full GC

对策按收益排序：先减少对象产生与存活（治本），再给足堆避免频繁触发，最后才调停顿目标或换收集器。如果单次停顿主要来自 Full GC，第一优先级是查清为什么退化到 Full GC（并发模式失败、晋升失败、Humongous 分配失败），而不是调 `MaxGCPauseMillis`。

#### Q2.1: `MaxGCPauseMillis` 调小就一定能降低停顿吗？

不一定，而且可能适得其反。

这个参数是软目标，G1 靠它决定每次往 CSet 里放多少 Region。调小后 G1 每次收的 Region 更少：

- 单次停顿可能降低（但受制于存活对象突增，不是硬保证）
- 收不干净的部分留到下一次，Young GC 更频繁
- 堆回收速度跟不上分配速度时，可能提前退化成 Full GC

所以它是在「单次停顿」与「回收效率」之间的调节旋钮，不是单调有效的降低按钮。合理做法是配合服务端到端延迟目标设定（比如目标 P99 延迟 100ms，GC 目标就不要卡到 1ms），再压测验证。

##### Q2.1.1: 那调小了没效果，下一步看什么？

看 GC 日志里停顿的**构成**：是并发标记周期太长、Mixed GC 每次都收不动，还是已经频繁 Full GC。同时用 `jstat -gcutil` 看老年代水位与晋升速率：

- 晋升速率高 → 去查为什么对象活过新生代（生命周期变长或泄漏）
- 老年代水位高、IHOP 触发太晚 → 适当降低 `-XX:InitiatingHeapOccupancyPercent` 让并发标记早开始
- 出现 `to-space exhausted` → 提高 `-XX:G1ReservePercent`（默认 10%）预留空间，或扩堆

如果这些都正常、只是堆实在太大导致停顿下不来，才是换收集器（ZGC）的时机。

### Q3: GC 日志里你会重点看哪些指标？

六个，按重要性排：

1. **Full GC 次数与原因**：理想为 0；出现就要查是并发模式失败、晋升失败还是 Humongous 分配失败
2. **GC 后老年代占用趋势**：判断泄漏与容量的核心指标
3. **单次停顿的最大值与 P99**：平均值会掩盖长尾，用户感知的是长尾
4. **GC 频率**：Young GC 间隔突然缩短往往意味着分配速率上升或新生代被压缩
5. **对象晋升速率**：晋升量暴涨通常先于 OOM 出现
6. **各代占用变化**：看 Eden/Survivor/Old 的进出是否健康，Survivor 是否过小导致提前晋升

#### Q3.1: 怎么区分 Young GC 频繁和 Full GC 频繁？

看日志里的暂停类型与耗时量级：

```
Pause Young (...)      → 年轻代回收，通常停顿短（毫秒到几十毫秒）
Pause Full (...)       → 整堆回收，停顿长，可能到秒级
```

`jstat -gcutil` 里对应的是 `YGC`/`YGCT` 与 `FGC`/`FGCT` 两组列，看哪个在快速上涨。两者的指向完全不同：

- Young GC 频繁但停顿短：多数时候不影响可用性，属容量/分配速率的调优问题
- Full GC 频繁：几乎总是严重问题，优先查泄漏与退化原因

把两者混为一谈、看到 GC 频繁就去扩堆，是最常见的误判。

## 常见坑

- **「GC 频繁就调大堆」** —— 先分清是 Young GC 频繁还是 Full GC 频繁。Young GC 频繁通常是分配速率高或新生代小；盲目扩堆可能无效，还会让单次 Full GC 更久
- **「调大堆就能解决 GC 问题」** —— 如果是泄漏，堆越大只是把 OOM 推后、让 dump 更大更难分析；容量调优的前提是没有泄漏
- **「Full GC 后老年代不下降是正常现象」** —— 单次不降可能涉及软引用/待 finalize 对象，但连续多次 Full GC 后水位仍阶梯式上涨基本可判定泄漏，此时该去抓 dump 而不是继续调参
- **「`MaxGCPauseMillis` 是硬指标，设了就能达到」** —— 它是软目标，调得过小会让每次回收的 Region 太少、更频繁甚至退化 Full GC
- **「`-XX:MetaspaceSize` 是元空间上限」** —— 它是触发首次元空间扩容/Full GC 的初始阈值，上限是 `-XX:MaxMetaspaceSize`
- **「`-Xmn` 设得越大越好」** —— 新生代过大会压缩老年代，且单次 Young GC 停顿与存活对象数相关；G1 下显式设置还会关掉自适应
- **「在 JDK 17 上继续用 `-XX:+PrintGCDetails`」** —— JDK 9 起统一日志框架已取代旧的 PrintGC* 系列，应使用 `-Xlog:gc*:...`
- **「`jmap -dump` 是轻量操作，随时可以抓」** —— `-dump:live` 会先触发一次 Full GC，生产上必须评估停顿影响
- **「只看 GC 单次停顿，不看频率和吞吐」** —— 停顿、频率、吞吐要一起看；一味压低单次停顿可能让回收效率崩掉
- **「容器里用固定 `-Xmx` 就行」** —— 未与 cgroup 限额对齐的固定值容易导致容器被 OOM Kill，应按内存比例设置并留出堆外空间

## 加分点

- 能背出 `jstat -gcutil` 的列含义与用途：`S0 S1 E O M CCS YGC YGCT FGC FGCT GCT`，一眼从 `FGC` 和 `O` 的变化判断泄漏与容量问题
- 会用 MAT 的**支配树（Dominator Tree）**找 retained size 最大的对象，用 Leak Suspects 快速定位可疑根；比 `jmap -histo` 只能看「谁多」更进一步，能看出「谁占的内存多且被谁持有」
- 坚持先量后调：在预发或压测环境复现、小流量灰度对照，而不是线上直接改参数
- 会把 GC 事件与其他现象用 **uptime 装饰**对齐：GC 长停顿是否与接口超时、线程池堆积发生在同一时刻
- 知道容器里应使用 `-XX:MaxRAMPercentage`（JDK 10 起提供，Java 8u191 有对应回移）按限额比例设堆，而不是写死 `-Xmx`
- 知道**类加载器泄漏**是 Metaspace 持续增长的典型根因（热部署、动态代理、脚本引擎反复创建类加载器），可用 `jcmd <pid> VM.metaspace` 查看
- 会用 `jcmd <pid> GC.run` 主动触发 Full GC 来验证「老年代是否回落」，这是判断泄漏最快的一步
- 会配 `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/data/dump/`，并确认磁盘空间与 dump 时长，避免 OOM 现场丢失

## 版本差异

| 版本 | 差异 |
|---|---|
| JDK 8 | 永久代（PermGen）被元空间取代，`-XX:PermSize` / `-XX:MaxPermSize` 失效，改用 `-XX:MetaspaceSize` / `-XX:MaxMetaspaceSize`；GC 日志用 `-XX:+PrintGCDetails` / `-XX:+PrintGCDateStamps` / `-Xloggc:gc.log` 等旧参数 |
| JDK 9 起 | 统一日志框架（JEP 158）取代旧 GC 日志参数，改用 `-Xlog:gc*:file=gc.log:time,uptime,level,tags`；G1 成为默认收集器（JEP 248），不显式指定时的调参对象是 G1 |
| JDK 10 | 提供按容器内存比例设堆的参数（如 `-XX:MaxRAMPercentage`），配合容器感知避免固定 `-Xmx` 与 cgroup 限额脱节 |
| JDK 14 | CMS 正式移除，收集器选择收敛为 G1 / ZGC / Shenandoah / Parallel / Serial |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 线上 GC 问题排查的第一步应该做什么？
    options:
      A: 调大堆内存
      B: 先排除内存泄漏，观察 Full GC 后老年代占用是否回落
      C: 直接换 ZGC 收集器
      D: 把 MaxGCPauseMillis 调到 1ms
    answer: B
    analysis: 调优顺序是「先排除泄漏 → 再调容量 → 最后调目标」。若是泄漏，调参数和换收集器都只是把 OOM 推后。判断依据是连续多次 Full GC 后老年代占用是否回落并稳定。

  - type: JUDGE
    stem: -XX:MetaspaceSize 是元空间能够使用的最大内存上限。
    answer: F
    analysis: MetaspaceSize 是元空间扩容的初始阈值，达到后会触发首次元空间扩容并伴随一次 Full GC，之后虚拟机会动态调整。真正的上限是 -XX:MaxMetaspaceSize。

  - type: MULTI
    stem: 关于 GC 调优与排查，下列说法正确的有？
    options:
      A: -Xms 与 -Xmx 设成相同值可以避免堆在运行中反复伸缩
      B: -XX:MaxGCPauseMillis 是硬性停顿上限
      C: jmap -dump:live 会先触发一次 Full GC，生产上要评估停顿影响
      D: 对象晋升速率的突然上涨往往是泄漏或对象生命周期变长的前兆
    answer: ACD
    analysis: B 错误，MaxGCPauseMillis 是软目标，调得过小反而可能让回收效率下降、退化 Full GC。A、C、D 都是正确实践，其中 C 的 -dump:live 触发 Full GC 是常见误用点。

  - type: CLOZE
    stem: |
      补全 JDK 9 起的 GC 日志开启方式与 OOM 自动堆转储配置：
      ```bash
      # 统一日志：输出所有 gc 相关 tag
      -{{1}}:gc*:file=gc.log:time,uptime,level,tags

      # OOM 时自动抓取堆转储
      -XX:+{{2}}
      -XX:HeapDumpPath=/data/dump/
      ```
    blanks:
      - ["Xlog", "Xlog（统一日志参数）"]
      - ["HeapDumpOnOutOfMemoryError", "HeapDumpOnOutOfMemoryError（OOM 时转储）"]
    analysis: JDK 9 起 GC 日志统一走 -Xlog，gc* 选取所有 gc 相关 tag，装饰项 time,uptime,level,tags 便于与其他现象对齐时间。HeapDumpOnOutOfMemoryError 配合 HeapDumpPath 可保留 OOM 现场。
    difficulty: 3
````
