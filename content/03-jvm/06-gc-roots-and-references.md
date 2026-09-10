---
slug: gc-roots-and-references
title: 怎么判断一个对象该被回收？四种引用有什么区别？
module: jvm
tags: [GC Roots, 可达性分析, 引用, 内存回收]
difficulty: 2
frequency: 3
related:
  - slug: garbage-collection-algorithms
    type: PREREQUISITE
  - slug: oom-troubleshooting
    type: DEEPEN
  - slug: threadlocal-memory-leak
    type: RELATED
---

## 电梯版回答

判断对象是否可回收，Java 用的是可达性分析而不是引用计数。引用计数有个致命缺陷——两个对象互相引用时计数永远不为 0，没法回收；而且每次赋值都要维护计数，开销也不小。可达性分析的做法是从一批称为 GC Roots 的根对象出发，顺着引用链往下遍历，遍历不到的对象就是可回收的。GC Roots 主要有：虚拟机栈局部变量表中引用的对象、方法区静态属性引用的对象、方法区常量引用的对象、本地方法栈 JNI 引用的对象，以及活跃线程、被 synchronized 持有的对象、JVM 内部的基本类型 Class 对象和常驻异常对象等。要注意不可达不等于立刻回收，对象还要经过两次标记，如果覆盖了 finalize() 且从没执行过，会被放进 F-Queue 里给一次自救机会。按引用强度从强到弱有四种：强引用只要可达就永不回收；软引用在内存不足时回收，适合做缓存；弱引用下一次 GC 必回收，ThreadLocalMap 的 key 就是弱引用；虚引用根本取不到对象，只用来在回收时收到通知，必须配合引用队列。

## 展开讲解

### 为什么不用引用计数

引用计数给每个对象维护一个被引用次数，计数归零就回收。它实现简单、可以即时回收，但有两个问题：

1. **循环引用无法回收**。这是致命缺陷：

```java
class Node {
    Node next;
}

Node a = new Node();
Node b = new Node();
a.next = b;
b.next = a;
a = null;
b = null;
// 此时 a、b 两个对象的计数都是 1，但它们已经无法从任何地方访问
// 引用计数法回收不了，可达性分析可以
```

2. **维护成本高**。每次引用赋值都要增减计数，且计数更新必须保证原子性（并发场景），带来额外开销。

主流的 HotSpot 用的是**可达性分析（Reachability Analysis）**。

### GC Roots 有哪些

可达性分析从 GC Roots 出发，沿引用链（引用链就是对象到对象的引用关系）遍历，能到达的对象标记为存活。GC Roots 不是固定的几个，而是一组「肯定还活着」的对象：

| GC Root | 说明 |
|---|---|
| 虚拟机栈中引用的对象 | 每个栈帧的局部变量表里引用的对象，也就是方法参数和局部变量。这是最主要的一类 |
| 方法区中静态属性引用的对象 | 类的 `static` 字段引用的对象 |
| 方法区中常量引用的对象 | 常量池里引用的对象，例如字符串常量池里的字符串 |
| 本地方法栈中 JNI 引用的对象 | native 方法通过 JNI 持有的引用 |
| 活跃线程 | 正在运行的线程本身（其栈就是上面第一类） |
| 被同步锁持有的对象 | `synchronized` 的 monitor 持有的对象，锁没释放就不能回收 |
| JVM 内部引用 | 基本数据类型对应的 Class 对象（如 `int.class`）、常驻异常对象（`NullPointerException`、`OutOfMemoryError` 等）、系统类加载器 |

最后一条常被忽略：JVM 会**预先创建**一批异常对象，目的是避免在内存已经耗尽时还要去 new 一个异常，导致连异常都抛不出来。

另外，使用 G1 等分代收集器时，会有一些**临时的 GC Roots**（如 Remembered Set 里的跨代引用），这是实现细节，不影响上面的模型。

### 不可达不等于立刻死

被判为不可达的对象**不会马上被回收**，要经历两次标记：

1. **第一次标记与筛选**。可达性分析发现不可达后做一次标记，然后筛选：判断对象**是否有必要执行 `finalize()`**。只有「对象覆盖了 `finalize()` 且它从未被调用过」才算有必要执行；没覆盖过、或已经执行过，都直接进入待回收集合。
2. **第二次标记**。有必要执行的对象会被放进 **F-Queue**，由虚拟机自己建立的一条低优先级 **Finalizer 线程**去执行它们的 `finalize()`。GC 随后对 F-Queue 中的对象做一次小规模标记。如果某个对象在 `finalize()` 中重新与引用链上的对象建立了关联（例如把自己赋给某个静态变量），它就被移出「即将回收」集合，实现**自救**。

`finalize()` 有几个必须记住的约束：

- **最多被调用一次**，由虚拟机保证。所以自救只有一次机会，第二次不可达时就真回收了。
- **不保证一定执行完**。Finalizer 线程优先级低，GC 不保证等它跑完，可能出现「对象已被回收但 finalize() 还没执行」。
- **不保证执行顺序**，运行代价高、不确定性大。所以《Java 编程思想》和官方都建议：不要用它释放资源，用 try-with-resources 或 Cleaner。

```java
public class SelfRescue {
    static SelfRescue SAVE_ME;

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        System.out.println("finalize 执行了");
        SAVE_ME = this;   // 重新建立引用，自救成功
    }

    public static void main(String[] args) throws Exception {
        SAVE_ME = new SelfRescue();
        SAVE_ME = null;
        System.gc();
        Thread.sleep(500);            // 等 Finalizer 线程执行
        System.out.println(SAVE_ME == null ? "已回收" : "自救成功");
    }
}
```

`System.gc()` 只是**建议**虚拟机执行 GC，并不保证；这段代码的输出不是稳定确定的，正说明了 finalize 的不可靠。

### 四种引用

引用强度从高到低，决定了对象在「内存紧张」和「下一次 GC」时的不同命运：

| 引用类型 | 相关类 | 回收时机 | 典型用途 |
|---|---|---|---|
| 强引用 | 无（直接赋值） | 只要引用链可达，**永远不回收**，即使抛 OOM | 普通对象引用 |
| 软引用 | `SoftReference` | 内存不足、即将 OOM 时回收；内存充足时不回收 | 内存敏感的缓存（图片、计算结果） |
| 弱引用 | `WeakReference` | **下一次 GC 必回收**，不管内存是否充足 | `ThreadLocalMap` 的 key、`WeakHashMap` |
| 虚引用 | `PhantomReference` | 无法通过它访问对象（`get()` 恒为 `null`），仅在对象被回收时收到通知 | 管理堆外内存，配合 `ReferenceQueue` |

```java
// 强引用：o 不可达前，GC 不动它
Object o = new Object();

// 软引用：内存不足时被回收
SoftReference<byte[]> soft = new SoftReference<>(new byte[1024 * 1024]);

// 弱引用：下一次 GC 就被回收
WeakReference<Object> weak = new WeakReference<>(new Object());

// 虚引用：必须传 ReferenceQueue，get() 永远是 null
ReferenceQueue<Object> queue = new ReferenceQueue<>();
PhantomReference<Object> phantom = new PhantomReference<>(new Object(), queue);
```

### 引用队列与 Cleaner

**引用队列 `ReferenceQueue` 的作用**：注册到队列上的引用对象（注意是 `Reference` 实例本身，不是被引用的对象），会在**它引用的对象被回收之后**被加入队列。程序可以从队列里取出这些引用，做后续清理。

关键点是「先回收对象，再入队」，所以拿到队列里的引用时，`get()` 已经取不到原对象了——这正是虚引用的设计目的：你只能在对象**已经死了**之后得到通知，不可能用它复活对象。

`PhantomReference` 的两个硬约束：

1. **必须传入 `ReferenceQueue`**，否则毫无意义（没有通知目标）。
2. `get()` **永远返回 `null`**，故意不让你访问对象，避免影响回收时机。

经典应用是 `DirectByteBuffer` 的堆外内存释放：它注册了一个 Cleaner，而 Cleaner 继承自 `PhantomReference`。当 DirectByteBuffer 对象被回收、虚引用入队时，清理逻辑通过 `Unsafe.freeMemory` 把对应的堆外内存释放掉。这也是「堆外内存不需要手动 free」的底层原因。

## 追问链

### Q1: 怎么判断一个对象该被回收？

Java 用**可达性分析**：从一组 GC Roots 出发，沿引用链遍历整个对象图，能到达的就标记为存活，遍历不到的就被判为可回收。

它解决的就是引用计数解决不了的循环引用问题——对象之间互相引用没关系，只要没有一条从 GC Roots 出发的路径能走到它们，就一起回收。

#### Q1.1: 为什么不用引用计数？它不是很直观吗？

直观是直观，但有两个硬伤。

第一，**循环引用回收不了**。两个对象互相持有，引用计数都是 1，但外部已经没有任何引用能访问到它们，逻辑上早该回收了，计数法却坚持它们活着。这是内存泄漏。

第二，**维护成本高且需要原子性**。引用赋值是极高频操作，每次都要改计数；多线程下计数更新还得保证原子，开销不小。

##### Q1.1.1: 那引用计数就一无是处吗？还有语言用它吗？

不是一无是处，Python 就主要用引用计数，再配一个**循环垃圾收集器**（cycle collector）专门定期扫循环引用。原因是引用计数有一个别人替代不了的优点：**可以即时回收**。

引用计数在计数归零的瞬间就能释放对象，不用等 GC 周期。这对「及时释放文件句柄、socket 等确定性资源」的语言（典型是 Python 的 `__del__`、CPython 的引用语义）很有价值。

HotSpot 不这么做，是因为 JVM 的强项是高性能 GC 与内存局部性，不需要把回收做成即时的；而循环引用是纯粹用计数无法绕过的坑。

### Q2: GC Roots 具体包括哪些？

主要有七类，面试说出前四类就够，能补后三类是加分：

1. **虚拟机栈中引用的对象**——栈帧的局部变量表里引用的对象，包括方法参数和局部变量，这是最主要的一类
2. **方法区静态属性引用的对象**——类的 `static` 字段
3. **方法区常量引用的对象**——常量池中的引用，如字符串常量
4. **本地方法栈中 JNI 引用的对象**——native 方法持有的引用
5. **活跃线程**——运行中的线程本身
6. **被同步锁持有的对象**——`synchronized` 的 monitor 持有的对象
7. **JVM 内部引用**——基本类型的 Class 对象（`int.class`）、常驻异常对象、系统类加载器

注意「栈」指的是**局部变量表里的引用**，不是整个栈本身。栈帧随方法返回出栈后，里面的引用自然消失，对象就可能变得不可达。

#### Q2.1: 不可达的对象一定会被回收吗？

不一定，要看它有没有**覆盖 `finalize()` 且从没执行过**。

`finalize()` 是对象被回收前的最后一道关卡，但只针对「覆盖了它且还没执行过」的对象。不满足条件的对象直接进入待回收集合。满足条件的会被放进 F-Queue，由 Finalizer 线程执行一次，如果在 `finalize()` 里重新把自己挂回引用链，就能自救。

所以「不可达 = 立刻回收」是错的。但这个机制极其不可靠：不保证执行、最多一次、不保证顺序，已经不被推荐使用。

##### Q2.1.1: 那在 `finalize()` 里自救成功一次后，第二次还能救吗？

不能。**`finalize()` 最多只被调用一次**，这是虚拟机保证的。

第二次变成不可达时，筛选阶段判断「已经执行过 finalize」，直接进入待回收集合，不会再给机会。所以自救是「一次性」的——很多考察 finalize 的题会问「为什么第一个对象自救成功、第二个失败」，答案就在这里。

实践中自救手法（把 `this` 赋给静态变量）现在只作为理解标记过程的例子，不要写进生产代码。要管理资源，Java 9 起有 `Cleaner`，或者直接用 try-with-resources。

### Q3: 四种引用有什么区别？

按强度从高到低：强、软、弱、虚，回收积极性依次升高。

- **强引用**：`Object o = new Object()`，最普通。只要引用链从 GC Roots 可达，无论内存多紧张都不回收，宁可抛 `OutOfMemoryError`。这是 `OOM` 的常见来源——一个静态集合不断往里塞对象，就一直可达。
- **软引用 `SoftReference`**：内存**充足时不回收**，内存不足（即将 OOM）时才回收。适合做缓存，用内存换性能，内存紧张时自动让路。
- **弱引用 `WeakReference`**：**下一次 GC 一定回收**，不管内存够不够。生命周期只到下一次 GC 前，适合表达「有就用、没有就重建」的附属关系。
- **虚引用 `PhantomReference`**：`get()` 恒为 `null`，无法访问对象，唯一用途是回收通知，必须配合 `ReferenceQueue`。

#### Q3.1: 软引用到底什么时候被回收？

准确说法是：**在 GC 判断内存不足、即将发生 OOM 时回收**，而不是「一有 GC 就回收」，也不是「非要等到抛出 OOM 才回收」。

HotSpot 的实现会综合考虑空闲堆大小和「上次使用时间」做近似 LRU 的选择：**软引用对象被清理时，优先清理最久未使用的**。所以软引用很适合实现「内存不够就淘汰冷数据」的缓存。

由于回收时机由 GC 根据内存压力决定，程序不能假设软引用对象一定还在——每次 `get()` 都可能返回 `null`，必须判空后回退到重新计算。

##### Q3.1.1: `-XX:SoftRefLRUPolicyMSPerMB` 是干什么的？

它控制**软引用在被回收前能存活多久**，单位是「每 MB 空闲堆对应的毫秒数」，默认值 1000。

含义是：堆里每空闲 1 MB，软引用对象大约可以保留 1000 ms。比如空闲 100 MB，那么软引用对象在被 LRU 选中前大约能存活 100 秒左右。空闲堆越多，软引用保留越久；内存越紧张，空闲堆越小，软引用越快被清理。

这个参数调大，缓存命中率更高但更容易 OOM；调小则缓存更容易失效。它只在软引用参与回收决策时有意义，说明软引用的回收时机是**可调的策略**，不是固定规则。

#### Q3.2: 弱引用在 `ThreadLocalMap` 里是怎么用的？

`ThreadLocalMap` 的 `Entry` 继承自 `WeakReference<ThreadLocal<?>>`：

```java
static class Entry extends WeakReference<ThreadLocal<?>> {
    Object value;
    Entry(ThreadLocal<?> k, Object v) {
        super(k);          // key 是弱引用
        value = v;         // value 是强引用
    }
}
```

把 **key（ThreadLocal 对象）做成弱引用**，是为了让 ThreadLocal 对象本身在外部没有强引用时能被 GC 回收。否则即使业务代码用完不再引用这个 ThreadLocal，map 里还拿着它，就永远泄漏了。

##### Q3.2.1: 既然 key 是弱引用，为什么 `ThreadLocalMap` 还会内存泄漏？

因为**弱引用只解决 key，value 是强引用**。

ThreadLocal 对象被回收后，Entry 的 key 变成 `null`，但 `value` 仍然被 Entry 强引用着。此时 Entry 成了一个 key 为 null 的「僵尸条目」。更关键的是，**线程池里的线程是长期存活的**（甚至活到进程结束），而 `ThreadLocalMap` 是挂在 `Thread` 对象上的字段——线程不死，这个 map 就不死，那些 value 也就一直可达，永远回收不了。

所以正确做法是：用完 ThreadLocal 必须调用 `remove()`。`ThreadLocalMap` 自己在 `set` / `get` / `remove` 时会顺带清理 key 为 null 的 Entry（启发式清理），但那是被动的，不能指望它及时清掉。

## 常见坑

- **说「Java 用引用计数判断对象存活」** —— HotSpot 用的是可达性分析。引用计数有循环引用的死穴，两个互相引用的对象计数不为 0 却已不可达
- **说「引用计数为 0 就回收，所以引用计数没问题」** —— 循环引用场景下计数永远不为 0，会内存泄漏
- **说「不可达对象会被立刻回收」** —— 要经过两次标记；覆盖了 `finalize()` 且没执行过的对象还会获得一次自救机会
- **说「`finalize()` 一定会被执行」** —— Finalizer 线程优先级低，GC 不保证等它执行完。`System.gc()` 也只是建议，不能保证触发
- **说「`finalize()` 会被调用多次」** —— 虚拟机保证最多一次，所以自救只有一次机会
- **说「软引用每次 GC 都会回收」** —— 软引用只在内存不足、即将 OOM 时才回收，内存充足时会保留
- **说「软引用只在抛 OOM 的那一刻才回收」** —— GC 在判断内存不足时就回收，不等真的抛出 OOM；回收时机还受 `-XX:SoftRefLRUPolicyMSPerMB` 影响
- **说「虚引用可以通过 `get()` 拿到对象」** —— `get()` 恒为 `null`，这是虚引用的定义
- **说「`ThreadLocalMap` 的 key 是强引用」** —— `Entry` 继承 `WeakReference`，key 是弱引用，value 才是强引用
- **说「弱引用对象要两次 GC 才回收」** —— 弱引用是下一次 GC 就回收，需要两次 GC 的是带 `finalize()` 的对象
- **说「GC Roots 包括被弱引用引用的对象」** —— 弱引用不构成 GC Root，否则弱引用对象永远不会被回收

## 加分点

- 能说出 JVM 会**预创建常驻异常对象**（`NullPointerException`、`OutOfMemoryError` 等）并作为 GC Roots。目的是内存耗尽时无需再分配内存就能抛出异常，避免「抛异常时又 OOM」
- 能说出 `DirectByteBuffer` 的堆外内存释放依赖 `Cleaner`，而 `sun.misc.Cleaner` / `java.lang.ref.Cleaner` 建立在 `PhantomReference` + `ReferenceQueue` 之上。这解释了「为什么虚引用这种看似没用的东西必须存在」
- 知道 `WeakHashMap`、Guava Cache 的 `weakKeys`、`ThreadLocalMap` 都是弱引用的经典应用，共同点是「附属关系」：主对象没了，附属条目就该自动消失
- 知道引用队列是「**先回收对象，再入队引用**」，所以从队列里取出的引用 `get()` 已返回 `null`——这正是无法用虚引用复活对象的原因
- 排查内存泄漏时用 `jmap -dump:live,format=b,file=heap.hprof <pid>` 或 `jcmd <pid> GC.heap_dump` 导出堆，再用 MAT 看「Path to GC Roots」，能直观看到对象是**被哪条引用链**拽住不放的。带 `live` 参数只 dump 存活对象，排除已有垃圾的干扰
- 知道大量对象实现 `finalize()` 会拖慢 GC：Finalizer 线程数量有限，F-Queue 积压会导致 GC 停顿变长，这也是 finalization 被废弃的原因之一

## 版本差异

| 版本 | 差异 |
|---|---|
| JDK 9 | `Object.finalize()` 被标记 `@Deprecated`；`java.lang.ref.Cleaner` 成为公开 API，官方推荐用它替代 finalizer 管理需要清理的资源（如堆外内存） |
| JDK 18 | JEP 421 将 finalization（终结机制）标记为 **deprecated for removal**；可用 `--finalization=disabled` 关闭它（finalize 默认仍会执行，但会给出警告）。替代方案是 try-with-resources 和 Cleaner |

## 自测题

```yaml
questions:
  - type: JUDGE
    stem: 在可达性分析中，从 GC Roots 遍历不到的对象会被立即回收。
    answer: F
    analysis: 不可达只意味着可以被回收。对象还要经过两次标记：对覆盖了 finalize() 且从未执行过的对象，会被放入 F-Queue 执行一次 finalize()，若期间重新建立引用链上的关联还能自救。finalize() 最多执行一次。

  - type: MULTI
    stem: 以下哪些可以作为 GC Roots？
    options:
      A: 虚拟机栈局部变量表中引用的对象
      B: 方法区中静态属性引用的对象
      C: 一个只被 WeakReference 引用的对象
      D: 被 synchronized 同步锁持有的对象
    answer: ABD
    analysis: C 错误。弱引用不构成 GC Roots，只被弱引用关联的对象在下一次 GC 就会被回收——若弱引用能充当 GC Root，弱引用对象就永远不会被回收。A、B、D 以及 JNI 引用、活跃线程、JVM 内部引用等都是 GC Roots。

  - type: CHOICE
    stem: 要实现一个「内存充足时保留、内存不足时自动淘汰」的缓存，应该用哪种引用？
    options:
      A: 强引用
      B: 软引用 SoftReference
      C: 弱引用 WeakReference
      D: 虚引用 PhantomReference
    answer: B
    analysis: 软引用在内存充足时不回收、内存不足（即将 OOM）时回收，正好符合缓存的需求，且 GC 会做近似 LRU 的淘汰。强引用永不回收会 OOM，弱引用下次 GC 必回收命中率太低，虚引用根本无法访问对象。

  - type: CHOICE
    stem: 关于虚引用 PhantomReference，下列说法正确的是？
    options:
      A: 可以通过 get() 取出被引用的对象
      B: 必须配合 ReferenceQueue 使用，仅用于对象被回收时的通知
      C: 内存不足时才会回收被引用的对象
      D: 适合用来做对象缓存
    answer: B
    analysis: 虚引用的 get() 永远返回 null，无法访问对象，因此 A 错误、D 错误；它不受内存是否充足影响，C 是软引用的特征。虚引用的唯一用途是回收通知，必须传入 ReferenceQueue，DirectByteBuffer 的堆外内存清理（Cleaner）就建立在它之上。
```
