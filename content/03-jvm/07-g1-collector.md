---
slug: g1-collector
title: G1 收集器的工作原理是什么？
module: jvm
tags: [GC, G1, 垃圾回收, Region, 调优]
difficulty: 3
frequency: 3
related:
  - slug: garbage-collection-algorithms
    type: PREREQUISITE
  - slug: gc-tuning
    type: DEEPEN
  - slug: zgc
    type: CONTRAST
  - slug: runtime-memory-layout
    type: RELATED
---

## 电梯版回答

G1 把堆切成若干大小相等的 Region（1~32MB、必须是 2 的幂，由 `-XX:G1HeapRegionSize` 指定，默认按堆大小反推出大约 2048 个），Region 在运行时动态扮演 Eden、Survivor、Old。超过 Region 一半大小的对象叫 Humongous，会占用连续的多个 Region。G1 不追求一次收完整个堆，而是设一个软目标 `-XX:MaxGCPauseMillis`（默认 200ms），用衰减均值预测每个 Region 的回收收益和耗时，每次挑「收益/成本比」最高的 Region 组成回收集合 CSet——这正是 Garbage First 这个名字的由来。回收分两种：Young GC 只收年轻代 Region 且 STW；Mixed GC 除年轻代外还回收一部分老年代 Region，收哪些由并发标记选出的候选集决定，而并发标记在堆占用超过 `-XX:InitiatingHeapOccupancyPercent`（默认 45%）时启动。并发标记用 SATB 快照加写前屏障，跨 Region 引用靠每块 Region 的 Remembered Set（底层是卡表）记录，避免全堆扫描。如果并发标记跟不上分配速度（并发模式失败）、晋升时没有空 Region（晋升失败）或 Humongous 分配失败，G1 就会退化成 Full GC。

## 展开讲解

### Region 布局：把堆切成等大的小块

G1 把连续的堆地址切成一堆等大的 Region，不再要求新生代和老年代在物理上各自连续：

```
堆空间（例如 4GB）= [R0][R1][R2][R3] ... [R2047]
每块 Region 运行时的角色由 G1 动态指定：
  Eden / Survivor / Old / Humongous / 空闲
```

- Region 大小范围 **1MB ~ 32MB**，且必须是 2 的幂，通过 `-XX:G1HeapRegionSize` 指定
- 不显式指定时，G1 按堆大小反推一个值，目标是让 **Region 数量在 2048 个左右**
- 角色是按需分配的：某块 Region 这个周期是 Eden，下个周期可能变成 Old，回收后还可能被重新当作 Eden

所以「G1 没有新生代老年代」是错的——**分代还在，只是从物理连续变成了逻辑角色**。

### Humongous 对象

如果一个对象的大小**超过 Region 大小的一半**，G1 认为它不可能在 Region 内做有效的复制回收，直接标记为 Humongous：

```
对象大小 <= Region/2              → 普通分配（Eden 的 Region 内）
对象大小 >  Region/2              → Humongous，独占 Region（可能浪费一半空间）
对象大小 >= Region                → Humongous，占用连续多个 Region
```

占用多个 Region 的 Humongous 对象会记录为 `startsHumongous`（首块）+ `continuesHumongous`（后续块）。它在**并发标记周期中被单独特判**，回收时机常与 Young GC 一起处理。

Humongous 的问题是**容易造成碎片和提前 Full GC**：一块 Region 里塞了一个略大于半块的大对象，剩下一半往往用不上；连续 Region 不够时只能触发 Full GC 来腾地方。线上如果日志频繁出现 Humongous 分配失败，先去看代码里有没有大数组、大缓存对象、一次性拼出来的超大 JSON/Excel。

### 停顿预测模型与 CSet

G1 名字里的 "Garbage First" 就是一个明确的策略：**优先回收垃圾最多的 Region**。

G1 会对每次回收做记录，用**带衰减因子的指数平均**（近期样本权重更高）来预测：

- 这块 Region 大概有多少存活对象（决定复制成本）
- 回收它能释放多少空间（收益）
- 加上 Remembered Set 扫描等固定开销，估计耗时

然后按 `收益 / 成本` 从高到低选 Region 组成**回收集合 CSet（Collection Set）**，直到预测累计耗时逼近 `-XX:MaxGCPauseMillis`（默认 200ms）就停止。

**`MaxGCPauseMillis` 是软目标，不是硬上限**。G1 只能通过「这次少收几块 Region」来逼近它；如果存活对象突然暴增导致预测失准，停顿仍可能超目标。硬性保证在现实中不存在。

### 三种回收：Young GC / Mixed GC / Full GC

| 类型 | 收什么 | 是否 STW | 触发 |
|---|---|---|---|
| Young GC | 只收 Eden/Survivor 的 Region | 是（并行多线程） | Eden 占用达到预期值，年轻代大小在 `G1NewSizePercent`(5%) ~ `G1MaxNewSizePercent`(60%) 间自适应 |
| Mixed GC | 年轻代 Region + **一部分**老年代 Region | 是（可分成多次） | 并发标记周期完成后，按 CSet 分多次回收 |
| Full GC | 整堆 | 是 | 并发模式失败 / 晋升失败 / Humongous 分配失败 |

Mixed GC 的两条约束：

- 只收**存活率足够低**的老年代 Region：存活率超过 `-XX:G1MixedGCLiveThresholdPercent`（默认 85%）的 Region 不进 CSet，因为复制它不划算
- 可容忍的垃圾占比由 `-XX:G1HeapWastePercent`（默认 5%）控制，一个并发标记周期内的 Mixed GC 次数目标由 `-XX:G1MixedGCCountTarget`（默认 8）控制

### 并发标记：SATB 与写前屏障

Mixed GC 要「部分回收老年代」，前提是**先知道每块老年代 Region 里有多少存活对象**，这就需要并发标记。G1 的并发标记周期：

```
初始标记 (STW，通常搭在 Young GC 上)
  → 根区域扫描（并发）
  → 并发标记（并发，贯穿整个周期）
  → 最终标记 Remark (STW)
  → 清理 Cleanup (STW，统计各 Region 存活并选出候选)
```

标记算法是 **SATB（Snapshot-At-The-Beginning）**：按「标记开始那一刻的对象图快照」判定存活。实现靠**写前屏障（pre-write barrier）**——引用被覆盖之前先把旧值记进 SATB 队列：

```java
void pre_write_barrier(Object field, Object old_value) {
    if (old_value != null && is_white(old_value)) {
        push_to_satb_queue(old_value);
    }
}
```

代价是产生**浮动垃圾**：标记期间新出现的垃圾这一轮收不掉，留到下一轮。好处是不需要 CMS 那样漫长的重新标记阶段——Remark 只需处理 SATB 队列。

### Remembered Set 与卡表

跨 Region 引用带来一个麻烦：回收 Regoin A 时，怎么知道 Regoin B 里有没有对象引用 A？如果不做记录，就得扫描整个堆。

G1 给**每块 Region 配一个 Remembered Set（RSet）**，记录「谁指向我」。用户线程写入引用时通过**写后屏障**把所在卡页标脏，GC 只扫这些脏卡对应的来源 Region：

```
Region B 里的对象引用 Region A → 写入时把 B 中那张卡页标脏
Region A 的 RSet 里记下「B 的某张卡页指向我」
回收 A 时只扫这些卡页对应的对象作为额外根，不扫全堆
```

RSet 的实现底层仍是 **512 字节的卡表（Card Table）**，并按稀疏表 → 哈希表 → 位图逐级升级。RSet 是 G1 的主要额外内存开销，可能占到堆的百分之几。

### 与 CMS 的对比

| 维度 | CMS | G1 |
|---|---|---|
| 堆布局 | 物理分代，Young/Old 各自连续 | 等大 Region，逻辑分代 |
| 标记 | 增量更新 + 写屏障，需较长 Remark | SATB + 写前屏障，Remark 很短 |
| 碎片 | 标记清除，有碎片，靠定时整理 | Region 内复制，无碎片 |
| 停顿控制 | 不设停顿目标 | `MaxGCPauseMillis` 软目标 + 收益预测 |
| Full GC | 退化时单线程串行，极慢 | 退化时仍可能很长，JDK 10 起 Full GC 并行 |

G1 本质上是**把复制算法用在「局部存活率低」的 Region 上**，从而绕开了「老年代存活率高、不适合复制」这个传统约束。

## 追问链

### Q1: G1 的堆布局和传统分代有什么不同？

传统分代（Serial/Parallel/CMS）要求新生代和老年代各自是一段**物理连续**的内存，而且比例启动后基本固定。G1 把堆切成等大 Region，新生代、老年代只是 Region 的角色标签，不需要连续。

好处有三点：回收粒度从「整个新生代或整个老年代」变成「若干 Region」，可以实现增量式的部分回收；不需要在内存中预留一段连续的巨大老年代，大堆上分配更灵活；大对象（Humongous）可以横跨连续 Region，不受单一分代边界限制。

坏处是引入了 RSet 和写屏障的额外开销，内存与 CPU 成本都比 Parallel 高。

#### Q1.1: Region 是逻辑分代，那 G1 怎么知道一块 Region 是 Eden 还是 Old？

G1 内部为每块 Region 维护元数据，用一组集合来标记角色：属于年轻代的 Region 放进 `youngList`，属于 Eden 或 Survivor 的分开记录，老年代 Region 在并发标记的 Cleanup 阶段根据存活率决定是否进入候选回收集合。

角色在回收时动态改写：Young GC 把存货对象从 Eden/Survivor 复制进新的 Survivor Region，被清空的 Region 归还到空闲列表，下次可以重新赋予 Eden 角色。所以「Eden 区」在 G1 里是一个会移动的 Region 集合，而不是固定地址段。

##### Q1.1.1: 那 Humongous 对象为什么必须单独处理？

因为它打破了「一个对象装在一块 Region 里」的假设。Region 内的复制回收依赖对象能在 Region 内分配和搬迁；一个比 Region 还大的对象根本放不进去，更没法用复制算法整体搬走。

所以 G1 把这类对象标记为 Humongous，独占（或连续独占）若干 Region，并在并发标记中单独跟踪它们的存活。代价是空间浪费和分配失败风险：

- 对象大小略超 Region/2 时，会独占一整块 Region，剩下一半基本浪费
- 分配需要**连续**的空闲 Region，找不到就直接触发 Full GC 来腾地方

这也是「小 Region 反而可能更糟」的原因：Region 越小，Humongous 的判定门槛（Region/2）越低，越多普通大对象被打成 Humongous。

#### Q1.2: G1 怎么知道哪些 Region 值得回收？

靠**回收收益预测**。G1 在运行中持续记录每次回收中每块 Region 的存活数据，用带衰减因子的指数平均（近期样本权重更高）来预测两个量：

- **收益**：回收这块 Region 能释放多少空间（等价于存活率越低、收益越高）
- **成本**：复制存活对象 + 扫描 RSet 需要多久

然后按「收益 / 成本」排序，从高分开始往 CSet 里装，累计预测耗时接近 `-XX:MaxGCPauseMillis`（默认 200ms）就停。这就是 Garbage First：先收垃圾最多的。

注意这解释了一个常见疑惑：**G1 收老年代是「挑肥拣瘦」的**，不是按地址顺序全扫一遍。

##### Q1.2.1: CSet 里的 Region 回收时，怎么处理别的 Region 对它的引用？

靠 Remembered Set。每块 Region 都有一份 RSet，记录「哪些其他 Region 的哪些卡页指向我」。回收某块 Region 时：

1. 把 CSet 里所有 Region 的 RSet 合并
2. 只扫描这些 RSet 指向的来源卡页，把其中引用了 CSet 对象的部分当作额外 GC Roots
3. 复制存活对象到目标 Region，更新引用

这样就不需要扫描整个堆。RSet 是**空间换时间**：增加内存与写屏障开销，换来回收时只需扫描相关卡页而不是全堆。这也是 G1 在大堆上比 CMS 停顿更可控的原因之一。

### Q2: Young GC 和 Mixed GC 的区别是什么？

| | Young GC | Mixed GC |
|---|---|---|
| 回收范围 | 只收年轻代 Region | 年轻代 Region + 部分老年代 Region |
| 前提 | 无 | 必须先完成一次并发标记周期 |
| 停顿 | STW，多线程并行 | STW，但会拆成多次执行 |
| 频率 | 高 | 每个并发标记周期后连续发生若干次 |

Mixed GC 名字里的 "Mixed" 就是「混着收」：一次回收里既有年轻代也有老年代。老年代只挑存活率低的那部分，逐步把老年代治理到健康水位，而不是像 Full GC 那样一次扫整堆。

#### Q2.1: Mixed GC 的「部分老年代」是怎么选出来的？

来自并发标记周期末尾的 **Cleanup** 阶段。并发标记会把每块老年代 Region 的存活对象量统计出来（用 SATB 快照保证正确性），Cleanup 阶段据此：

- 存活率低于 `-XX:G1MixedGCLiveThresholdPercent`（默认 85%）的 Region 进入候选集
- 候选集还要满足可容忍垃圾占比 `-XX:G1HeapWastePercent`（默认 5%）的约束
- 最终按收益/成本排序，分摊到 `-XX:G1MixedGCCountTarget`（默认 8）次 Mixed GC 中执行

所以老年代是**分批、渐进**被清理的，每次只动最划算的一批。

##### Q2.1.1: 那会不会出现收不完、最后退化成 Full GC？

会，这正是 G1 最主要的退化路径。三种触发方式：

| 退化原因 | 场景 |
|---|---|
| 并发模式失败 | 并发标记还没跑完，堆已经先被填满，只能停下一切做 Full GC |
| 晋升失败（to-space exhausted） | 年轻代对象要晋升，但 CSet 没有足够的空闲 Region 承接复制，Evacuation 失败 |
| Humongous 分配失败 | 找不到连续的 Region 放置大对象 |

退化后的 Full GC 是标记-整理式的整堆回收（可能做压缩），停顿明显长于 Young/Mixed GC。JDK 10 起 G1 的 Full GC 改为并行（JEP 307），此前是单线程串行，更加缓慢。

规避手段通常是：给足堆和预留空间（`-XX:G1ReservePercent`，默认 10%）、适当降低 `-XX:InitiatingHeapOccupancyPercent` 让并发标记早点开始、以及从源头减少 Humongous 对象和过高的分配速率。

### Q3: G1 的并发标记为什么用 SATB，而不是 CMS 的增量更新？

主要为了缩短 Remark。增量更新（CMS）需要记录「黑色对象新增的指向白色对象的引用」，并在重新标记阶段以这些记录为根再扫一遍，这个阶段必须 STW，堆越大越久。SATB 改为按标记开始时的快照判定存活，只需处理写前屏障记下来的旧引用队列，Remark 阶段很短。

代价是浮动垃圾：快照之后新产生的垃圾这一轮收不到。G1 能接受这个代价，因为它的停顿目标（200ms 量级）比 CMS 宽松，且浮动垃圾量正比于并发标记期间的分配速率，通常远小于整堆。

还有一层结构性原因：G1 可以在**后续的 Young/Mixed GC 中顺带处理 SATB 队列**，把标记成本摊到多次回收里，而不是集中在一个 Remark 上。

#### Q3.1: SATB 用写前屏障，和 ZGC 的读屏障有什么区别？

作用时机和目的都不同：

| | 写屏障（G1 的 SATB） | 读屏障（ZGC） |
|---|---|---|
| 触发时机 | 引用**被覆盖之前** | 每次**读取引用**时 |
| 记录什么 | 被删除的旧引用（保证快照不丢） | 检查指针颜色，必要时修正 |
| 支持的能力 | 并发标记 | 并发标记 + **并发转移**（对象移动时用户线程仍能正确访问） |
| 成本分布 | 写操作变贵 | 读操作变贵，通过 JIT 内联成几条指令 |

G1 不做并发转移——它的复制（Evacuation）是 STW 的。ZGC 之所以能边移动对象边让用户线程访问，是因为读屏障能在读到旧地址时「自愈」成新地址。所以写屏障够 G1 用，却撑不起 ZGC 的目标。

## 常见坑

- **「G1 就是低延迟收集器，所以一定比 CMS 好」** —— G1 的定位是「可预测停顿 + 大堆友好」，不是无条件低延迟。它引入写屏障和 RSet 开销，小堆、高吞吐场景下 Parallel 往往更合适；停顿目标也只是软目标
- **「G1 没有新生代和老年代了」** —— 分代仍存在，只是从物理连续变成 Region 的逻辑角色
- **「`MaxGCPauseMillis` 是硬性上限，设了就一定达到」** —— 它是软目标，G1 通过「少收几块 Region」逼近，存活对象突增时仍可能超标
- **「Mixed GC 会一次性回收所有老年代 Region」** —— 只回收存活率低于 `G1MixedGCLiveThresholdPercent`（默认 85%）的候选 Region，且分摊到多次 Mixed GC
- **「G1 的 Region 越大越好 / 固定 2MB」** —— 范围 1~32MB、必须是 2 的幂，默认按堆推算使 Region 数约 2048；Region 越大，Humongous 门槛越高但回收粒度越粗
- **「G1 的 Full GC 也是并发/低停顿的」** —— Full GC 是整堆 STW，JDK 10 起才并行化，之前是单线程串行，非常慢
- **「Humongous 分配失败只是慢一点」** —— 找不到连续 Region 会直接触发 Full GC，是 G1 的主要退化原因之一
- **「G1 不需要卡表/记忆集」** —— RSet 正是 G1 避免全堆扫描的核心结构，底层是 512 字节的卡表加写后屏障
- **「`-XX:MaxGCPauseMillis` 设得越小越好」** —— 设得过小会让每次 CSet 太小、收不干净，Young GC 更频繁，甚至堆占用失控退化成 Full GC

## 加分点

- 能说清 **RSet 是空间换时间**：增加写屏障与内存开销，换来回收时只扫相关卡页，这是 G1 相比 CMS 停顿更可控的结构性原因
- 知道 G1 的 **PLAB（Promotion Local Allocation Buffer）** 用于并行 Evacuation 时各线程的晋升分配，避免竞争目标 Region
- 知道 JDK 10 的 **JEP 307** 让 G1 Full GC 并行化，修复了此前 Full GC 单线程慢到不可接受的痛点
- 能对比 CMS：G1 用 SATB 换短 Remark、用 Region 复制换无碎片，代价是写屏障和 RSet 开销；CMS 用增量更新换低浮动垃圾，代价是长 Remark 和碎片
- 会用 GC 日志里的关键字定位问题：`Pause Young (Normal) (G1 Evacuation Pause)`、`Pause Full (G1 Compaction Pause)`、`to-space exhausted`（晋升失败）、`Humongous` 相关警告
- 知道 `-XX:G1ReservePercent`（默认 10%）是为 Evacuation 预留的兜底空间，预留不足会直接导致晋升失败
- 知道 G1 年轻代在 `G1NewSizePercent`(5%) 到 `G1MaxNewSizePercent`(60%) 之间自适应，硬设 `-Xmn` 会关掉这个自适应

## 版本差异

| 版本 | 差异 |
|---|---|
| JDK 7 | G1 作为实验性收集器首次可用，需 `-XX:+UnlockExperimentalVMOptions -XX:+UseG1GC` |
| JDK 8 | G1 不再需要解锁实验开关，`-XX:+UseG1GC` 直接可用，但仍非默认 |
| JDK 9 | G1 成为默认收集器（JEP 248），CMS 被标记废弃 |
| JDK 10 | G1 的 Full GC 并行化（JEP 307），修复 Full GC 单线程串行过慢的问题 |
| JDK 14 | CMS 彻底移除，G1 成为主流选择之一 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: G1 中多大的对象会被判定为 Humongous 对象？
    options:
      A: 超过一个 Region 大小
      B: 超过 Region 大小的一半
      C: 超过老年代容量的 1%
      D: 超过 Survivor 的总大小
    answer: B
    analysis: 判定阈值是 Region 大小的一半。超过一半即标记为 Humongous 并独占 Region（可能浪费一半空间）；若达到或超过一个 Region，则占用连续多个 Region。Region 越小，越多大对象会落入这个判定。

  - type: JUDGE
    stem: G1 的 -XX:MaxGCPauseMillis 是一个硬性停顿上限，只要设置了就一定不会超过。
    answer: F
    analysis: 它是软目标。G1 靠回收收益预测决定每次往 CSet 里放多少 Region，使预测耗时逼近该值；存活对象突增、预测失准时停顿仍可能超标。现实中不存在硬性停顿保证。

  - type: MULTI
    stem: 关于 G1 的回收过程，下列说法正确的有？
    options:
      A: Young GC 只回收年轻代 Region，且是 STW 的
      B: Mixed GC 会回收年轻代 Region 和一部分存活率较低的老年代 Region
      C: 并发模式失败、晋升失败、Humongous 分配失败都可能退化成 Full GC
      D: G1 的复制转移（Evacuation）是与用户线程并发执行的
    answer: ABC
    analysis: D 错误。G1 的 Evacuation 是 STW 的，所以它不需要读屏障；与用户线程并发进行对象转移是 ZGC 的能力，靠染色指针加读屏障实现。A、B、C 都是 G1 的准确描述。

  - type: CLOZE
    stem: |
      补全 G1 判定 Humongous 对象与选择回收集合的关键条件：
      ```java
      // 对象大小超过 Region 的一半，标记为 Humongous
      boolean humongous = objSize > regionSize / {{1}};

      // 选 CSet 时，用衰减均值预测收益与成本，
      // 累计预测耗时逼近下面这个软目标就停止：
      // -XX:{{2}} （默认 200ms）
      ```
    blanks:
      - ["2", "二"]
      - ["MaxGCPauseMillis", "MaxGCPauseMillis（最大 GC 停顿）"]
    analysis: Humongous 阈值是 Region 大小的一半。G1 的停顿预测以 MaxGCPauseMillis 为软目标，按「收益/成本比」从高到低挑 Region 组成 CSet，这正是 Garbage First 名字的来源。
    difficulty: 3
````
