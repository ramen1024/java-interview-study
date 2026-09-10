---
slug: cas-and-aba
title: CAS 是什么？ABA 问题怎么解决？
module: concurrency
tags: [CAS, ABA, AtomicInteger, LongAdder, 无锁]
difficulty: 3
frequency: 3
related:
  - slug: volatile-visibility
    type: RELATED
  - slug: synchronized-lock-upgrade
    type: RELATED
  - slug: aqs-principle
    type: RELATED
  - slug: concurrent-collections
    type: RELATED
---

## 电梯版回答

CAS 是 Compare And Swap，三个操作数：内存地址 V、预期旧值 A、要写入的新值 B。只有当 V 处的当前值等于 A 时才把它改成 B 并返回成功，否则什么都不做返回失败，整个「比较加交换」是一条 CPU 原子指令完成，x86 上对应带 `lock` 前缀的 `cmpxchg`，Java 通过 `Unsafe` 的 `compareAndSwapInt` 等方法暴露。它不加锁也不做系统调用，但失败后要靠代码自己重试，也就是自旋，竞争激烈时会空耗 CPU。CAS 的经典缺陷是 ABA：值从 A 变成 B 又变回 A，CAS 只比较当前值，察觉不到中间发生过变化；在节点会被弹出又复用的栈或链表结构里，这会让一个已经脱链的旧节点被重新扶正，导致结构损坏、节点丢失。解法是加版本号用 `AtomicStampedReference`，只需要「有没有被标记过」这一位信息时用 `AtomicMarkableReference`。另外 `AtomicInteger.incrementAndGet()` 就是 CAS 自旋的教科书实现，而高并发计数用 `LongAdder` 比 `AtomicLong` 快得多，因为它把热点拆成多个 Cell 分段累加，读取时再求和。

## 展开讲解

### CAS 的三个操作数与底层指令

CAS 的语义可以用一行伪代码表达：

```
cas(V, A, B):
    if *V == A:
        *V = B
        return true
    else:
        return false
```

关键在于这个「读—比较—写」是原子的，不会被其他线程穿插。底层不是 Java 实现的：

- x86 上是 `cmpxchg` 指令，要保证多核原子性还得加 `lock` 前缀锁住缓存行（或总线）；操作 64 位以上的数据用 `cmpxchg8b` / `cmpxchg16b`。
- Java 层早期只通过 `sun.misc.Unsafe` 暴露，如 `compareAndSwapInt`、`compareAndSwapLong`、`compareAndSwapObject`；`AtomicInteger`、`AtomicReference` 这些原子类都是它们的封装。
- Java 9 起有了标准 API `VarHandle`，提供 `compareAndSet` 以及带内存序的版本，逐步替代直接用 `Unsafe`。

CAS 同时具有 volatile 读和 volatile 写的内存语义，所以既保证原子性，也建立 happens-before 关系——这一点常被忽略，很多人以为它只是个「无锁的原子操作」。

### 原子类的自旋是怎么写的

以 Java 8 的 `AtomicInteger` 为例，`incrementAndGet` 最终落到 `Unsafe.getAndAddInt`：

```java
public final int incrementAndGet() {
    return unsafe.getAndAddInt(this, valueOffset, 1) + 1;
}

public final int getAndAddInt(Object o, long offset, int delta) {
    int v;
    do {
        v = getIntVolatile(o, offset);              // 读当前值
    } while (!compareAndSwapInt(o, offset, v, v + delta));  // 值没变才写回
    return v;
}
```

`valueOffset` 是 `value` 字段在对象内的偏移，CAS 直接按地址操作。循环里如果 CAS 失败，说明 `v` 已经被别的线程改过，于是重新读、重新算、重新试——这就是「自旋」。自旋的代价是失败期间线程一直在用户态忙等，竞争越激烈、失败越频繁，CPU 空转越严重。

### ABA 问题与具体例子

考虑一个无锁栈（Treiber stack），每个节点用 `AtomicReference<Node> top` 表示栈顶，弹栈就是「读 top 和它的 next，再 CAS 把 top 换成 next」：

```
初始栈：top → A → B → C     （A.next = B，B.next = C）

线程 T1：读到 top = A、A.next = B，准备 CAS(top, A, B) 把 A 弹出
线程 T2：弹出 A（top = B）、弹出 B（top = C），然后把 A 重新 push 回去
         此时 top = A，且 A.next 已经被改成 C，B 已经脱链

线程 T1：执行 CAS(top, A, B)。因为 top 仍然是 A，CAS 成功，top 变成 B
```

T1 的 CAS 明明成功了，但 B 早已不在栈里，它现在把 top 指向了一个已经脱链、甚至可能已被复用为其他用途的节点。如果 B 的 `next` 已被改写、或者 B 已被对象池回收后另作他用，栈结构就被破坏，C 甚至其他节点会丢失。根因就是 CAS 只比较「当前值是不是 A」，无法区分「一直是 A」和「A 变走又变回来」。

### 解法：版本号与标记位

`AtomicStampedReference<V>` 把引用和一个 `int` 版本号打包成一个 `Pair`，一次 CAS 同时校验两者：

```java
AtomicStampedReference<Node> top = new AtomicStampedReference<>(nodeA, 0);

int[] stampHolder = new int[1];
Node expected = top.get(stampHolder);   // 同时取出引用和版本号
int expectedStamp = stampHolder[0];

boolean ok = top.compareAndSet(expected, newNode, expectedStamp, expectedStamp + 1);
```

即使引用从 A 又变回 A，版本号已经从 0 变成了别的值，`compareAndSet` 就会失败。代价是每次读要同时取两个值、每次写要同时改两个值。

`AtomicMarkableReference<V>` 只带一位布尔标记：

```java
boolean[] markHolder = new boolean[1];
V expected = ref.get(markHolder);
ref.compareAndSet(expected, newValue, markHolder[0], !markHolder[0]);
```

它只能区分「被标记过 / 没被标记过」两种状态，表达不了任意次数的变化，所以**不能**用来解决一般的 ABA，只适合「只需知道有没有被处理过」的场景——比如无锁链表里给待删除节点打删除标记。要区分多次 ABA 还是得用版本号。

### LongAdder 为什么比 AtomicLong 快

`AtomicLong` 在高并发下所有线程抢同一个 `value` 做 CAS，失败率高、频繁自旋，而且这个字段所在缓存行会在多个 CPU 核之间反复失效、来回搬运（缓存行乒乓）。`LongAdder` 的思路是**分散热点**：

- 继承 `Striped64`，内部有一个 `volatile long base` 和一个 `volatile Cell[] cells`。
- 低竞争时直接 CAS `base`；一旦 CAS 失败，就走 `longAccumulate` 为当前线程分配一个 `Cell`，之后各线程 CAS 自己那个 Cell，互不干扰，冲突概率大幅下降。
- `Cell` 用 `@sun.misc.Contended` 做缓存行填充，避免相邻 Cell 落在同一缓存行产生**伪共享**。
- 读取时 `sum()` 把 `base` 和所有 `Cell` 的值加起来。

所以 `LongAdder` 适合「写多读少、写并发极高」的计数场景（如 QPS 统计）。代价是 `sum()` 不是强一致的即时快照，遍历求和期间其他线程还在写，读到的数可能偏小；如果读远多于写、或需要基于当前值做判断，`AtomicLong` 反而更合适。`LongAccumulator` 是它的泛化版本，可自定义累加函数。

## 追问链

### Q1: CAS 为什么能不加锁就保证原子性？

因为原子性不是 Java 保证的，而是 CPU 指令保证的。CAS 编译后是一条带 `lock` 前缀的 `cmpxchg` 指令，硬件在同一时刻只会让一个核完成这次「比较加交换」，其他核的竞争会被缓存一致性协议挡住。Java 只是通过 `Unsafe.compareAndSwapInt` 这类本地方法把这条指令暴露出来，`AtomicInteger` 再把它包装成好用的 API。所以 CAS 属于**乐观策略**：先假设没有冲突直接改，冲突了就靠重试解决，而不是像锁那样先排队。

#### Q1.1: 那 CAS 失败之后怎么办？

由调用方自己重试，典型写法就是 `do-while` 自旋：读当前值，算出新值，CAS 期望旧值，失败就重新读、重新算。`AtomicInteger.incrementAndGet`、`AtomicReference.compareAndSet` 的常见用法都是这个模式。CAS 本身不会帮你重试，也不会阻塞；`compareAndSet` 返回 false 就是明确告诉你「我没改，你自己决定下一步」。

##### Q1.1.1: 自旋一直失败会有什么后果？

线程一直在用户态空转，白白消耗 CPU，还可能把其他有用的线程挤掉——这叫忙等（busy-wait），竞争越激烈越亏。极端情况下多个线程反复 CAS 同一个变量，性能可能比直接加锁还差，因为锁至少会把等不到的线程挂起。工程上的缓解手段是限制重试次数、退避（backoff，比如 `Thread.onSpinWait()` 或让出时间片），或者干脆换成能分散热点的结构，比如用 `LongAdder` 替代 `AtomicLong`。

### Q2: ABA 问题为什么危险？举个具体例子。

危险在于 CAS 的成功并不代表「状态没变过」。无锁栈的例子最直观：初始 `top → A → B → C`，T1 读到 `top = A`、`A.next = B`，准备 CAS 把 A 弹出；这时 T2 把 A 和 B 依次弹出，又把 A push 回去，此时 `top = A` 但 `A.next` 已经是 C，而 B 已经脱链。T1 的 CAS 比较 `top == A` 成功，把 top 改成 B——指向了一个已经不在栈里的旧节点。如果 B 被对象池复用、`next` 已被改写，栈就彻底乱了，节点丢失或读取到脏数据。

#### Q2.1: `AtomicStampedReference` 是怎么加版本号的？

它内部不是存裸引用，而是存一个 `Pair<V>`，把「引用 + int stamp」打包成一个对象，CAS 比对的是整个 `Pair` 的引用。读的时候用 `get(int[] stampHolder)` 一次把引用和版本号都取出来，写的时候用 `compareAndSet(expectedRef, newRef, expectedStamp, newStamp)`，四个条件同时满足才更新。因为引用和版本号在同一个 Pair 里一起被替换，两者的变化天然是原子的，不会出现「引用改了版本号没改」的中间态。

##### Q2.1.1: 那 `AtomicMarkableReference` 和它有什么区别？

区别在携带的信息量。`AtomicStampedReference` 带一个 32 位 `int` 版本号，能区分任意多次变化（理论上版本号会回绕，实际场景足够用）；`AtomicMarkableReference` 只带一位布尔标记，只能表达两种状态，所以它解决不了「A 变 B 又变回 A」这种一般 ABA，只适合「有没有被标记过」这一位就够的场景，比如无锁链表给节点打删除标记、判断某个引用是否已被处理。面试里如果只答「ABA 用 AtomicMarkableReference 解决」是错的，它只是特殊情况下够用。

### Q3: 那高并发计数到底该用 AtomicLong 还是 LongAdder？

看读写的比例和竞争程度。`AtomicLong` 的所有更新都 CAS 同一个 `value`，精确、即时，适合竞争不激烈、或者需要频繁读取当前精确值做判断的场景（比如生成序号、限流计数）。`LongAdder` 在竞争激烈时把写分散到多个 `Cell`，吞吐明显更高，适合「写多读少」的统计类计数；但它的 `sum()` 是遍历求和，读不是强一致的，而且会占用更多内存。粗略的取舍是：写并发高且读不频繁选 `LongAdder`，要精确即时值或竞争很低选 `AtomicLong`。

#### Q3.1: `LongAdder` 的 Cell 数组具体怎么减少竞争？

它继承 `Striped64`，核心是 `base` 加 `Cell[] cells`。`add` 先尝试 CAS `base`；失败说明有竞争，就调用 `longAccumulate`，用当前线程的 `Thread` 里一个探测值（probe）取模选一个 Cell，之后这个线程基本固定 CAS 自己的 Cell，和其他线程的 Cell 不冲突。这样 N 个线程的竞争从「N 抢 1」变成「N 分散到多个」，CAS 成功率大幅上升。读的时候 `sum()` 把 base 和各 Cell 累加。代价是内存变多、读取无锁一致性。

##### Q3.1.1: 为什么 Cell 要做伪共享填充？

伪共享（false sharing）指两个逻辑上无关的变量恰好落在同一个 CPU 缓存行里。一个核修改自己那个变量时，会让整条缓存行在其他核的副本失效，另一个核即使改的是同一行里另一个变量，也得重新从内存加载，缓存行在两个核之间来回弹跳，性能急剧恶化。`LongAdder` 的各个 Cell 会被不同线程频繁写，如果它们紧密排列就极可能共享缓存行，所以 `Cell` 用 `@sun.misc.Contended` 做填充，让每个 Cell 独占缓存行，把「写不同变量」变成「写不同缓存行」。这是用空间换并发性能的典型手法。

## 常见坑

- **「CAS 是 JVM 自己实现的原子操作」** —— JVM 只是暴露，真正保证原子性的是 CPU 的 `cmpxchg` 指令（x86 上带 `lock` 前缀）
- **「CAS 没有内存屏障，只保证原子性」** —— 错。CAS 具有 volatile 读写语义，既保证原子性也建立 happens-before 关系
- **「用了 CAS 就是完全无锁、没有代价」** —— CAS 本身无锁，但失败要自旋忙等，高竞争下 CPU 空转可能比加锁更差；它也只能保护单个变量
- **「ABA 只是理论问题，实际不会发生」** —— 在节点复用、对象池、地址复用的场景里真实存在，无锁栈和链表是重灾区
- **「AtomicStampedReference 能解决所有 ABA」** —— 版本号是 int 会回绕；而且它比对的是引用相等，值相等的不同对象不算同一个
- **「用 AtomicMarkableReference 就能解决 ABA」** —— 它只有一位布尔标记，只能区分两种状态，表达不了多次变化，只适合「是否被标记」这一位信息足够的场景
- **「AtomicInteger.compareAndSet 能检测 ABA」** —— 不能。它只比较当前值是否等于预期值，A 变 B 又变回 A，它照样成功
- **「CAS 可以一次原子地更新多个变量」** —— 不能，一条 CAS 只作用于一个内存地址。多变量要封装进一个对象用 `AtomicReference`，或改用锁
- **「LongAdder 的 sum() 是强一致的精确值」** —— 不是，它遍历求和的期间其他线程仍在写，读到的是近似快照，可能偏小
- **「高并发下 AtomicLong 一定比 LongAdder 好，因为它是原子的」** —— 写竞争激烈时 `LongAdder` 吞吐通常更高；只有在低竞争或需要即时精确读时才优先 `AtomicLong`

## 加分点

- 能说清 `AtomicStampedReference` 的 `Pair` 设计：把引用和版本号打包成一个对象再 CAS 整个 Pair 引用，这样两者的变更是原子的，而不是先改引用再改版本号（那样中间态会被别的线程看到）
- 提到无锁结构里规避 ABA 的另一种思路：不用「复用节点」而用「不复用」——每次 push 都新建节点、不复用已弹出的节点，从源头消除 ABA 的成因，代价是 GC 压力更大
- 知道 `AtomicMarkableReference` 的真实用途是给待删除节点打标记（如无锁链表删除两个阶段），而不是解决 ABA
- 知道 JDK 9 的 `VarHandle` 是 `Unsafe` CAS 的标准替代品，提供 `compareAndSet` 和带内存序的版本，新代码不应直接依赖 `Unsafe`
- 知道 `weakCompareAndSet` 与 `compareAndSet` 的区别是前者可能「虚假失败」（spurious failure），适合放在循环里重试；具体的内存语义在 Java 9 前后有过调整，回答时以所用 JDK 的官方文档为准
- 能横向联系 `LongAdder` 的家族：`LongAccumulator`、`DoubleAdder`、以及 `Striped64` 这套「base 加 Cell 数组」的分段思想，说明它是通用的高并发累加框架
- 提到伪共享时能顺带讲 `@Contended` 需要 JVM 支持（`-XX:-RestrictContended`）才能对用户类生效，JDK 内部的 `Striped64.Cell` 是特批的
- 知道 `ConcurrentHashMap` 的 `size` 统计、部分计数场景也用到了分段思想，能把「分散热点」抽象成一种通用优化模式

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 5 | 引入 `java.util.concurrent.atomic` 原子类，全部基于 `Unsafe` 的 CAS 实现无锁更新 |
| Java 8 | 新增 `LongAdder` 与 `LongAccumulator`（基于 `Striped64`），用 base 加 Cell 分段累加解决高并发计数瓶颈；`Cell` 用 `@sun.misc.Contended` 做缓存行填充 |
| Java 9 | 引入 `VarHandle` 标准 API，提供 `compareAndSet` / `weakCompareAndSet` 等带内存序语义的方法，作为直接使用 `Unsafe` 的替代方案 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: CAS 的三个操作数分别是什么？
    options:
      A: 内存地址 V、预期值 A、新值 B
      B: 旧值、新值、版本号
      C: 锁对象、线程、状态
      D: 变量名、期望类型、目标类型
    answer: A
    analysis: CAS 在地址 V 处比较当前值是否等于预期值 A，相等则写入新值 B 并返回成功，不等则不动并返回失败。Java 层由 Unsafe 的 compareAndSwapInt、compareAndSwapObject 等暴露，x86 上对应带 lock 前缀的 cmpxchg 指令。
    difficulty: 1

  - type: MULTI
    stem: 关于 CAS 的 ABA 问题及解法，下列说法正确的有？
    options:
      A: ABA 指值从 A 变成 B 又变回 A，CAS 只比较当前值所以察觉不到
      B: AtomicStampedReference 通过附加一个版本号来识破 ABA
      C: AtomicMarkableReference 只有一位布尔标记，只能区分两种状态，表达不了多次变化
      D: AtomicInteger.compareAndSet 自身就能检测 ABA
    answer: ABC
    analysis: D 错误，compareAndSet 只比较当前值或引用，中间变化又变回来它一样会成功。A 是问题定义；B 用版本号解决；C 的一位标记只适合「是否被处理过」这类场景，不能解决一般 ABA。
    difficulty: 2

  - type: JUDGE
    stem: LongAdder 的 sum() 返回的总数是强一致的精确值，可以作为并发写入时刻的准确计数。
    answer: F
    analysis: sum() 把 base 和各个 Cell 的值逐个读出来相加，遍历期间其他线程仍在写入，所以只是近似快照，可能偏小。需要精确即时值、或竞争不激烈时，用 AtomicLong 更合适。
    difficulty: 2

  - type: CLOZE
    stem: |
      补全原子类自旋 CAS 的循环，以及解决 ABA 的标准类：
      ```java
      // Unsafe.getAndAddInt 的自旋骨架
      do {
          v = getIntVolatile(o, offset);
      } while (!{{1}}(o, offset, v, v + delta));

      // 要识破「A 变 B 又变回 A」，需要额外的版本号，
      // 标准做法是用 {{2}} 把引用和 stamp 打包后一次 CAS
      ```
    blanks:
      - ["compareAndSwapInt", "compareAndSwapInt()"]
      - ["AtomicStampedReference"]
    analysis: CAS 失败（当前值不再等于读到的 v）时循环重试，这就是自旋。AtomicStampedReference 内部用 Pair 把对象引用和一个 int 版本号打包，每次 CAS 同时带上 expectedStamp 和 newStamp，值回到 A 但版本号已经变了，CAS 就会失败，从而识别出 ABA。
    difficulty: 3
````
