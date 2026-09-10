---
slug: hashmap-thread-unsafe
title: HashMap 为什么线程不安全？并发下会出什么问题？
module: java-basics
tags: [HashMap, 线程安全, 并发, 死循环]
difficulty: 3
frequency: 3
related:
  - slug: hashmap-internals
    type: PREREQUISITE
  - slug: concurrenthashmap
    type: DEEPEN
---

## 电梯版回答

HashMap 从头到尾都没有做任何同步。Java 7 的致命问题是并发扩容时用头插法重建链表，两个线程交替执行会把链表首尾相接成环，之后任何落到这个桶上的 get 都会在环里死循环，表现是 CPU 100% 且线程栈停在 HashMap 相关方法上。Java 8 改成尾插，成环问题消失了，但并发写依然会丢数据：同一个桶同时 put 会互相覆盖，size 自增不是原子操作会失真。所以并发场景要用 ConcurrentHashMap。

## 展开讲解

### Java 7：并发扩容成环

Java 7 的 `transfer` 用头插法迁移节点，核心是这几行：

```java
void transfer(Entry[] newTable, boolean rehash) {
    for (Entry<K, V> e : table) {
        while (null != e) {
            Entry<K, V> next = e.next;
            if (rehash) {
                e.hash = (null == e.key) ? 0 : hash(e.key);
            }
            int i = indexFor(e.hash, newTable.length);
            e.next = newTable[i];      // 头插：新节点指向桶里已有的头
            newTable[i] = e;           // 自己成为新头
            e = next;
        }
    }
}
```

成环的过程需要两条线程交错，关键在于**每条线程持有自己的 `newTable`**，
但共享同一批 `Entry` 对象，而头插会修改 `e.next`：

```
初始：桶里 A → B → null

① 线程 1 执行到 next = e.next 后被挂起（e = A，next = B）
② 线程 2 完整迁移完，新表中变成 B → A → null
   （头插是逆序的，所以顺序反了）
③ 线程 1 恢复：把 A 头插进自己的新表，A.next = newTable[i] = null
   然后 e = next = B
④ 线程 1 继续处理 B：B.next = newTable[i] = A
   于是 A.next 仍指向 B（步骤②留下的）→ B → A → B → A …
```

第 ④ 步是死结：线程 2 已经把 `A.next` 设成了 null，但线程 1 的第 ③ 步
又把它接回 B。结果 `A.next = B` 且 `B.next = A`，形成环。

之后 `get` 一个哈希到该桶但不存在的 key 时，`for` 循环会沿着环一直走下去，
永不返回。这是**线上真实发生过的事故**，症状是几个线程 CPU 打满，
`jstack` 看到的栈顶是 `HashMap.getEntry` / `HashMap.get`。

### Java 8：不成环，但会丢数据

Java 8 改尾插，且扩容后每个元素的相对顺序不变，因此不会有环。
但还有三个并发问题：

```java
// ① 空桶直接赋值，两条线程可能都判断为空
if ((p = tab[i = (n - 1) & hash]) == null)
    tab[i] = newNode(hash, key, value, null);

// ② 链表尾插也非原子：两条线程可能都读到 p.next == null
if ((e = p.next) == null) {
    p.next = newNode(hash, key, value, null);
}

// ③ size 自增不是原子操作
if (++size > threshold)
    resize();
```

- **覆盖丢数据**：① 和 ② 都是「读—判断—写」三步，非原子。两条线程
  同时写同一个桶，后者覆盖前者，前一条数据直接消失。
- **size 失真**：`++size` 对应读、加一、写回三步，并发下会少算，
  导致扩容时机判断错误——可能长期不扩容，链表越挂越长。
- **并发扩容读到中间态**：扩容过程中 `table` 会被换成新数组，
  另一条线程可能拿到旧数组的引用读到已被置 null 的桶（`oldTab[j] = null`），
  于是 `get` 到一个明明刚 put 过的 key 返回 null。

**关键结论**：Java 8 把「死循环」降级成了「丢数据」。
丢数据更难发现，但不再让服务直接挂掉。

### fail-fast 不解决问题

`modCount` 机制只能**发现**一部分并发修改：

```java
final Node<K, V> nextNode() {
    if (modCount != expectedModCount)
        throw new ConcurrentModificationException();
    // ...
}
```

它有两个局限：一是只在迭代器里检查，单次 `put` 完全不设防；
二是它是 best-effort，Javadoc 明确写了「不能保证一定抛出」——
因为你不能指望并发修改恰好在 `modCount` 检查点之间发生。

另外还要注意：`modCount` 检查是**为了快速失败而不是为了线程安全**。
即使抛了 `ConcurrentModificationException`，数据可能已经被写坏了。

### 正确的并发方案

| 方案 | 锁粒度 | 说明 |
|---|---|---|
| `Hashtable` | 整表 | 每个方法都加 `synchronized`，读写互斥，性能差，已不推荐 |
| `Collections.synchronizedMap` | 整表（包装器持有一个 mutex） | 同样全表互斥，且**迭代时要手动 `synchronized`**，否则仍会 CME |
| `ConcurrentHashMap` | 桶级 | 1.7 用 Segment 分段锁，1.8 用 CAS + synchronized 锁桶头节点 |

`ConcurrentHashMap` 的 `size()` 在并发下返回的是**近似值**（1.8 用
`baseCount` + `CounterCell` 数组分散计数，类似 `LongAdder` 的思路），
它只保证最终一致，不保证某一时刻的精确值。这一点经常被追问。

## 追问链

### Q1: Java 7 的环形链表是怎么形成的？

根因是**头插法会修改已有节点的 next 指针，而这个节点同时被另一条线程持有**。
两条线程各自有一份 `newTable`，但共享同一批 `Entry`。线程 1 执行到一半被挂起，
线程 2 完成迁移并把链表顺序反转；线程 1 恢复后按自己记录的 `next` 继续走，
头插时把已经被改过的 `next` 重新接上，最终形成 A → B → A 的环。

#### Q1.1: 为什么 Java 8 改成尾插就不会成环了？

尾插不改变已有节点的 `next` 指向——新节点永远是接在链尾，
`p.next = e` 里的 `e` 是新创建的节点。而扩容时拆成 `lo`/`hi` 两条链表，
用的是 `loTail.next = e` 这种「只在尾部追加」的方式，同样不会修改
某个已被其他线程引用的中间节点的 `next` 到反方向上去。

更本质的说法是：**成环需要「修改方向」这个动作，而尾插只做「追加」**。
不过要小心，尾插保证的是不出现环，**不保证数据不丢**。

##### Q1.1.1: 那 Java 8 并发 put 到底会丢什么？

三类问题：

1. **同桶覆盖**：`tab[i] = newNode(...)` 与 `p.next = newNode(...)`
   都不是原子的，两条线程同时写同一个桶，后写的赢，先写的节点丢失。
2. **size 偏小**：`++size` 非原子，两条线程各自读到同一个旧值再写回，
   自增只生效一次。size 偏小会导致该扩容时不扩容。
3. **扩容期间读到 null**：扩容会把旧桶逐个置 null（`oldTab[j] = null`）
   再迁移，另一条线程若此时用旧表引用访问，可能读到 null 而误判 key 不存在。

第 3 类尤其危险，因为 `put` 成功了但紧接着 `get` 返回 null，业务上会
表现为「刚写的数据查不到」，而不是报错。所以**并发场景绝对不能靠
「出错会暴露」来兜底**，必须直接用 `ConcurrentHashMap`。

### Q2: 那为什么 `Collections.synchronizedMap` 也不够用？

因为它只对单个方法加锁，**复合操作仍然不是原子的**。典型反例是
「检查再插入」：

```java
Map<String, String> map = Collections.synchronizedMap(new HashMap<>());
if (!map.containsKey(key)) {     // ① 检查
    map.put(key, value);         // ② 写入
}
```

① 和 ② 各自是原子的，但组合起来不是。两条线程可能都通过 ① 的检查，
然后依次执行 ②，后者覆盖前者。

正确的用法是让调用方自己加锁包住整个复合操作，或者改用
`ConcurrentHashMap` 提供的原子复合方法：

```java
map.putIfAbsent(key, value);                              // 原子
map.computeIfAbsent(key, k -> heavyComputation(k));        // 原子
map.merge(key, 1, Integer::sum);                           // 原子
```

#### Q2.1: ConcurrentHashMap 为什么不允许 null key 和 null value？

因为**无法区分「这个 key 不存在」和「这个 key 存在但值是 null」**。

单线程的 `HashMap` 可以用 `containsKey` 再查一次来区分。但在并发容器里，
两个调用之间存在其他线程修改的窗口：

```java
if (map.containsKey(key)) {      // 此刻存在
    V value = map.get(key);      // 另一条线程可能已经 remove 了
    // value 为 null，到底是「本来就没有」还是「刚被删了」？
}
```

`ConcurrentHashMap` 的作者 Doug Lea 选择了从设计上消除这种二义性：
直接禁止 null，`put` 时抛 `NullPointerException`。
代价是不能再存 null 值，收益是所有读操作的结果都无歧义。
这也顺带解决了 `get` 返回 null 时无法判断原因的问题。

## 常见坑

- **说「Java 8 的 HashMap 是线程安全的，因为它不带头插」** —— 只是不再成环，
  覆盖丢数据和 size 失真依然存在
- **说「加 `Collections.synchronizedMap` 就安全了」** —— 单方法原子，
  复合操作仍需自行加锁
- **说「迭代时并发修改一定会抛 `ConcurrentModificationException`」** ——
  Javadoc 明确说这是 best-effort，不保证
- **说「`ConcurrentHashMap` 的 `size()` 是精确值」** —— 并发下是近似值，
  高并发时甚至要走遍历兜底
- **把 `Hashtable` 当成推荐方案** —— 它整表加锁且不允许 null，早已过时
- **认为 1.7 成环是「一定」发生的** —— 需要特定的线程交错时序，
  所以这个 bug 在生产上偶发且极难复现，但一旦触发就是 CPU 打满

## 加分点

- 能说清「头插改 direction、尾插只 append」这个区别，比背「1.7 头插 1.8 尾插」
  更能体现理解
- 知道 `ConcurrentHashMap` 1.8 的扩容支持**多线程协助迁移**：
  迁移中的桶会被放一个 `ForwardingNode`，其他线程遇到它就知道
  「这个桶正在迁移」，转而去帮忙迁移其他桶，而不是干等锁
- 知道 `ConcurrentHashMap.size()` 用了类似 `LongAdder` 的
  `baseCount` + `CounterCell` 分散热点，这也是「分治减少竞争」的经典应用
- 主动把「不可变对象天然线程安全」和「`final` 字段的安全发布」
  联系起来：如果 HashMap 在构造后不再修改，且通过 `final` 字段发布，
  多线程读是安全的——这是 `Collections.unmodifiableMap` 之外的另一条路
- 提到 Guava 的 `ImmutableMap` / JDK 9+ 的 `Map.of()` 在只读场景
  比 `ConcurrentHashMap` 更合适：无锁、更省内存

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 7 | 头插法扩容 + `indexFor` 重算下标；并发扩容可能形成环形链表，导致 get 死循环、CPU 100% |
| Java 8 | 尾插法 + `hash & oldCap` 分流；不再成环，但并发 put 仍会覆盖丢数据、size 失真 |
| Java 8+ | `ConcurrentHashMap` 改为 CAS + synchronized 锁桶头节点，并发粒度从 Segment 细化到单个桶 |
| Java 1.5 起 | `ConcurrentHashMap.size()` 在并发下即返回近似值；JDK 8 起用 `CounterCell` 分散计数优化高并发争用 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: Java 7 的 HashMap 在并发扩容时可能出现的最严重后果是什么？
    options:
      A: 抛 ConcurrentModificationException
      B: 链表形成环，后续 get 死循环导致 CPU 100%
      C: 直接抛 OutOfMemoryError
      D: 自动降级为 Hashtable
    answer: B
    analysis: 头插法会修改已有节点的 next 指针，两条线程交错迁移时链表被首尾相接成环，之后 get 落到该桶上会在环里死循环，表现为 CPU 打满且线程栈停在 HashMap 方法中。

  - type: MULTI
    stem: Java 8 的 HashMap 在并发 put 下仍会出现哪些问题？
    options:
      A: 同一个桶的写入互相覆盖，导致丢数据
      B: ++size 非原子，导致 size 偏小、扩容时机判断错误
      C: 扩容期间其他线程可能读到已被置 null 的桶，get 到刚 put 的 key 返回 null
      D: 扩容时形成环形链表导致死循环
    answer: ABC
    analysis: D 是 Java 7 的问题。Java 8 改尾插后不再成环，把「死循环」降级成了更难发现的「丢数据」。

  - type: JUDGE
    stem: ConcurrentHashMap 允许存放 null 值。
    answer: F
    analysis: ConcurrentHashMap 禁止 null key 和 null value。因为并发下无法区分「key 不存在」与「key 存在但值为 null」，Doug Lea 选择从设计上消除这种二义性。

  - type: JUDGE
    stem: 使用 Collections.synchronizedMap 包装后，先 containsKey 判断再 put 的复合操作是线程安全的。
    answer: F
    analysis: 包装器只保证单个方法原子。containsKey 与 put 之间存在窗口，两条线程可能都通过检查然后依次写入，后者覆盖前者。需要调用方自行加锁，或改用 ConcurrentHashMap 的 putIfAbsent 等原子复合方法。
````
