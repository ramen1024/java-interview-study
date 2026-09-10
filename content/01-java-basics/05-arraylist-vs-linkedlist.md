---
slug: arraylist-vs-linkedlist
title: ArrayList 的扩容机制是怎样的？和 LinkedList 怎么选？
module: java-basics
tags: [ArrayList, LinkedList, 集合, 扩容]
difficulty: 1
frequency: 3
related:
  - slug: hashmap-internals
    type: CONTRAST
  - slug: initialization-order
    type: RELATED
---

## 电梯版回答

ArrayList 底层是可变长数组，默认容量 10，但和 HashMap 一样是第一次 add 时才真正分配。数组装不下时按 1.5 倍扩容，JDK 8 的写法是 `newCapacity = oldCapacity + (oldCapacity >> 1)`，再用 `Arrays.copyOf` 把老数组整段复制过去，这是一次 O(n) 的开销，所以能预估大小时应该用带初始容量的构造方法或 `ensureCapacity` 提前分配。它的下标访问是 O(1)，但在中间插入删除要用 `System.arraycopy` 搬移后面的元素，是 O(n)。LinkedList 底层是双向链表，中间插入删除本身只要改指针，可定位到那个位置仍然得从头或从尾遍历，同样是 O(n)；而且每个节点都有独立对象头和前后指针的额外开销，内存不连续对 CPU 缓存也不友好。所以除了明确只做两端操作的队列场景，实际几乎总是选 ArrayList，LinkedList 是 JDK 里少数基本可以不用的集合。

## 展开讲解

### 容量与延迟分配

核心字段（JDK 21）：

```java
private static final int DEFAULT_CAPACITY = 10;
private static final Object[] EMPTY_ELEMENTDATA = {};
private static final Object[] DEFAULTCAPACITY_EMPTY_ELEMENTDATA = {};
transient Object[] elementData;   // 实际存放元素的数组
private int size;                 // 元素个数，不是容量
protected transient int modCount; // 结构性修改次数，供 fail-fast 用
```

三个构造方法的行为不一样，这是最容易被追问的点：

| 构造方法 | 初始 `elementData` | 第一次 add 后容量 |
|---|---|---|
| `new ArrayList<>()` | `DEFAULTCAPACITY_EMPTY_ELEMENTDATA`（共享空数组） | 10 |
| `new ArrayList<>(0)` | `EMPTY_ELEMENTDATA`（共享空数组） | 1 |
| `new ArrayList<>(n)` | `new Object[n]`，立即分配 | n |

两个空数组常量内容一样，靠 `==` 区分：默认构造的那个才会在首次 add 时膨胀到 `DEFAULT_CAPACITY`，`new ArrayList<>(0)` 只按需扩到 1。所以「ArrayList 一创建就有 10 个槽位」是错的——Java 7 起改成了延迟分配，只有 `new ArrayList<>(n)` 是立刻分配。

### 扩容：1.5 倍与 Arrays.copyOf

JDK 8 的 `grow` 是最好记的版本：

```java
private void grow(int minCapacity) {
    int oldCapacity = elementData.length;
    int newCapacity = oldCapacity + (oldCapacity >> 1);  // 1.5 倍
    if (newCapacity - minCapacity < 0)
        newCapacity = minCapacity;                       // 一次要得更多就按需扩
    if (newCapacity - MAX_ARRAY_SIZE > 0)
        newCapacity = hugeCapacity(minCapacity);
    elementData = Arrays.copyOf(elementData, newCapacity);
}
```

JDK 21 把这段委托给 `ArraysSupport.newLength`，单元素 add 时语义等价：

```java
int newCapacity = ArraysSupport.newLength(
        oldCapacity,
        minCapacity - oldCapacity,  // minimum growth
        oldCapacity >> 1            // preferred growth
);
elementData = Arrays.copyOf(elementData, newCapacity);
```

`ArraysSupport.newLength` 的实现是 `oldLength + Math.max(minGrowth, prefGrowth)`：普通单元素 add 时 minGrowth = 1、prefGrowth = `oldCapacity >> 1`，取后者，就是 1.5 倍；一次 `addAll` 要得更多时按需要的量扩。容量序列是 10 → 15 → 22 → 33 → 49，注意右移是整数运算、向下取整（15 + (15 >> 1) = 15 + 7 = 22）。

`Arrays.copyOf` 底层是 `System.arraycopy`，而且是**浅拷贝**：只复制引用，元素对象本身不复制，所以扩容的代价是复制 n 个引用而不是 n 个对象。代价有两点：时间 O(n)，以及扩容瞬间新旧数组同时存在，峰值内存约为原来的 1.5 倍，旧数组要等 GC 回收。

### 能预估容量就别让它反复扩容

- `new ArrayList<>(n)`：创建时就分配 n 长度的数组，适合已知规模。
- `ensureCapacity(int minCapacity)`：批量 add 之前预留。`minCapacity <= elementData.length` 时直接返回；如果当前是默认构造的共享空数组且 `minCapacity <= DEFAULT_CAPACITY` 也直接返回。它只改容量，不改 `size`，也不填充元素。
- `trimToSize()`：反向收缩到当前 `size`，释放多余槽位。

### 随机访问 vs 中间插入删除

```java
// 随机访问：边界检查 + 数组下标
public E get(int index) {
    Objects.checkIndex(index, size);
    return elementData(index);        // O(1)
}

// 指定位置插入：把 index 之后的元素整体后移一格
public void add(int index, E element) {
    rangeCheckForAdd(index);
    modCount++;
    final int s;
    if ((s = size) == elementData.length)
        elementData = grow();
    System.arraycopy(elementData, index, elementData, index + 1, s - index);
    elementData[index] = element;
    size = s + 1;                     // O(n)
}
```

- `get(index)` / `set(index, e)` 是 O(1)。
- `add(index, e)` / `remove(index)` 是 O(n)，因为要 `System.arraycopy` 搬移。
- 尾部 `add(e)` 均摊 O(1)，偶尔摊上一次扩容复制。

### LinkedList 是双向链表

```java
private static class Node<E> {
    E item;
    Node<E> next;
    Node<E> prev;
}
transient Node<E> first;
transient Node<E> last;
transient int size;
```

- `get(index)` 的实现是 `node(index).item`，而 `node(index)` 会先判断 `index < (size >> 1)`，决定从 `first` 还是 `last` 线性走过去——所以**按下标访问是 O(n)**，不是 O(1)。
- `add(index, e)` / `remove(index)` 同样先 `node(index)` 定位，再改指针，整体 O(n)。
- 真正 O(1) 的只有「已经持有节点引用」的操作：`addFirst` / `removeFirst` / `addLast` / `removeLast`，以及用 `ListIterator` 遍历时的 `remove()`（删的是刚经过的节点，已有引用）。
- LinkedList 实现了 `Deque`，可以当栈或队列用。

### 为什么实际几乎总选 ArrayList

- **内存连续，CPU 缓存友好**：数组元素挨着放，遍历时缓存行命中率高、硬件预取器有效；链表节点散落在堆上，每跳一次基本就是一次 cache miss。
- **每个元素多一个对象**：每个 `Node` 都是独立对象，压缩指针下大约是「对象头 12 字节 + item 引用 4 字节 + next 4 字节 + prev 4 字节」，对齐后约 24 字节，还没算 item 本身；ArrayList 一个元素只占一个数组槽位（一个引用）。
- **「插入删除快」的前提不成立**：按下标操作仍要 O(n) 定位；`System.arraycopy` 是连续内存的批量拷贝，常数极小，常常比链表从头遍历找位置还快。
- **分配成本**：链表每次插入都要 `new Node`，分配、写屏障、后续 GC 都有开销；数组扩容是偶发的。

结论：需要栈或队列时优先 `ArrayDeque`（也是数组实现），LinkedList 的独特价值基本只剩「需要 List 接口 + 频繁在已知节点处增删」或需要存 null 元素（`ArrayDeque` 不允许 null）。

## 追问链

### Q1: 你说扩容是 1.5 倍，为什么是 1.5 倍而不是 2 倍？

官方没有给出严格论证，1.5 是个经验值。普遍的解释是：2 倍增长更激进，申请的容量会明显超过实际需要，低负载时浪费更多内存；1.5 倍增长更平缓，空间利用率更好，代价只是扩容次数略多。ArrayList 也没有像 HashMap 那样必须取 2 的幂的理由——HashMap 要用 `(n - 1) & hash` 代替取模，所以容量只能翻倍——所以它选择了 1.5 这个折中值。

#### Q1.1: 那扩容具体要付出什么代价？为什么说能预估容量就该提前指定？

`Arrays.copyOf` 会新建一个数组并 `System.arraycopy` 复制所有引用，时间 O(n)；同时旧数组在这一刻还活着，峰值内存约为原来的 1.5 倍，之后才能被 GC 回收。假设一个列表最终要装 100 万个元素，从默认的 10 一路扩容上去要经历几十次复制，累计复制量是元素数量的常数倍，还制造了大量临时数组。所以已知规模就 `new ArrayList<>(n)`，或者在批量添加之前 `ensureCapacity(n)`。

##### Q1.1.1: 调了 ensureCapacity 之后 size 会变吗？传的 minCapacity 比当前容量小呢？

都不会。`ensureCapacity` 只改 `elementData` 的容量，不改 `size`，也不填充任何元素，`size` 仍然只在 add 之后增加。如果 `minCapacity <= elementData.length`，第一个条件不成立，方法直接返回；如果当前还是默认构造的共享空数组且 `minCapacity <= DEFAULT_CAPACITY`，也直接返回——因为按默认路径首次 add 本来就会给到 10。另外注意 `new ArrayList<>(n)` 是立即分配 n 长度数组，和 `ensureCapacity` 的按需扩容不是一回事。

### Q2: LinkedList 中间插入删除不是 O(1) 吗？为什么说实际几乎总选 ArrayList？

O(1) 只在**你手里已经有目标节点引用**时成立，比如 `addFirst` / `removeFirst`，或者用 `ListIterator` 边遍历边删。最常见的 `list.add(index, e)` / `list.remove(index)` 要先调 `node(index)` 从表头或表尾线性走到那个位置，是 O(n) 定位，再 O(1) 改指针，整体 O(n)。而 ArrayList 的插入虽然要搬数组，但搬的是连续内存，常数小得多，实跑起来常常更快。再加上链表节点内存分散、每个元素多出一个 Node 对象，遍历和插入成本都更高。

#### Q2.1: 那什么场景下确实该用 LinkedList？

两种：一是需要频繁在两端增删且不需要随机访问，同时明确要 `List` 语义——但即便如此通常也该先考虑 `ArrayDeque`，它同样实现 `Deque`、没有节点开销、性能更好；二是需要在**已有节点引用**的情况下频繁插入删除，比如用 `ListIterator` 在遍历中反复插入，这时链表是 O(1) 而 ArrayList 每次插入 O(n)。只有当你既要两端操作、又必须允许存 null 元素时（`ArrayDeque` 不允许 null），LinkedList 才真正轮得到。

##### Q2.1.1: 用迭代器边遍历边删除时，ArrayList 会不会退化得比 LinkedList 更差？

会，但要看怎么写。`for (int i = 0; i < list.size(); i++) list.remove(i)` 每次删除都要 `arraycopy` 搬移剩余元素，删光整个列表是 O(n²)，还会因为下标前移而漏删。换成 `Iterator.remove()` 好一些，但 ArrayList 的迭代器删除底层仍然是逐次 `remove(int)`，删光所有元素同样是 O(n²)；LinkedList 的迭代器删除是 O(1)/次，总量 O(n)，这一点上链表占优。更推荐 `removeIf(...)`：ArrayList 的实现是用位数组标记要删的位置，再一趟原地压缩，把复杂度拉回 O(n)，也顺便避免了漏删。所以「边遍历边删」并不构成选 LinkedList 的理由，换用 removeIf 就好。

### Q3: ArrayList 和 HashMap 都是数组扩容，为什么扩容策略不一样？

HashMap 的容量必须是 2 的幂，因为它要用 `(n - 1) & hash` 代替取模，所以扩容只能翻倍；翻倍还有个额外好处，扩容时只判断 `hash & oldCap` 这一位就能把链表一分为二，不用重算 hash。ArrayList 没有取模需求，选了 1.5 倍，扩容时只能老老实实整段 `System.arraycopy`。两者共同的代价都是 O(n) 的数组复制，所以能预估大小时都应该用带初始容量的构造方法。延迟分配这一点上两者倒一致：HashMap 第一次 put 才建表，ArrayList 第一次 add 才扩到 10。

#### Q3.1: 那 ArrayList 为什么实现了 RandomAccess？

`RandomAccess` 是个空的标记接口，`Collections.binarySearch`、`Collections.shuffle`、`Collections.reverse` 这些工具类会 `instanceof RandomAccess` 做判断：是就用下标 for 循环（数组 O(1) 访问，最快），不是就改用 `ListIterator`（对顺序访问的 LinkedList 可以避免每次 `get` 都 O(n) 遍历）。LinkedList 没有实现它，所以这些算法作用在 LinkedList 上会自动走迭代器路径。

## 常见坑

- **「ArrayList 默认容量是 10，所以 new 的时候就有 10 个槽位」** —— 默认构造只赋一个共享空数组 `DEFAULTCAPACITY_EMPTY_ELEMENTDATA`，第一次 add 才扩到 10；Java 6 才是构造时直接分配 `new Object[10]`。
- **「`new ArrayList<>()` 和 `new ArrayList<>(0)` 一样」** —— 首次 add 后前者容量 10、后者容量 1，因为用了两个不同的空数组常量来区分。
- **「扩容就是 oldCapacity × 1.5」** —— 是 `oldCapacity + (oldCapacity >> 1)`，整数右移向下取整（10→15→22→33），而且当 `minCapacity` 更大时直接取 `minCapacity`。
- **「LinkedList 插入删除是 O(1)，所以增删多就用 LinkedList」** —— 按下标的 `add`/`remove` 要先 O(n) 定位；再加上每节点对象开销和内存不连续，实际几乎总是 ArrayList 更快。
- **「ArrayList 是线程安全的」** —— 非线程安全。`add` 里的 `elementData[size++] = e` 不是原子操作，并发会丢元素、`size` 与实际不符。读多写少用 `CopyOnWriteArrayList`，否则用 `Collections.synchronizedList` 或外部加锁。
- **「遍历时用 for + remove(i) 删除没问题」** —— 会漏删元素并退化成 O(n²)，应该用 `removeIf(...)` 或迭代器的 `remove()`。
- **「ArrayList 存 int 没有装箱开销」** —— 泛型只能存引用类型，存 int 会自动装箱成 Integer，每个元素都是独立对象；性能敏感场景要用数组或第三方 primitive 集合。

## 加分点

- **fail-fast 与 modCount**：所有结构性修改都会 `modCount++`，迭代器 `next()` 会调 `checkForComodification()` 比较 modCount，不一致就抛 `ConcurrentModificationException`。这是「尽力而为」的检测，单线程误用也会触发，但它不是并发安全的保证。
- **`removeIf` 不是逐个删**（JDK 8+）：先用位数组标记要删除的位置，再一趟原地压缩，避免逐个删除的 O(n²)。
- **`Arrays.copyOf` 是浅拷贝**：扩容、`toArray`、`subList` 都不会复制元素对象。
- **`subList` 返回视图不是拷贝**：对 subList 的修改会写回原 list；原 list 结构变了之后再去操作 subList 会抛 `ConcurrentModificationException`。
- **`ArrayDeque` 比 LinkedList 更适合做栈和队列**：同样是数组实现（环形缓冲），没有节点开销，也不允许 null；`Stack` 继承 `Vector`、方法都 synchronized，早就不该用了。
- **`Collections.unmodifiableList` 只是包装**：返回只读视图，底层 list 改了它也跟着变，不等于不可变。

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 6 | 默认构造 `new ArrayList<>()` 立即分配 `new Object[10]` |
| Java 7 | 改为延迟分配：默认构造赋共享空数组，首次 add 才扩到 10 |
| Java 8 | `grow` 写法为 `newCapacity = oldCapacity + (oldCapacity >> 1)`，上限 `MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8`；`List` 增加 `sort` / `replaceAll` / `removeIf` 等默认方法 |
| JDK 11（已核对源码） | `grow` 仍通过私有 `newCapacity(minCapacity)` 计算，公式等价于 1.5 倍，上限 `Integer.MAX_VALUE - 8` |
| JDK 21（已核对源码） | `grow` 改为委托 `ArraysSupport.newLength(oldCapacity, minGrowth, oldCapacity >> 1)`，取 `max(minGrowth, prefGrowth)`；软上限常量改为 `SOFT_MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8` |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: ArrayList 的默认初始容量是多少，它在什么时候真正分配？
    options:
      A: 10，构造 ArrayList 时立即分配
      B: 10，第一次 add 时分配
      C: 16，第一次 add 时分配
      D: 0，永远逐个元素增长
    answer: B
    analysis: DEFAULT_CAPACITY = 10，但 Java 7 起默认构造只赋共享空数组 DEFAULTCAPACITY_EMPTY_ELEMENTDATA，第一次 add 才扩到 10。只有 new ArrayList<>(n) 是立即分配。

  - type: JUDGE
    stem: 容量为 10 的 ArrayList 触发扩容后，容量会直接变成 22。
    answer: F
    analysis: 10 触发扩容得到 15（10 + (10 >> 1)），15 再扩容才得到 22（15 + 7）。1.5 倍在整数运算下向下取整，所以序列是 10→15→22→33。

  - type: MULTI
    stem: 关于 ArrayList 与 LinkedList，下列说法正确的有？
    options:
      A: ArrayList 的 get(index) 是 O(1)，LinkedList 的 get(index) 是 O(n)
      B: LinkedList 的 add(index, e) 不需要定位，是 O(1)
      C: LinkedList 的每个节点都有对象头以及前驱、后继两个引用
      D: ArrayList 中间插入要用 System.arraycopy 搬移元素，是 O(n)
    answer: ACD
    analysis: B 错误。LinkedList 的 add(index, e) 要先调 node(index) 从表头或表尾线性遍历到该位置，是 O(n) 定位，只有已持有节点引用时才是 O(1)。

  - type: CLOZE
    stem: |
      补全 JDK 8 ArrayList 扩容时计算新容量的表达式与复制方法：
      ```java
      private void grow(int minCapacity) {
          int oldCapacity = elementData.length;
          int newCapacity = oldCapacity + (oldCapacity {{1}} {{2}});
          if (newCapacity - minCapacity < 0)
              newCapacity = minCapacity;
          elementData = Arrays.{{3}}(elementData, newCapacity);
      }
      ```
    blanks:
      - [">>"]
      - ["1"]
      - ["copyOf"]
    analysis: 1.5 倍写作 oldCapacity + (oldCapacity >> 1)，右移一位相当于整除 2；最终的数组复制用 Arrays.copyOf，底层是 System.arraycopy 的浅拷贝，只复制引用。
    difficulty: 3
````
