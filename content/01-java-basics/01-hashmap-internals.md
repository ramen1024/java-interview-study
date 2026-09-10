---
slug: hashmap-internals
title: HashMap 的底层结构是怎样的？put 一个元素经历了什么？
module: java-basics
tags: [HashMap, 集合, 红黑树, 哈希]
difficulty: 2
frequency: 3
related:
  - slug: hashmap-resize
    type: PREREQUISITE
  - slug: hashmap-thread-unsafe
    type: DEEPEN
  - slug: concurrenthashmap
    type: CONTRAST
---

## 电梯版回答

Java 8 的 HashMap 是「数组 + 链表 + 红黑树」。数组是哈希桶，每个桶挂一条链表，链表长度达到 8 且数组长度达到 64 时转成红黑树，退到 6 再退回链表。put 时先用扰动后的 hash 定位桶下标，桶空就直接放；否则比较 hash 和 key，相同就覆盖，不同就插到链表尾部；插入后如果元素总数超过阈值就扩容。数组容量默认 16，负载因子 0.75，阈值是两者的乘积，且数组是第一次 put 时才初始化的。

## 展开讲解

### 核心字段

```java
static final int DEFAULT_INITIAL_CAPACITY = 1 << 4;  // 16
static final int MAXIMUM_CAPACITY = 1 << 30;
static final float DEFAULT_LOAD_FACTOR = 0.75f;
static final int TREEIFY_THRESHOLD = 8;      // 链表转红黑树的长度阈值
static final int UNTREEIFY_THRESHOLD = 6;    // 红黑树退回链表的长度阈值
static final int MIN_TREEIFY_CAPACITY = 64;  // 树化要求的最小数组长度

transient Node<K, V>[] table;   // 哈希桶数组
transient int size;             // 实际元素个数
int threshold;                  // 扩容阈值 = capacity * loadFactor
```

节点类型有三种：`Node`（普通链表节点）、`TreeNode`（红黑树节点）、以及
`LinkedHashMap` 用的 `Entry`。`TreeNode` 继承自 `Node`，所以树化后
`table` 的静态类型不变。

### 定位桶下标：扰动函数 + 位运算

```java
static final int hash(Object key) {
    int h;
    return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
}

// putVal 里定位下标
if ((p = tab[i = (n - 1) & hash]) == null) {
    tab[i] = newNode(hash, key, value, null);
}
```

两步都值得说清楚：

1. **扰动函数**把 hashCode 的高 16 位异或到低 16 位。因为 `(n - 1) & hash`
   在 n 较小时只用到 hash 的低位，不扰动的话高位永远参与不了散列，
   高位不同的 key 会大量撞到同一个桶。
2. **`(n - 1) & hash` 等价于 `hash % n`**，成立的前提是 n 为 2 的幂——
   此时 `n - 1` 的低位全是 1，与运算相当于按位取模。位运算比取模快得多。

`key` 为 `null` 时 hash 取 0，因此放在 `table[0]`，这也是 HashMap
允许 null key 的实现方式（只能有一个）。

### put 的完整流程

```java
final V putVal(int hash, K key, V value, boolean onlyIfAbsent, boolean evict) {
    Node<K, V>[] tab; Node<K, V> p; int n, i;
    if ((tab = table) == null || (n = tab.length) == 0)
        n = (tab = resize()).length;              // ① 延迟初始化
    if ((p = tab[i = (n - 1) & hash]) == null)
        tab[i] = newNode(hash, key, value, null);  // ② 桶为空，直接放
    else {
        Node<K, V> e; K k;
        if (p.hash == hash && ((k = p.key) == key || (key != null && key.equals(k))))
            e = p;                                 // ③ 桶首节点就是同一个 key
        else if (p instanceof TreeNode)
            e = ((TreeNode<K, V>) p).putTreeVal(this, tab, hash, key, value);  // ④ 走树
        else {
            for (int binCount = 0; ; ++binCount) {
                if ((e = p.next) == null) {
                    p.next = newNode(hash, key, value, null);  // ⑤ 尾插
                    if (binCount >= TREEIFY_THRESHOLD - 1)
                        treeifyBin(tab, hash);
                    break;
                }
                if (e.hash == hash && ((k = e.key) == key || (key != null && key.equals(k))))
                    break;
                p = e;
            }
        }
        if (e != null) {                            // ⑥ key 已存在，覆盖 value
            V oldValue = e.value;
            if (!onlyIfAbsent || oldValue == null)
                e.value = value;
            afterNodeAccess(e);
            return oldValue;
        }
    }
    ++modCount;
    if (++size > threshold)                         // ⑦ 超过阈值就扩容
        resize();
    afterNodeInsertion(evict);
    return null;
}
```

注意几个容易被忽略的细节：

- 判断 key 相等是 `hash` 相同 **且**（引用相同 **或** `equals` 为真）。
  先用 `==` 是为了省一次 `equals` 调用，同一个对象直接命中。
- `treeifyBin` 内部会先检查 `table.length < MIN_TREEIFY_CAPACITY`，
  成立的话只做 `resize()` 而**不树化**。所以「链表到 8 就变红黑树」
  这个说法不完整。
- `size` 与 `threshold` 都是普通 `int`，`++size` 不是原子操作，
  这是并发场景下 `size` 失真的直接原因。

### 三种节点结构

```
桶为空         → Node（单个节点）
链表           → Node 串成单链表，插入在尾部
链表 ≥ 8 且数组 ≥ 64 → TreeNode 红黑树
```

## 追问链

### Q1: 为什么默认容量是 16？

16 是空间与扩容代价的折中。阈值 = 16 × 0.75 = 12，容量再小会频繁扩容，
每次扩容都要重建整个 table；容量再大则低负载时浪费大量桶数组空间。
同时 16 是 2 的幂，满足 `(n - 1) & hash` 等价取模的前提。

#### Q1.1: 为什么容量必须是 2 的幂？

因为只有 n 是 2 的幂时，`n - 1` 的二进制低位才全是 1。此时
`hash & (n - 1)` 与 `hash % n` 结果完全一致。如果 n 不是 2 的幂，
比如 15，那么 `n - 1 = 14 = 1110`，二进制最低位恒为 0，
所有 hash 为奇数的 key 都只能落在偶数下标上，一半的桶永远用不到。

##### Q1.1.1: 那如果构造时传了非 2 的幂次会怎样？

`tableSizeFor()` 会向上取到最近的 2 的幂：

```java
static final int tableSizeFor(int cap) {
    int n = cap - 1;
    n |= n >>> 1; n |= n >>> 2; n |= n >>> 4;
    n |= n >>> 8; n |= n >>> 16;
    return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
}
```

传 17 得到 32，传 16 得到 16。这里有个高频追问点：**这个容量调整发生在
第一次 put 时的 `resize()` 里，不是构造方法里**。构造方法只是把
`tableSizeFor` 的结果暂存进 `threshold` 字段：

```java
public HashMap(int initialCapacity, float loadFactor) {
    this.loadFactor = loadFactor;
    this.threshold = tableSizeFor(initialCapacity);  // 暂存在 threshold
}
```

此时 `table` 仍是 null。第一次 put 时 `resize()` 看到
`oldCap == 0 && oldThr > 0`，才用 `newCap = oldThr` 真正创建数组。
这也是为什么「HashMap 构造后就分配了 16 个桶」是错的。

### Q2: 扩容时元素怎么重新分配？

Java 8 不需要重新计算 hash。容量翻倍后，原来的下标 j 在新表中的位置
只可能是 j 或 j + oldCap，取决于 `hash & oldCap` 是 0 还是 1：

```
oldCap = 16 = 0001 0000
hash = 5  → 5  & 16 = 0  → 留在 j
hash = 21 → 21 & 16 = 16 → 移到 j + 16
```

因为容量从 n 变成 2n 后，`(2n - 1) & hash` 相比 `(n - 1) & hash`
只多考虑了一位，也就是 oldCap 对应的那一位。为 0 则下标不变，
为 1 则正好加上 oldCap。JDK 用 `loHead/loTail` 和 `hiHead/hiTail`
两条链表把原桶一分为二，尾插保持相对顺序：

```java
if ((e.hash & oldCap) == 0) {
    if (loTail == null) loHead = e; else loTail.next = e;
    loTail = e;
} else {
    if (hiTail == null) hiHead = e; else hiTail.next = e;
    hiTail = e;
}
// ...
if (loTail != null) { loTail.next = null; newTab[j] = loHead; }
if (hiTail != null) { hiTail.next = null; newTab[j + oldCap] = hiHead; }
```

#### Q2.1: Java 7 的扩容有什么问题？

Java 7 用头插法（`newTable[i] = e` 之后 `e = next`），并且需要
`indexFor` 重新计算下标。单线程下没问题，但并发扩容时两条线程
各自头插同一个链表，链表会被反转并可能首尾相接形成**环形链表**。
之后任何一次 `get` 落到这个桶上就会在环里死循环，表现为
**CPU 100% 但线程栈停在 HashMap.getEntry**。

Java 8 改成尾插，扩容后元素的相对顺序不变，从根上消除了成环的可能。

## 常见坑

- **只说「数组 + 链表」就结束** —— 漏掉 Java 8 的红黑树，以及
  `TREEIFY_THRESHOLD = 8` 之外还有 `MIN_TREEIFY_CAPACITY = 64` 的前置条件
- **说「链表长度到 8 就转红黑树」** —— 数组长度不足 64 时 `treeifyBin`
  只会做一次 `resize()`，不树化
- **说「负载因子越大越好」或「越小越好」** —— 负载因子大省空间但冲突多、
  查询慢；小则冲突少但频繁扩容、空间浪费。0.75 是官方给的折中值
- **说「HashMap 构造时就分配了 16 个桶」** —— Java 8 是延迟初始化，
  首次 put 才创建数组
- **说「下标是 `hashCode() % n`」** —— 实际是扰动函数 + `(n - 1) & hash`，
  且前提是 n 为 2 的幂
- **说「扩容要重新计算所有元素的 hash」** —— Java 8 不需要，
  只判断 `hash & oldCap` 这一位

## 加分点

- 扰动函数存在的意义是让高位也参与散列。举反例最有说服力：
  一批 key 的 hashCode 恰好只有高位不同（比如某些对象的 hashCode
  是地址右移的结果），不扰动就会全部落进同一个桶
- 红黑树的引入除了优化长链表的查询，还有一个现实动机是**防哈希碰撞攻击**：
  攻击者可以构造大量 hashCode 相同的 key 让链表退化成 O(n)，
  服务端 CPU 被打满。这也是为什么阈值卡在 8——按泊松分布，
  正常情况下一个桶里到 8 个元素的概率约为 6×10⁻⁸
- 能主动提到 `tableSizeFor` 的位运算技巧（连续无符号右移 + 或运算，
  把最高位 1 向右「铺满」）通常是加分项
- 知道 Java 8 尾插法带来的额外好处：扩容后相对顺序不变，
  这使得并发扩容即使丢数据也不会成环，把「死循环」降级为「可能丢数据」

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 7 | 数组 + 链表；插入用**头插法**；扩容需要 `indexFor` 重新计算下标；并发扩容可能形成环形链表导致 CPU 100% |
| Java 8 | 引入红黑树（长度 ≥ 8 且数组 ≥ 64 树化，≤ 6 退化）；改为**尾插法**；扩容用 `hash & oldCap` 分流，不需要重新计算 hash；仍非线程安全，并发 put 可能丢数据 |
| Java 8（构造） | `new HashMap<>(n)` 的容量调整延迟到首次 put，构造方法只把结果暂存进 `threshold` |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: HashMap 的默认初始容量和默认负载因子分别是多少？
    options:
      A: 16 和 0.75
      B: 8 和 0.75
      C: 16 和 1.0
      D: 32 和 0.5
    answer: A
    analysis: DEFAULT_INITIAL_CAPACITY = 1 << 4 即 16，DEFAULT_LOAD_FACTOR = 0.75f。阈值 = 16 × 0.75 = 12，第 13 个元素插入时触发扩容。

  - type: JUDGE
    stem: 在 Java 8 中，只要链表长度达到 8 就一定会转换成红黑树。
    answer: F
    analysis: 还要满足数组长度不小于 MIN_TREEIFY_CAPACITY（64）。否则 treeifyBin 只执行 resize() 扩容，不树化。

  - type: MULTI
    stem: 关于 HashMap 的扩容，下列说法正确的有？
    options:
      A: Java 8 扩容时不需要重新计算每个元素的 hash
      B: 扩容后元素要么留在原下标 j，要么移动到 j + oldCap
      C: 扩容后容量变为原来的 2 倍（未达上限时）
      D: 扩容时会重新执行一次 key 的 hashCode()
    answer: ABC
    analysis: D 错误。Java 8 直接复用节点里缓存的 hash 字段，只判断 hash & oldCap 这一位。Java 7 才需要重新 indexFor（但同样不必重算 hashCode）。

  - type: CLOZE
    stem: |
      补全 Java 8 中定位桶下标的代码，以及扩容时把原桶一分为二的判断条件：
      ```java
      static final int hash(Object key) {
          int h;
          return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> {{1}});
      }

      // resize() 中把原链表按是否留在原下标拆成两条
      if ((e.hash & {{2}}) == 0) {
          // 留在原下标 j
      } else {
          // 移动到新下标 j + oldCap
      }
      ```
    blanks:
      - ["16"]
      - ["oldCap", "oldCap（原容量）"]
    analysis: 扰动函数把高 16 位异或到低 16 位，因为 (n-1)&hash 在 n 较小时只用到低位。扩容时只需判断 hash 在 oldCap 那一位上是 0 还是 1，为 0 留在原位，为 1 则下标加上 oldCap。
    difficulty: 3
````
