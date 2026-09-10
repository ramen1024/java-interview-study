---
slug: concurrent-collections
title: 常用的并发容器有哪些？阻塞队列怎么选？
module: concurrency
tags: [并发容器, ConcurrentHashMap, CopyOnWriteArrayList, 阻塞队列]
difficulty: 3
frequency: 2
related:
  - slug: concurrenthashmap
    type: PREREQUISITE
  - slug: aqs-principle
    type: PREREQUISITE
  - slug: why-not-executors
    type: RELATED
  - slug: thread-pool-parameters
    type: RELATED
---

## 电梯版回答

常用的分三类。映射用 ConcurrentHashMap，Java 8 起不再用分段锁，改成空桶 CAS 写入加非空桶 synchronized 锁头节点。列表用 CopyOnWriteArrayList，只适合读多写极少的场景，写时复制整个数组所以写开销 O(n)，读无锁，迭代器是快照、弱一致且不支持 remove，典型用途是监听器列表。队列用 ConcurrentLinkedQueue，CAS 无锁但 size() 是 O(n) 且只是估计值。阻塞队列里 ArrayBlockingQueue 是有界数组、一把锁两个 Condition，LinkedBlockingQueue 默认容量是 Integer.MAX_VALUE 等同无界、是任务堆积 OOM 的常见根源，读写分离两把锁所以吞吐更高，SynchronousQueue 不存元素、直接移交，Executors.newCachedThreadPool 用的就是它。对满队列的语义：add 满了抛异常，offer 满了立即返回 false，put 满了阻塞。

## 展开讲解

### ConcurrentHashMap：从分段锁到锁单个桶

Java 7 是 `Segment[]`，每个 Segment 继承 `ReentrantLock`，并发度由构造参数 `concurrencyLevel` 决定（默认 16），扩容要锁住整个段。Java 8 把 Segment 去掉了：

- 桶为空：`casTabAt(tab, i, null, new Node<>(...))` 用 CAS 直接放头节点，完全不加锁
- 桶非空：`synchronized (f)` 锁住桶的头节点 `f`，再做链表或红黑树插入
- 扩容：`transfer` 支持多线程协同迁移，已迁移的桶放一个 `ForwardingNode` 标记，其他线程碰到它会调 `helpTransfer` 一起搬
- 计数：`baseCount` 加一个 `CounterCell[]` 数组分段累加（思路同 LongAdder），`size()` 是求和得到的估计值

读操作完全无锁：桶元素用 `tabAt`（内部是 `Unsafe.getObjectVolatile`）读，`Node` 的 `val` 和 `next` 是 volatile 字段，靠可见性而不是锁。

```java
// Java 8 ConcurrentHashMap.putVal 的骨架
if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
    if (casTabAt(tab, i, null, new Node<K,V>(hash, key, value)))
        break;                            // 空桶 CAS，无锁
} else {
    synchronized (f) {                    // 只锁这个桶的头节点
        // 链表 / 红黑树插入，或碰到 ForwardingNode 就去协助扩容
    }
}
```

### CopyOnWriteArrayList：写时复制

读操作直接读当前的数组引用，不加锁；写操作先加锁（Java 8 用 `ReentrantLock`，Java 9 起改成在普通 `Object` 监视器上 `synchronized`，源码注释写明是「能用内建监视器就不上 ReentrantLock」），然后复制出一份新数组、在副本上修改、最后替换 `array` 引用。换来的是读零锁、迭代天然安全，代价是每次写都全量复制。

```java
public boolean add(E e) {
    synchronized (lock) {
        Object[] es = getArray();
        int len = es.length;
        es = Arrays.copyOf(es, len + 1);   // 复制整个数组，O(n)
        es[len] = e;
        setArray(es);                      // 替换引用
        return true;
    }
}
```

迭代器是快照（`COWIterator` 内部的 `snapshot` 数组），是**弱一致**的：不 fail-fast，但看不到迭代开始后的增删，也不支持 `remove` / `set` / `add`（抛 `UnsupportedOperationException`）。

### ConcurrentLinkedQueue：CAS 无锁队列

基于 Michael-Scott 队列，用 CAS 维护 `head` / `tail`，没有锁。`offer` / `poll` 是 O(1) 的 CAS 循环；但 `size()` 需要从头遍历到尾，是 **O(n)**，而且并发下这个值只是估计值。`isEmpty()` 是 O(1)，判断有没有元素优先用它。

迭代器同样是弱一致的，不会抛 `ConcurrentModificationException`。

### 阻塞队列对比

| 实现 | 容量 | 数据结构 | 锁 | 要点 |
|---|---|---|---|---|
| `ArrayBlockingQueue` | 有界，必须构造指定 | 数组 | 一把 `ReentrantLock`，`notEmpty` / `notFull` 两个 Condition | 读写互斥，实现简单，支持公平模式 |
| `LinkedBlockingQueue` | 可选有界，**无参默认 `Integer.MAX_VALUE`** | 链表 | `putLock` / `takeLock` 两把锁，各带一个 Condition | 读写可并行，吞吐更高；用一个 `AtomicInteger count` 协调 |
| `SynchronousQueue` | 不存储元素 | 栈（非公平）/ 队列（公平） | 内部自旋 + 阻塞 | 每次 put 必须等到一个 take，直接移交；`Executors.newCachedThreadPool` 用它 |
| `PriorityBlockingQueue` | 无界 | 二叉堆 | `ReentrantLock` + `notEmpty` | 按优先级（`Comparable` / `Comparator`）出队，非 FIFO；入队基本不会阻塞 |
| `DelayQueue` | 无界 | 堆 | `ReentrantLock` + `available` | 元素实现 `Delayed`，到期后才能被 take；用于延迟任务、重试队列 |

### add / offer / put 的区别

| 方法 | 队列满时 | 阻塞 |
|---|---|---|
| `add(e)` | 抛 `IllegalStateException` | 否 |
| `offer(e)` | 返回 `false` | 否 |
| `offer(e, timeout, unit)` | 等待超时后返回 `false` | 限时 |
| `put(e)` | 一直等到有空间 | 是 |

对应的出队侧：`remove()` / `element()` 在空时抛 `NoSuchElementException`，`poll()` 返回 null，`take()` 阻塞。

线程池的 `execute` 内部用的是 `offer`，因为它需要立刻知道入队成功还是失败，好决定去创建非核心线程还是走拒绝策略。如果用的是 `put`，入队会阻塞，`maximumPoolSize` 和拒绝策略就永远没有机会生效。

## 追问链

### Q1: Java 8 的 ConcurrentHashMap 为什么放弃了分段锁？

分段锁有几个硬伤：并发度在构造时就固定成 `concurrencyLevel`（默认 16），最多只能有 16 个线程同时写；扩容要锁住整个段；每个 Segment 都是一把 `ReentrantLock` 对象，内存开销大。Java 8 把锁粒度缩到单个桶，并发度等于桶的数量；而且空桶插入是 CAS、完全无锁，只有发生哈希冲突的桶才需要 synchronized。并发能力从「固定 16」变成了「随容量增长」。

#### Q1.1: 那 Java 8 具体是怎么保证线程安全的？

分四种情况：空桶用 `casTabAt` 原子写入；非空桶用 `synchronized` 锁住桶首节点，再操作链表或红黑树；扩容时用 `ForwardingNode` 标记已迁移的桶，其他线程发现后调 `helpTransfer` 协助搬迁；计数用 `baseCount` 加 `CounterCell[]` 分段累加（类似 LongAdder），`size()` 是把它们求和得到的估计值。

##### Q1.1.1: get 操作要不要加锁？

不需要，`get` 全程无锁：先用 `tabAt` 读桶的头节点，再顺着 `next` 找。可见性由 volatile 保证——`Node` 的 `val` 和 `next` 都是 volatile 字段，`table` 也按 volatile 语义读取。所以即使扩容正在进行也能读到正确的节点（可能从旧表或新表读到，但不会读到写了一半的节点）。这也是 ConcurrentHashMap 读性能接近 HashMap 的原因。

#### Q1.2: 既然要缩小锁粒度，为什么不继续用 ReentrantLock？

因为粒度到桶级后竞争概率已经很低，synchronized 在低竞争下走偏向锁 / 轻量级锁，开销比 ReentrantLock 那条 AQS 排队路径更小，而且由 JVM 内建、不需要额外的锁对象。桶级同步也不需要 ReentrantLock 的高级能力（超时、公平、多个 Condition），给每个桶挂一把锁反而浪费内存。

### Q2: CopyOnWriteArrayList 适合什么场景？

只适合**读多写极少**。所有读直接读当前数组引用、不加锁；所有写先加锁，再复制出新数组、在副本上改、最后替换引用。换来的是读零锁、迭代天然线程安全，代价是每次写都要复制整个数组。典型用途是监听器 / 观察者列表、白名单、配置项这类「迭代远多于修改」的场景。

#### Q2.1: 它的迭代器为什么不会抛 ConcurrentModificationException？

因为迭代器持有创建那一刻的数组快照（`COWIterator` 里的 `Object[] snapshot`）。写操作是替换 `array` 引用，从不动原数组，所以遍历期间那份数组内容不会变。它是**弱一致**的：不 fail-fast，但也看不到迭代开始后的新元素，而且 `remove` / `set` / `add` 都会抛 `UnsupportedOperationException`。

##### Q2.1.1: 为什么说写开销是 O(n)？

每次 `add` / `set` / `remove` 都要 `Arrays.copyOf` 出一个新数组再替换引用，元素越多复制越慢，瞬时还会同时存在两份数组，GC 压力翻倍。所以在循环里逐个 `add` 是典型的 O(n²) 写法；批量场景优先用一次加锁只复制一次的 `addAll`。

### Q3: 阻塞队列该怎么选？

先按「满了怎么办」和「要不要优先级」来定：需要严格有界、容量可控选 `ArrayBlockingQueue`；追求吞吐、能接受链表开销选 `LinkedBlockingQueue`；任务必须立刻交给线程执行、不允许排队选 `SynchronousQueue`；要按优先级出队用 `PriorityBlockingQueue`；要延迟到某个时间点才能取用 `DelayQueue`。线程池的工作队列优先选有界队列，让 `maximumPoolSize` 和拒绝策略真正生效。

#### Q3.1: LinkedBlockingQueue 为什么常被当成「无界队列」？

因为它的无参构造把容量设成了 `Integer.MAX_VALUE`：

```java
public LinkedBlockingQueue() {
    this(Integer.MAX_VALUE);
}
```

技术上有上界，但实际等同无界。`newFixedThreadPool` 和 `newSingleThreadExecutor` 用的正是这个默认构造。任务生产速度长期高于消费速度时，队列会一直涨到 OOM；而且因为 `offer` 永远成功，`maximumPoolSize` 根本没有机会生效。

##### Q3.1.1: ArrayBlockingQueue 和 LinkedBlockingQueue 在锁上有什么差别？

`ArrayBlockingQueue` 只有一把 `ReentrantLock`，配 `notEmpty` 和 `notFull` 两个 Condition，put 和 take 会互相排斥。`LinkedBlockingQueue` 用 `putLock` / `takeLock` 两把锁，各带一个 Condition，入队和出队可以真正并行，所以吞吐更高；代价是多了一个 `AtomicInteger count` 来协调两边，有一些 CAS 开销，内存上也每个元素都要包一个 `Node` 对象。

## 常见坑

- **说 Java 8 的 ConcurrentHashMap 还是分段锁** —— 分段锁是 Java 7 的实现（`Segment` 继承 `ReentrantLock`）；Java 8 已改成空桶 CAS 加非空桶 synchronized 锁首节点
- **说「LinkedBlockingQueue 是无界的，所以随便用」** —— 无参构造容量是 `Integer.MAX_VALUE`，等同无界，任务堆积最终 OOM，而且会让 `maximumPoolSize` 永远不生效。必须显式传容量
- **说 CopyOnWriteArrayList 读也要加锁** —— 读完全无锁，直接读 volatile 语义的数组引用，只有写才加锁（Java 9 起是普通 `Object` 监视器，Java 8 曾是 `ReentrantLock`）
- **说 CopyOnWriteArrayList 的迭代器可以 remove** —— 快照迭代器不支持结构性修改，`remove` / `set` / `add` 都抛 `UnsupportedOperationException`
- **说 ConcurrentLinkedQueue 的 size() 是 O(1)** —— 它要遍历整条队列，是 O(n)，并发下也只是估计值；判空应该用 O(1) 的 `isEmpty()`
- **说 ConcurrentHashMap 的 size() 精确** —— 并发下是 `baseCount` 与 `CounterCell[]` 求和的结果，不保证精确
- **说 add 和 put 是一回事** —— 队列满时 `add` 抛 `IllegalStateException`，`offer` 立即返回 false，`put` 阻塞等待
- **说 ConcurrentHashMap 是全程加锁的** —— 只有发生哈希冲突的桶才 synchronized，读完全无锁

## 加分点

- 能说清 `sizeCtl` 的编码：负数表示正在初始化或扩容（低 16 位是参与迁移的线程数减一），正数表示下次扩容阈值，用一个 volatile int 承载多种状态
- 提到 `CounterCell` 借鉴了 LongAdder 的分段计数思路，配合 `@Contended` 减少伪共享
- 提到 `ForwardingNode` 让扩容可以多线程协作搬迁，而不是扩容时全表停写
- 知道 Java 7 分段锁的并发度是固定的（由 `Segment[]` 长度决定），这是它被淘汰的直接原因
- 需要有序的并发 Map 用 `ConcurrentSkipListMap`（跳表，无锁读、弱一致迭代），哈希容器本身无法排序
- 主动点出所有并发容器的迭代器都是**弱一致**的：不抛 CME、不保证看到最新数据，这是并发容器的统一设计取向
- 提到 `CopyOnWriteArraySet` 就是基于 CopyOnWriteArrayList 实现的，只适合小集合

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 5 | 随 JSR-166 引入 `ConcurrentHashMap`（分段锁 `Segment`）、`CopyOnWriteArrayList`、`ConcurrentLinkedQueue` 和一组 `BlockingQueue` 实现 |
| Java 6 | 增加 `ConcurrentSkipListMap` / `ConcurrentSkipListSet` |
| Java 8 | `ConcurrentHashMap` 去掉 `Segment`，改为空桶 CAS + synchronized 锁单个桶，引入红黑树（与 HashMap 同样的 8 / 64 阈值）、`CounterCell` 计数、`ForwardingNode` 协同扩容；`CopyOnWriteArrayList` 的写锁是 `ReentrantLock` |
| Java 9 | `CopyOnWriteArrayList` 的写锁从 `ReentrantLock` 换成普通 `Object` 监视器 + `synchronized`，减少每个实例的内存占用 |
| Java 21 | 虚拟线程可直接阻塞在 `BlockingQueue` 的 put / take 上而不占载体线程；但在 `synchronized` 块内阻塞仍可能 pin 住载体线程（该问题到 JDK 24 的 JEP 491 才解决） |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: Java 8 的 ConcurrentHashMap 向一个空桶插入节点时，用什么方式保证原子性？
    options:
      A: 对整个 table 加 synchronized
      B: 用 CAS 写入桶的头节点
      C: 用 ReentrantLock 锁住桶
      D: 复制整个数组
    answer: B
    analysis: 空桶走 casTabAt，用 Unsafe 的 CAS 直接写入头节点；只有桶已非空（哈希冲突）时才用 synchronized 锁住桶的首节点。

  - type: JUDGE
    stem: CopyOnWriteArrayList 的迭代器支持在遍历过程中删除元素。
    answer: F
    analysis: 迭代器基于创建时的数组快照（COWIterator），弱一致且只读，remove / set / add 都会抛 UnsupportedOperationException。

  - type: MULTI
    stem: 关于阻塞队列，下列说法正确的有？
    options:
      A: LinkedBlockingQueue 的无参构造容量是 Integer.MAX_VALUE
      B: ArrayBlockingQueue 用一把锁和两个 Condition 实现
      C: SynchronousQueue 不存储元素，put 必须等到一个 take
      D: PriorityBlockingQueue 是无界的，入队通常不会因为容量而阻塞
    answer: ABCD
    analysis: 四项都对。A 是任务堆积 OOM 的常见根源；B 的读写互斥但实现简单；C 是直接移交（handoff）；D 因为无界所以 offer / put 基本不会因容量阻塞，但仍可能因为没有元素而让 take 阻塞。

  - type: CLOZE
    stem: |
      队列已满时三种入队方法的语义不同，补全下面代码：
      ```java
      queue.add(e);                    // 满时抛 IllegalStateException
      boolean ok = queue.{{1}}(e);     // 满时立即返回 false，不阻塞
      queue.{{2}}(e);                  // 满时阻塞，直到有空间
      ```
    blanks:
      - ["offer"]
      - ["put"]
    analysis: add 满了抛 IllegalStateException；offer 立即返回 boolean（也有带超时的重载，超时后返回 false）；put 会一直阻塞到队列有空间。线程池的 execute 内部用 offer，因为它需要立刻知道入队是否失败，好去创建非核心线程或走拒绝策略。
    difficulty: 2
````
