---
slug: hashmap-resize
title: HashMap 什么时候扩容？扩容代价有多大？负载因子怎么调？
module: java-basics
tags: [HashMap, 扩容, 负载因子]
difficulty: 2
frequency: 3
related:
  - slug: hashmap-internals
    type: PREREQUISITE
  - slug: hashmap-thread-unsafe
    type: DEEPEN
---

## 电梯版回答

扩容由元素个数超过阈值触发，阈值等于容量乘负载因子，默认 16 × 0.75 = 12。扩容时容量翻倍，元素按 hash 在旧容量最高位上是 0 还是 1 分成两条链表，分别留在原下标和搬到下标加旧容量的位置，全程不需要重新计算 hash。代价是要重建整个桶数组并搬迁所有元素，所以能在构造时就估出容量就不要反复扩容。负载因子调高省内存但冲突变多、查询变慢，调低则相反，0.75 是官方给的折中值。

## 展开讲解

### 触发条件与扩容过程

```java
if (++size > threshold)
    resize();
```

`threshold = capacity * loadFactor`，初始 `16 * 0.75 = 12`。
注意判断用的是 `>` 而不是 `>=`：第 12 个元素插入后 size 等于 12，
不触发；第 13 个才触发。

`resize()` 做三件事：

```java
final Node<K,V>[] resize() {
    Node<K,V>[] oldTab = table;
    int oldCap = (oldTab == null) ? 0 : oldTab.length;
    int oldThr = threshold;
    int newCap, newThr = 0;

    if (oldCap > 0) {
        if (oldCap >= MAXIMUM_CAPACITY) {          // 已达 1<<30，不再扩容
            threshold = Integer.MAX_VALUE;
            return oldTab;
        }
        // 容量翻倍；只有旧容量够大时才同步翻倍阈值
        else if ((newCap = oldCap << 1) < MAXIMUM_CAPACITY
                 && oldCap >= DEFAULT_INITIAL_CAPACITY)
            newThr = oldThr << 1;
    }
    else if (oldThr > 0)        // 构造时给了 initialCapacity，此时才真正建表
        newCap = oldThr;
    else {                      // 全默认构造
        newCap = DEFAULT_INITIAL_CAPACITY;
        newThr = (int)(DEFAULT_LOAD_FACTOR * DEFAULT_INITIAL_CAPACITY);
    }
    if (newThr == 0)            // 用 newCap * loadFactor 补算阈值
        newThr = ...;

    threshold = newThr;
    table = newTab;
    // 逐桶搬迁
    ...
}
```

### 搬迁为什么不需要重新计算 hash

容量从 n 变成 2n 后，下标计算从 `hash & (n-1)` 变成 `hash & (2n-1)`。
两个掩码的差别**只在第 log₂(n) 位上**——也就是旧容量那一位：

```
n = 16  →  n-1 = 0000 1111
2n = 32 → 2n-1 = 0001 1111
                  ↑ 多出来的就是这一位（= oldCap）

hash =  5 → 5 & 16 = 0  → 新下标仍是 5
hash = 21 → 21 & 16 = 16 → 新下标是 5 + 16 = 21
```

所以只需看 `hash & oldCap`：

- 为 0 → 留在原下标 `j`
- 为 1 → 搬到 `j + oldCap`

实现上用 `loHead/loTail` 和 `hiHead/hiTail` 两条链表把原桶一分为二：

```java
Node<K,V> loHead = null, loTail = null;
Node<K,V> hiHead = null, hiTail = null;
do {
    next = e.next;
    if ((e.hash & oldCap) == 0) {
        if (loTail == null) loHead = e; else loTail.next = e;
        loTail = e;
    } else {
        if (hiTail == null) hiHead = e; else hiTail.next = e;
        hiTail = e;
    }
} while ((e = next) != null);

if (loTail != null) { loTail.next = null; newTab[j] = loHead; }
if (hiTail != null) { hiTail.next = null; newTab[j + oldCap] = hiHead; }
```

对比 Java 7 的 `indexFor(hash, newCapacity)` 逐个重算下标，Java 8 少了
一次哈希运算，且搬迁后链表相对顺序不变。

### 扩容的代价

- **时间**：O(n)。要遍历全部元素并重建桶数组。一个装了 100 万元素的
  HashMap，最后一次扩容要搬 100 万个节点。
- **内存**：扩容瞬间**新旧两个数组同时存在**，峰值内存约为平时的 1.5 倍。
  这是大 HashMap 触发 OOM 的常见时刻。
- **GC 压力**：新数组是连续的大对象，会直接进入老年代（大对象分配），
  容易触发 Full GC。

**结论：能预估容量就在构造时指定**。

```java
// 要放 1000 个元素，别写 new HashMap<>(1000)
Map<String, Order> map = new HashMap<>(1000 / 0.75f + 1);   // ≈ 1335
```

因为构造参数是**容量**不是元素数，直接传 1000 会得到容量 1024，
阈值 768，放第 769 个元素时就扩容了。Guava 的
`Maps.newHashMapWithExpectedSize(n)` 就是替你算了这笔账。

### 负载因子的取舍

| 负载因子 | 冲突概率 | 内存 | 扩容次数 | 适用 |
|---|---|---|---|---|
| 0.5 | 低 | 浪费约一半桶 | 多 | 查询极敏感、元素少 |
| 0.75 | 折中 | — | — | 默认，绝大多数场景 |
| 1.0 | 高 | 最省 | 少 | 内存紧张且能接受长链表 |

0.75 的来源是**在泊松分布假设下，让链表长度超过 8 的概率足够低**。
官方注释给出的数据：在 0.75 的负载因子下，一个桶里元素数达到 8 的
概率约为 `0.00000006`，这正是树化阈值取 8 的依据。

### 树化与退树化的容量门槛

```java
final void treeifyBin(Node<K,V>[] tab, int hash) {
    int n, index;
    if (tab == null || (n = tab.length) < MIN_TREEIFY_CAPACITY)
        resize();                 // 数组太小，先扩容而不是树化
    else if ((e = tab[index]) != null) {
        // 真正转红黑树
    }
}
```

`MIN_TREEIFY_CAPACITY = 64`。数组长度不足 64 时，扩容比树化更划算——
因为扩容后元素被摊到更多桶里，链表自然就短了，没必要上红黑树。

退树化发生在 `split()`（扩容时处理 TreeNode），
当树中节点数小于等于 `UNTREEIFY_THRESHOLD = 6` 时退回链表：

```java
if (lc <= UNTREEIFY_THRESHOLD)
    tab[index] = loHead.untreeify(map);
```

阈值取 6 而不是 8，是为了**在 7 这个区间留出缓冲**，
避免元素在 7、8 之间来回增删导致频繁树化/退树化。

## 追问链

### Q1: 为什么扩容是 2 倍而不是 1.5 倍或固定增量？

因为**只有容量是 2 的幂，`(n-1) & hash` 才等价于 `hash % n`**。
1.5 倍会得到 24 这样的非 2 的幂，掩码不再是低位全 1，
散列立刻变得极不均匀（比如 n=24, n-1=23=10111，最低位恒为 1 之外的
模式会让一半下标用不到）。

固定增量（比如每次加 16）则会让下标分布随容量增长而错乱，
且需要重新计算每个元素的 hash。

而取 2 倍还有一个额外好处：迁移时只需判断 `hash & oldCap` 一位，
不用重新散列。这是"容量必须是 2 的幂"这条约束带来的最大红利。

#### Q1.1: 那 `new HashMap<>(3)` 会怎样？

构造时 `tableSizeFor(3)` 向上取到最近的 2 的幂，得到 4。
第一次 put 时 `resize()` 用这个值建表，容量为 4，阈值
`4 * 0.75 = 3`。

所以 `new HashMap<>(3)` 放 3 个元素不会扩容，第 4 个才扩容。
注意这里的实现细节：**容量调整发生在第一次 put 时的 resize()，
不在构造方法里**。构造方法只是把 `tableSizeFor` 的结果暂存进
`threshold` 字段，此时 `table` 还是 null。

##### Q1.1.1: 那如果元素数刚好在阈值边界，会提前扩容吗？

不会。判断是 `++size > threshold`：

```
容量 16，阈值 12
插入第 12 个 → size = 12，12 > 12 为假，不扩容
插入第 13 个 → size = 13，13 > 12 为真，扩容到 32，新阈值 24
```

所以「装了 12 个元素还没扩容」是正常状态。这个边界在算容量时
很容易差一：

```java
// 想放 12 个元素，容量 16 够不够？
// 够。阈值 12，能装 12 个不扩容。
Map<String, Object> ok = new HashMap<>(16);

// 想放 13 个呢？
// 不够，会扩容一次。应该直接给 32。
```

### Q2: 扩容和树化的关系是什么？

两者都在「桶内元素变多」时触发，但**判据完全不同**，容易混淆：

| | 触发条件 | 判据 |
|---|---|---|
| 扩容 | 全局元素数超过阈值 | `size > threshold` |
| 树化 | 单个桶的链表长度达到 8 | `binCount >= TREEIFY_THRESHOLD - 1` |

一个桶链表很长但全局 size 很小（哈希分布极差）时，会触发树化
（若数组长度够）而不会扩容。反过来，元素分散在各桶、每个桶都很短时，
只会扩容不会树化。

还有个交叉条件：**桶链表到 8 但数组长度不足 64 时，走的是扩容而不是树化**。
所以严格说，「链表长度到 8 就转红黑树」这个说法不成立。

#### Q2.1: 哈希分布极差会有什么后果？

如果所有 key 都落到同一个桶（比如刻意构造的哈希碰撞攻击），
那么：

1. 第一次：链表到 8，数组长度 16 < 64，触发扩容
2. 扩到 64 后如果还在同一个桶：树化，查询复杂度从 O(n) 降到 O(log n)
3. **但所有元素仍在同一个桶里**，红黑树有 100 万个节点

第 3 步意味着单桶的操作仍是 O(log n)，n 是全局元素数。
比起链表的 O(n) 好得多，但仍远差于正常情况的 O(1)。

这就是为什么「防哈希碰撞攻击」是红黑树的现实动机之一，
也是 Java 8 的扰动函数（把高 16 位异或到低位）存在的意义：
它让高位不同的 hashCode 也能散到不同桶，抬高了构造碰撞的门槛。

顺带一提，`String.hashCode()` 是公开算法，攻击者可以精确构造出
大量哈希相同的字符串。所以对外暴露的接口如果直接拿用户输入当
Map 的 key，且 Map 规模可控，理论上就有被攻击的空间。

## 常见坑

- **把构造函数参数当成元素数** —— `new HashMap<>(1000)` 的容量是 1024、
  阈值 768，放第 769 个元素时会扩容。要按 `n / 0.75 + 1` 算
- **说「元素数达到阈值就扩容」时把边界说成 `>=`** —— 是 `> threshold`，
  容量 16 时第 12 个元素不会触发扩容
- **说「扩容需要重新计算 hash」** —— Java 8 复用节点里缓存的 hash 字段，
  只判断 `hash & oldCap` 一位
- **认为扩容只在元素多时发生** —— 桶链表到 8 但数组不足 64 时，
  也会通过 `treeifyBin` 触发一次扩容
- **把树化阈值和退树化阈值都说成 8** —— 退树化是 6，中间留 7 做缓冲
- **认为负载因子越小越好** —— 小负载因子让桶数组利用率低、
  扩容更频繁，空间和时间双重浪费
- **忽略扩容瞬间的内存峰值** —— 新旧数组同时存在，
  大 Map 的扩容时刻是 OOM 的高发时机

## 加分点

- 能说出 **`MIN_TREEIFY_CAPACITY` 的存在意义**：数组小时扩容比树化划算，
  因为扩容后元素被摊开，链表自然变短
- 知道退树化阈值 6 与树化阈值 8 之间的**缓冲区间**设计意图——
  避免在临界值附近反复转换，这与「避免抖动」是同一类工程思路
- 提到 Guava 的 `Maps.newHashMapWithExpectedSize` / `Maps.capacity(n)`
  就是替你算了 `n / 0.75 + 1`，可以直接用而不必手算
- 能把「容量必须是 2 的幂」这一个约束串起三件事：
  位运算取模、扰动函数只做高低位异或、扩容只判断一位
- 知道**扩容时的内存翻倍风险**，并给出应对：预估容量、
  或改用分段结构（如 `ConcurrentHashMap` 分段迁移、
  Redis 的渐进式 rehash 思路）
- 提到 Redis 的 `dict` 用**渐进式 rehash**（分批搬迁，
  期间两个哈希表并存、查询走两张表）而不是一次性扩容，
  可以作为「如何避免大结构扩容停顿」的对比案例

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 7 | 扩容用 `indexFor` 逐个重算下标；头插法可能导致并发成环 |
| Java 8 | `hash & oldCap` 一位判断，无需重算；尾插保持顺序；引入红黑树与 `MIN_TREEIFY_CAPACITY` |
| Java 8 | 容量调整延迟到首次 put 的 `resize()`，构造方法只暂存 `threshold` |
| Java 19+ | `HashMap` 新增 `newHashMap(int numMappings)` 静态工厂（配合 `SequencedMap`），直接按**元素个数**语义构造，不必再手算负载因子 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 默认配置下（容量 16、负载因子 0.75），插入第几个元素时会触发第一次扩容？
    options:
      A: 第 12 个
      B: 第 13 个
      C: 第 16 个
      D: 第 17 个
    answer: B
    analysis: 阈值 = 16 × 0.75 = 12，判断是 ++size > threshold。插入第 12 个后 size = 12，不大于 12；插入第 13 个后 size = 13 > 12，触发扩容到 32。

  - type: MULTI
    stem: 关于 HashMap 扩容时的元素迁移，下列说法正确的有？
    options:
      A: 不需要重新计算每个元素的 hashCode
      B: 元素要么留在原下标 j，要么搬到 j + oldCap
      C: 判断依据是 hash & oldCap 是 0 还是 1
      D: 扩容时会把所有链表都转成红黑树
    answer: ABC
    analysis: D 错误。扩容只做迁移，桶内节点类型不变；TreeNode 会走 split() 拆成两棵树，若某棵节点数不大于 6 则退化成链表。

  - type: JUDGE
    stem: 想在 HashMap 中放 1000 个元素且不发生扩容，构造时传 new HashMap<>(1000) 即可。
    answer: F
    analysis: 构造参数是容量而非元素数。传 1000 会向上取到 1024，阈值 1024 × 0.75 = 768，放第 769 个元素时就会扩容。应传 1000 / 0.75 + 1，约为 1335。

  - type: CHOICE
    stem: 某个桶的链表长度已达到 8，但当前数组长度是 32，接下来会发生什么？
    options:
      A: 立即把这个桶转成红黑树
      B: 触发一次扩容到 64，不树化
      C: 抛异常
      D: 什么也不做
    answer: B
    analysis: treeifyBin 会先判断 table.length < MIN_TREEIFY_CAPACITY（64）。32 小于 64，所以只执行 resize() 扩容。数组小时扩容能把元素摊开、链表自然变短，比树化更划算。
````
