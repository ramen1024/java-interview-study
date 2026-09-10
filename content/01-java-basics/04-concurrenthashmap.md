---
slug: concurrenthashmap
title: ConcurrentHashMap 是怎么实现线程安全的？1.7 和 1.8 有什么区别？
module: java-basics
tags: [ConcurrentHashMap, 并发, 分段锁, CAS]
difficulty: 3
frequency: 3
related:
  - slug: hashmap-thread-unsafe
    type: PREREQUISITE
  - slug: hashmap-resize
    type: RELATED
---

## 电梯版回答

核心思路是「把锁的粒度做细」。Java 7 用 Segment 分段锁，默认 16 段，每段是一个继承 ReentrantLock 的小 HashMap，不同段可以并发写，并发度受段数限制。Java 8 改成 CAS 加 synchronized 锁单个桶的头节点：桶为空时用 CAS 无锁写入，桶非空时只锁住这个桶，锁粒度从段细化到桶，并发度随元素增长而提升。扩容支持多线程协助迁移，迁移中的桶放一个 ForwardingNode 让其他线程知道该去帮忙而不是干等。另外它的 size() 在并发下返回近似值，并且不允许 null key 和 null value。

## 展开讲解

### Java 7：Segment 分段锁

```java
static final class Segment<K,V> extends ReentrantLock {
    transient volatile HashEntry<K,V>[] table;
    transient int count;
    transient int modCount;
}

final Segment<K,V>[] segments;   // 默认 16 个
```

结构上是「两层哈希」：

```
ConcurrentHashMap
├── Segment[0]  ← ReentrantLock，各自独立加锁
│   └── HashEntry[] → 链表
├── Segment[1]
└── ...
```

- **定位**：先用 hash 的高位选 Segment，再用低位选 Segment 内的桶下标
- **并发度**：等于 Segment 数量（默认 16，构造时可通过
  `concurrencyLevel` 指定），最多 16 条线程同时写
- **扩容**：只扩 Segment 内部的数组，不影响其他 Segment

局限很明显：**并发度被段数封顶**。段数在构造时就定死，
即使后来元素涨到千万级，也还是 16 条线程能并行写。
而且一旦要在全表层面统计（size），就得上锁。

### Java 8：CAS + synchronized 锁桶头

```java
transient volatile Node<K,V>[] table;

// 桶为空：CAS 无锁写入
if (casTabAt(tab, i, null, new Node<K,V>(hash, key, value, null)))
    break;

// 桶非空：只锁这个桶的头节点
synchronized (f) {
    if (tabAt(tab, i) == f) {      // 双重检查，锁期间头节点没被换掉
        // 链表或红黑树插入
    }
}
```

三个变化：

| | Java 7 | Java 8 |
|---|---|---|
| 锁粒度 | Segment（一段多个桶） | 单个桶的头节点 |
| 空桶写入 | 需要加段锁 | CAS 无锁 |
| 并发度 | 固定为段数 | 随桶数组增长而提升 |
| 哈希冲突结构 | 只有链表 | 链表 + 红黑树 |
| 扩容 | 单线程扩自己的段 | 多线程协助迁移 |

为什么锁「头节点」是安全的：所有插入都必须先拿到桶的头节点，
锁住它就等于锁住这个桶。而 `synchronized` 在这里优于 `ReentrantLock`，
因为 JDK 6 之后偏向锁/轻量级锁的优化让**无竞争场景几乎没有开销**，
比显式加锁更划算。

### 关键字段 sizeCtl

`sizeCtl` 是一个多语义的 `volatile int`，一张表把它的含义说清楚：

| 取值 | 含义 |
|---|---|
| 0 | 未初始化，用默认容量 16 |
| 正数 | **下一次扩容的阈值**（初始化后）或**初始容量**（未初始化时） |
| -1 | 正在初始化（`MOVED` 之外的负值中最小） |
| -(1 + 正在扩容的线程数) | 正在扩容，如 -2 表示 1 线程在扩容 |

判断正在扩容用 `sizeCtl < 0`，更精确的辅助判断是
`(sc >>> RESIZE_STAMP_SHIFT) == resizeStamp(n)`——只看高位戳记，
避免不同的扩容批次混淆。

### 多线程协助扩容

这是 1.8 最值得讲的设计。扩容时用 `transferIndex` 做**任务分片**：

```
table.length = 64，要向 128 迁移
transferIndex 从 64 开始，每个线程用 CAS 认领一段（步长 16）
线程 A 认领 [48, 64)
线程 B 认领 [32, 48)
线程 C 认领 [16, 32)
...
认领不到（transferIndex <= 0）就退出协助
```

迁移完成的桶会被放一个 **`ForwardingNode`**，它的 `hash` 值是
常量 `MOVED = -1`，并持有新表的引用。其他线程遇到它：

- 写入时：`if (fh >= 0)` 判断发现是 ForwardingNode，
  则调用 `helpTransfer()` **加入协助迁移**，而不是阻塞等锁
- 读取时：跟着 `f.next` 找到新表继续查，所以**扩容期间读请求不会被阻塞**

这就是「协助扩容」相比「扩容时全局加锁」的最大优势：
扩容不再是单点瓶颈，而是所有写入线程共同分担的工作。

### size() 为什么是近似值

1.8 用 `baseCount` 加一个 `CounterCell[]` 数组分散计数：

```java
private transient volatile long baseCount;
private transient volatile CounterCell[] counterCells;

// 类似 LongAdder：先尝试 CAS baseCount，
// 失败则在一个随机的 CounterCell 槽上累加
if (casBase(b, b + 1)) return;
CounterCell[] cs = counterCells;
if (cs != null) {
    int idx = ThreadLocalRandom.getProbe() & (cs.length - 1);
    if (casCell(cs[idx], ...)) return;
}
```

这么做的目的是**避免所有线程争抢同一个计数器**。
代价是 `sumCount()` 要把 baseCount 和所有 CounterCell 加起来，
而求和期间其他线程还在改——所以返回的是**近似值**。

Javadoc 明确写了：`size()` 是「一个估计值」，
并发下可能不准。它只在没有并发更新时精确。

### 不允许 null

```java
if (key == null || value == null) throw new NullPointerException();
```

原因不是「懒得处理」，而是**并发下无法消除二义性**：

```java
if (map.containsKey(key)) {   // 此刻存在
    V v = map.get(key);       // 另一线程可能已 remove
    // v 为 null：本来就没有，还是刚被删了？
}
```

单线程的 `HashMap` 可以用「先 containsKey 再 get」来区分，
但并发容器里这两个调用之间存在窗口，区分不了。
Doug Lea 的选择是从设计上禁止 null，让所有读结果无歧义。

## 追问链

### Q1: 为什么 Java 8 要放弃分段锁？

分段锁有两个绕不过去的硬伤：

1. **并发度被段数封顶**。段数在构造时确定，之后不能改。
   一台 32 核机器上只有 16 个段，就有 16 条线程无谓地排队。
2. **统计需要全局视角**。`size()` 要遍历所有段并加锁，
   在段数多时开销大且容易成为热点。

Java 8 的方案把锁粒度从「段」降到「桶」，好处是：

- **并发度随容量自然增长**：元素多、桶多，能并行的线程就多，
  不再需要一个调参参数
- **空桶写入完全无锁**：CAS 一条指令，不产生任何锁竞争
- **统计不需要全局锁**：`baseCount` + `CounterCell` 分散计数

代价是**单桶内的操作仍需加锁**，如果哈希分布极差（大量 key 落在一个桶），
并发度会退化。但这属于数据分布问题，1.7 也同样受影响。

#### Q1.1: 那 1.8 里 synchronized 会不会比 ReentrantLock 慢？

不会，反而是更优选择。原因有两个：

1. **JDK 6 之后 synchronized 有锁升级优化**。无竞争时走偏向锁，
   加解锁只是一次 CAS 甚至更少；`ReentrantLock` 即使无竞争也要
   走一次 AQS 的 CAS + 链表操作。在「桶锁竞争本来就低」的场景里，
   `synchronized` 的开销更小。
2. **省下每个 Segment 一个锁对象的内存**。1.7 有 16 个 Segment，
   每个都是 `ReentrantLock` 实例；1.8 直接用头节点当锁，
   零额外对象。

还有一个实现层面的理由：`synchronized` 的锁信息存在对象头 Mark Word 里，
头节点本来就要被访问，锁信息**搭便车**不需要额外内存。而
`ReentrantLock` 需要维护 state、owner、等待队列。

##### Q1.1.1: 那锁头节点时如果头节点被删了怎么办？

所以有**双重检查**：

```java
for (Node<K,V>[] tab = table;;) {
    Node<K,V> f; int n, i, fh;
    // ...定位到桶 i，f 是头节点
    else if ((fh = f.hash) == MOVED)
        tab = helpTransfer(tab, f);     // 正在扩容，去协助
    else {
        V oldVal = null;
        synchronized (f) {
            if (tabAt(tab, i) == f) {   // ★ 双重检查：头节点还是刚才那个
                // 此时才认为桶结构稳定，可以安全插入
            }
        }
    }
}
```

`tabAt(tab, i) == f` 确认「加锁前后头节点没被换掉」。如果不等，
说明期间有其他线程改了桶头（比如迁移完成或树化），
就放弃本次操作，回到外层 `for` 循环重新定位。

这正是「锁对象必须是对外可见且稳定的哨兵」这个通用原则的体现：
锁和要保护的数据之间要有确定的绑定关系，不能锁一个随时会被替换的对象。

### Q2: 扩容期间读写分别怎么处理？

**读请求完全不阻塞**：

```java
else if ((eh = e.hash) == MOVED)
    // 遇到 ForwardingNode，直接去新表继续找
    e = ((ForwardingNode<K,V>)e).find(h, key);
```

因为 ForwardingNode 持有新表引用，读操作可以**跨表完成查找**。
这是 1.8 读写不互斥的关键——读路径上没有锁。

**写请求两种走向**：

- 落在**未迁移**的桶：正常 CAS 或锁桶写入，不受影响
- 落在**已迁移**的桶：发现是 ForwardingNode，调用 `helpTransfer()`
  先协助扩容，完成后再回到外层循环重新定位

注意 `helpTransfer` 之后是 `continue`/外层循环重试，
**不会丢失本次写入**——这是必须确认的一点，否则并发扩容会丢数据。

## 常见坑

- **说「ConcurrentHashMap 用分段锁」而不说版本** —— 这是 1.7 的实现，
  1.8 已改为 CAS + synchronized 锁桶头
- **说 1.8 完全不用锁** —— 空桶写入是 CAS 无锁，但桶非空时仍然
  要 `synchronized` 锁头节点
- **说 `size()` 是精确值** —— 并发下是近似值，
  只有无并发更新时精确
- **认为读操作需要加锁** —— 读路径靠 `volatile` 读加
  ForwardingNode 跳转，全程不加锁
- **说「扩容是单线程做的」** —— 1.8 支持多线程通过
  `helpTransfer` 协助迁移
- **把「不允许 null」解释成实现偷懒** —— 根因是并发下
  `containsKey` + `get` 之间存在窗口，无法区分「不存在」和「值为 null」
- **忘记 `sizeCtl` 是多语义字段** —— 0、正数、-1、负值各代表不同状态，
  面试里被追问「怎么判断正在扩容」时答不上来会显得没看过源码

## 加分点

- 能讲清 **`sizeCtl` 的多语义设计**，并说出判断扩容的
  `resizeStamp` 高位戳记方案——用一次 CAS 表达多个状态，
  与线程池的 `ctl` 是同一手法（把状态打包进一个原子变量）
- 知道 **`ForwardingNode` 的 `hash = MOVED = -1`** 这个约定。
  用负数 hash 做哨兵很巧妙：正常节点的 hash 恒为非负，
  所以一个 `if (fh >= 0)` 就能区分真实节点与特殊节点
  （`MOVED = -1` 迁移中、`TREEBIN = -2` 树化、`RESERVED = -3` 占位）
- 提到 `CounterCell` 与 `LongAdder` 同源，都是「分散热点减少竞争」，
  并能对比 `AtomicLong` 单点 CAS 在超高并发下的劣势
- 能把「锁粒度」这条主线串起来：Hashtable 全表锁 →
  1.7 分段锁 → 1.8 桶级锁 → 无锁（CAS）。每一次演进都在
  缩小锁的范围，同时提高并发度上限
- 知道 1.8 的 `computeIfAbsent` 等复合操作是**原子**的，
  这是 `Collections.synchronizedMap` 给不了的——
  后者的「检查再写入」需要调用方自己加锁
- 提到**哈希分布极差时桶级锁会退化**，与 `HashMap` 树化的动机相通，
  可以引到防哈希碰撞攻击

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 5 | 引入 `ConcurrentHashMap`，Segment 分段锁实现 |
| Java 7 | 优化 Segment 内的定位；仍为分段锁，并发度等于段数 |
| Java 8 | **改为 CAS + synchronized 锁桶头**；引入红黑树；支持多线程协助扩容；`baseCount` + `CounterCell` 分散计数；`computeIfAbsent` 等复合操作保证原子 |
| Java 8 | `resizeStamp` + `ForwardingNode` 让读操作在扩容期间不阻塞 |
| Java 9+ | `mappingCount()` 推荐替代 `size()`（返回 long，超大集合不溢出 int） |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: Java 8 的 ConcurrentHashMap 在向一个空桶写入时采用什么方式？
    options:
      A: CAS 无锁写入
      B: synchronized 锁住整个 table
      C: 加 Segment 分段锁
      D: 直接写入，不做任何同步
    answer: A
    analysis: 空桶写入用 casTabAt 做一次 CAS；桶非空时才 synchronized 锁住该桶的头节点。CAS 失败说明有其他线程抢先修改了同一个桶，外层循环会重新定位。

  - type: MULTI
    stem: 关于 Java 8 ConcurrentHashMap 的扩容，下列说法正确的有？
    options:
      A: 支持多个线程协助迁移
      B: 迁移中的桶会被放置 ForwardingNode
      C: 扩容期间读请求会被阻塞直到迁移完成
      D: 遇到 ForwardingNode 的写线程会调用 helpTransfer 加入协助
    answer: ABD
    analysis: C 错误。读请求沿 ForwardingNode 持有的新表引用继续查找，全程不加锁，因此扩容期间读不阻塞——这正是 1.8 相对「扩容加全局锁」的关键优势。

  - type: JUDGE
    stem: ConcurrentHashMap 的 size() 方法在并发环境下返回的一定是精确值。
    answer: F
    analysis: 1.8 用 baseCount 加 CounterCell 数组分散计数以避免单点竞争，sumCount() 求和期间其他线程仍在更新，所以并发下返回近似值。Javadoc 明确将其描述为估计值。

  - type: CHOICE
    stem: 为什么 ConcurrentHashMap 要禁止 null key 和 null value？
    options:
      A: 实现难度大所以省略了
      B: 并发下无法区分「key 不存在」与「key 存在但值为 null」
      C: 为了节省内存
      D: 历史遗留，后续版本已支持
    answer: B
    analysis: 单线程的 HashMap 可以靠 containsKey 再 get 无歧义地区分，但并发容器里两个调用间存在窗口，其他线程可能已删除该 key，从而无法判断 get 返回 null 的原因。Doug Lea 选择从设计上禁止 null 以消除二义性。
    difficulty: 3
````
