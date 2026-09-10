---
slug: threadlocal-memory-leak
title: ThreadLocal 的原理是什么？为什么会内存泄漏？
module: concurrency
tags: [ThreadLocal, ThreadLocalMap, 弱引用, 内存泄漏, 线程池]
difficulty: 3
frequency: 3
related:
  - slug: gc-roots-and-references
    type: RELATED
  - slug: why-not-executors
    type: RELATED
  - slug: thread-pool-parameters
    type: RELATED
---

## 电梯版回答

ThreadLocal 自己不存数据，数据存在每个 Thread 内部的 `ThreadLocalMap` 里，用 ThreadLocal 对象本身当 key，所以同一个 ThreadLocal 在不同线程里 `get` 到的是各自独立的值，这是线程隔离而不是共享。泄漏的根因是 `ThreadLocalMap.Entry` 继承 `WeakReference<ThreadLocal<?>>`：key 是弱引用、value 是强引用。当 ThreadLocal 对象不再被强引用时 key 会被 GC 回收，Entry 变成 key 为 null 但 value 还在；如果这个线程来自线程池、会长期存活，value 就永远挂在 Thread 的 map 上回收不掉。要强调泄漏的是 value 不是 ThreadLocal，根因是线程复用。`set`、`get`、`remove` 会顺带做启发式清理，但只在被调用时触发、只扫描一部分槽位，不可靠，所以线程池场景必须在 `try` 的 `finally` 里调 `remove()`。把 key 设计成弱引用，是为了让不再使用的 ThreadLocal 至少不拖住 key 这一半。

## 展开讲解

### 数据到底存在哪：Thread 持有 ThreadLocalMap

`ThreadLocal` 类本身几乎不存状态，真正的容器挂在 `Thread` 上：

```java
public class Thread implements Runnable {
    ThreadLocal.ThreadLocalMap threadLocals = null;
    ThreadLocal.ThreadLocalMap inheritableThreadLocals = null;
}
```

`ThreadLocal.get()` 拿到当前线程，再取它的 `threadLocals` map，用 `this`（即 ThreadLocal 对象）当 key 查。第一次 `get` 时若 map 为 null，会调 `createMap` 创建；`set` 同理。所以「ThreadLocal 是每个线程一份」这句话的含义是：变量存在线程里，ThreadLocal 只是那把查找用的钥匙。

`ThreadLocalMap` 的存储结构是**开放寻址加线性探测**，和 HashMap 的「数组加链表」完全不同，没有链表结构。定位下标用：

```java
private final int threadLocalHashCode = nextHashCode();
private static final int HASH_INCREMENT = 0x61c88647;
private static AtomicInteger nextHashCode = new AtomicInteger();

int i = key.threadLocalHashCode & (table.length - 1);
```

每 new 一个 ThreadLocal，`nextHashCode` 就增加 `HASH_INCREMENT`（一个与黄金分割相关的常量），让 hash 分布更均匀，减少线性探测的冲突。key 的比较用的是 `==`，不是 `equals`——每个 ThreadLocal 都是独立对象，引用相等就是同一个 key。

### Entry：key 弱引用，value 强引用

这是整道题的核心结构：

```java
static class Entry extends WeakReference<ThreadLocal<?>> {
    Object value;

    Entry(ThreadLocal<?> k, Object v) {
        super(k);        // key 是弱引用，父类 WeakReference 持有 referent
        value = v;       // value 是强引用
    }
}
```

注意 `Entry` 本身是被谁持有的：它存在 Thread 的 `threadLocals` map 里，而 map 被 Thread 强引用。所以引用链是：

```
Thread ──强──> ThreadLocalMap ──强──> Entry ──强──> value
                                        └──弱──> ThreadLocal（key）
```

「key 弱、value 强」带来两种生命周期：

- 只要外部还有强引用指向这个 ThreadLocal（最常见的写法 `private static final ThreadLocal<T> TL`），key 就不会被回收，Entry 一直有效，value 也一直被保留——这正是我们想要的行为。
- 一旦外部对 ThreadLocal 的强引用消失（例如它只被方法内的局部引用持有，或所在类被卸载），key 就会被 GC 回收，Entry 变成 `key = null` 的「stale entry」，但 value 仍被 Entry 强引用、Entry 又被 map 强引用、map 又被 Thread 强引用。

### 泄漏链条

把上面第二条串起来就是完整的泄漏路径：

1. ThreadLocal 对象失去外部强引用，key 被 GC 回收，Entry 的 key 变成 null。
2. value 仍被这个 key 为 null 的 Entry 强引用。
3. Entry 在 Thread 的 `ThreadLocalMap` 里，而 map 被 Thread 强引用。
4. 如果线程是普通线程，线程结束后 Thread 对象和 map 一起被回收，泄漏有界。
5. 如果线程来自线程池，线程会一直存活复用，Thread 对象不回收，这条链条就一直挂着，**value 永远回收不掉**——这才是真正的泄漏。

所以准确说法是「泄漏的是 value」，而且线程池把「有界泄漏」放大成了「无界泄漏」。如果 value 又是一个较大的对象（数据库连接、大缓存、请求上下文），累积起来就可能 OOM。

### set / get / remove 的启发式清理不可靠

ThreadLocalMap 确实内置了清理机制：

- `expungeStaleEntry(int staleSlot)`：从某个槽位出发，把 key 为 null 的 Entry 的 value 置 null，并把探测序列后续的元素重新 hash 回填，避免留下空洞。
- `cleanSomeSlots(int i, int n)`：从位置 i 开始，按「大约 log2(n) 次」的节奏抽查若干槽位，遇到 stale entry 就调 `expungeStaleEntry`，是启发式扫描。
- `replaceStaleEntry`：`set` 过程中遇到 stale entry 时会尝试替换并顺带向后清理。
- `remove()`：删掉当前 key 对应的 Entry，并调用 `expungeStaleEntry` 清理一段。

不可靠的地方在触发条件和范围：

- 只有调用 `set` / `get` / `remove` 时才可能触发，如果某个线程此后不再碰任何 ThreadLocal，stale entry 就一直留着。
- `cleanSomeSlots` 是概率式抽查，不保证扫到所有 stale entry。
- 没有全表扫描的清理入口，指望「用着用着就自动清干净」不成立。

### 正确用法：try / finally remove

线程池场景的标准写法：

```java
private static final ThreadLocal<RequestContext> CONTEXT = new ThreadLocal<>();

public void handle(Request request) {
    CONTEXT.set(new RequestContext(request));
    try {
        doHandle();
    } finally {
        CONTEXT.remove();   // 归还线程前必须清掉，否则 value 随线程存活
    }
}
```

`remove()` 既删除当前 key 的 Entry，也顺带清理探测序列上的 stale entry，是唯一可靠的收尾手段。注意 `remove` 必须放在 `finally`，否则业务抛异常时一定漏清。

一个容易被忽略的点：把 ThreadLocal 声明成 `static final` 能避免「null key」这种泄漏，因为 key 始终被静态字段强引用、不会被回收；但 value 仍会随线程存活，如果它是请求级的大对象，在线程池里不 remove 照样会让上一个请求的对象被下一个请求无意间读到并一直挂着。在 Web 容器热部署场景，还可能出现 `Thread → ThreadLocalMap → Entry → ThreadLocal → Class → ClassLoader` 的引用链，导致旧的 ClassLoader 无法卸载，这是另一类更隐蔽的泄漏。

### InheritableThreadLocal 为什么在线程池里失效

`InheritableThreadLocal` 的值复制发生在**线程创建时**，不是任务提交时：

```java
// Thread 构造方法中的逻辑
if (parent.inheritableThreadLocals != null) {
    this.inheritableThreadLocals =
        ThreadLocal.createInheritedMap(parent.inheritableThreadLocals);
}
```

新线程构造时把父线程的 `inheritableThreadLocals` 复制一份。线程池的线程只在池子初始化时创建一次，之后所有任务都复用它，所以：

- 池子创建之后父线程再 set 的值，工作线程看不到。
- 第一个任务在线程上设置的 ThreadLocal 值会被后续任务继承到，造成串味。

`TransmittableThreadLocal`（阿里开源的 TTL）解决思路是：不依赖线程创建时刻的复制，而是在**任务提交时**抓取提交线程的 TTL 快照，在**任务执行前**把这个快照设到工作线程上，执行完再恢复现场（把工作线程原来的值还原），从而既支持线程池传递、又不污染复用线程。落地方式通常是 `TtlExecutors.getTtlExecutorService(pool)` 包装线程池，或用 Java agent 对线程池做增强。

## 追问链

### Q1: ThreadLocal 的数据到底存在哪？为什么每个线程拿到的不一样？

数据存在 `Thread` 对象里的 `ThreadLocalMap` 字段，ThreadLocal 对象只是 map 的 key。`get()` 先拿 `Thread.currentThread()`，再取它的 `threadLocals`，用 `this` 当 key 去查；每个线程各有一个 map，所以同一个 ThreadLocal 在不同线程查出来的是各自的 value。这也是为什么 ThreadLocal 不需要任何同步——它访问的是只有本线程能碰到的那份数据，线程隔离本身就是它避免竞争的方式。

#### Q1.1: 那 Entry 的 key 为什么用弱引用？

为了让「不再被使用的 ThreadLocal」至少不会因为 map 而永远无法回收。map 被 Thread 强引用，如果 Entry 对 key 也是强引用，那么只要线程活着，ThreadLocal 对象就永远被 map 拽住，哪怕业务代码早已不再引用它——连 key 都泄漏了。改成弱引用后，外部强引用一消失，GC 就能回收 ThreadLocal 对象；同时 Entry 变成 key 为 null 的 stale entry，给了后续 `set` / `get` / `remove` 一个可供识别的清理标记。这是「两害相权取其轻」的设计：key 弱引用无法阻止 value 泄漏，但至少把泄漏范围缩小了一半，并留下清理线索。

##### Q1.1.1: 既然 key 是弱引用，为什么 value 不用弱引用？

因为 value 必须活到「ThreadLocal 还在被使用」的整个期间。如果 value 也是弱引用，只要没有别的强引用，GC 随时可能把 value 回收，那 `get()` 就可能拿到 null，ThreadLocal 就失去了「线程内长期保存一份数据」的核心语义——比如缓存一个 `SimpleDateFormat`、保存一次请求的上下文，这些本来就要在线程存活期间一直可用。所以 key 用弱引用是为了「不阻碍回收」，value 用强引用是为了「保证可用」，两者的诉求相反，只能这样分工。代价就是必须由使用方用 `remove()` 主动收尾。

### Q2: 那泄漏究竟是怎么发生的？

把引用链和线程生命周期串起来：ThreadLocal 失去外部强引用后，key 被 GC 回收，Entry 成了 key 为 null 但 value 仍被强引用的 stale entry；Entry 挂在 Thread 的 `ThreadLocalMap` 上，map 又被 Thread 强引用。线程池里的线程创建后长期存活、反复复用，Thread 对象不回收，于是这个 value 也跟着一直不回收。每来一个曾经用过 ThreadLocal 的「一次性」实例（比如方法里 new 出来的、或热部署后旧 ClassLoader 里的），就多挂一个 value，最终可能导致内存持续增长甚至 OOM。

#### Q2.1: set / get 不是会清理吗，为什么还泄漏？

因为清理是启发式、被动、局部的。`expungeStaleEntry` 和 `cleanSomeSlots` 只在 `set` / `get` / `remove` 被调用时才被触发，`cleanSomeSlots` 按 log2(n) 的量级抽查槽位而不是全表扫描，没有专门的定时清理线程。只要一个线程此后不再调用任何 ThreadLocal 方法（很常见：这个线程的后续任务都不用它），或者 stale entry 正好落在抽查范围之外，它就会一直留着。所以清理机制只能降低概率，不能当作正确性保证，真正的保证只能来自显式的 `remove()`。

##### Q2.1.1: 那是不是把 ThreadLocal 定义成 static final 就绝对安全？

不是。`static final` 只保证 key 始终被静态字段强引用、不会被回收，因此不会产生 key 为 null 的 stale entry。但 value 仍被 map 强引用、map 又被线程强引用，线程不死 value 就不走。在线程池里，如果 value 是请求级的大对象且不 remove，上一个请求的对象会一直挂在线程上，还可能被下一个碰巧用同一 key 的任务读到——既是内存问题也是串数据问题。更隐蔽的是 Web 容器热部署：`Thread → ThreadLocalMap → Entry → ThreadLocal → Class → ClassLoader` 这条链会让旧应用的 ClassLoader 无法卸载，属于另一类泄漏。所以 static final 是良好实践，但不能替代 `finally` 里的 `remove()`。

### Q3: InheritableThreadLocal 在线程池里为什么失效？

因为它的传递发生在 Thread 的**构造方法**里：新线程创建时把父线程的 `inheritableThreadLocals` 复制一份。线程池的线程只在池子初始化（或扩容）时创建，之后任务都是复用这些线程，创建时刻早就过去了。结果有两个：池子创建之后父线程再 set 的值传不进去；某个任务在工作线程上留下的值反而会被后续任务继承，造成数据串味。所以它只能用于「亲手 `new Thread` 并当场启动」的场景。

#### Q3.1: TransmittableThreadLocal 怎么解决？

它把传递时机从「线程创建时」改到「任务提交与执行时」。TTL 会维护所有可传递的 ThreadLocal 实例，配合 `TtlExecutors.getTtlExecutorService(pool)` 包装线程池（或用 Java agent 增强）：任务提交时抓取提交线程当前的 TTL 快照，工作线程执行任务前把快照 set 上去，任务执行完再把工作线程原来的值恢复回去。这样既能跨线程池传递，又不会让一个任务的值污染后续任务。它继承自 `InheritableThreadLocal`，所以 `new Thread` 的普通场景也照样兼容。

##### Q3.1.1: 它和普通 ThreadLocal 的清理方式有什么不同？

普通 ThreadLocal 的清理责任完全在使用方，必须自己 `try-finally remove()`，否则线程池里就泄漏。TTL 的包装层在任务边界自动做「设置—执行—恢复」：任务开始前用快照覆盖工作线程的当前值，任务结束后恢复任务执行前的现场，于是上一次任务设置的值不会残留到下一次。换句话说，普通 ThreadLocal 靠使用方在每个使用点清理，TTL 靠线程池包装层在任务粒度上自动收口。但要注意 TTL 需要业务代码统一走包装后的 ExecutorService，漏了包装（比如直接提交到未包装的池子）就仍然会退化。

## 常见坑

- **「ThreadLocal 会导致内存泄漏，所以要尽量少用」** —— 不准确。泄漏的是 value，而且只在「线程长期存活加不 remove」时发生；根因是线程池的线程复用，不是 ThreadLocal 本身的缺陷。用对方式它是很正常的工具
- **「key 是强引用所以 ThreadLocal 对象回收不掉」** —— 说反了。key 是弱引用，真正强引用的是 value；key 反而会被回收，留下 key 为 null 的 stale entry
- **「key 被回收后整个 Entry 就从 map 里没了」** —— Entry 对象还在 table 里，只是 key 变成 null，value 仍被它强引用，要等后续清理才可能被移除
- **「调用 remove() 会立刻清掉整张表里所有 stale entry」** —— remove 只删当前 key 的 Entry 并顺带 expunge 一段探测序列，不是全表扫描
- **「set / get 会自动清理，不需要手动 remove」** —— 清理是启发式、被动、局部的，只在方法被调用且抽查命中的时候发生，不能作为正确性保证
- **「static final 的 ThreadLocal 绝对不会泄漏」** —— 不会有 null key，但 value 仍随线程存活；热部署场景还可能沿 `Thread → ThreadLocal → Class → ClassLoader` 造成 ClassLoader 泄漏
- **「InheritableThreadLocal 在线程池里也能可靠传递」** —— 它只在线程创建时复制，线程池线程创建后就固定了，之后提交的任务不会重新拷贝
- **「ThreadLocal 是用来在多线程间共享数据的」** —— 恰好相反，它是线程隔离，用来让每个线程各有一份数据；共享数据要用别的机制
- **「ThreadLocalMap 和 HashMap 一样是数组加链表」** —— ThreadLocalMap 是开放寻址加线性探测，没有链表；key 比较用 `==` 而不是 `equals`
- **「一个 ThreadLocal 在 map 里就是一个固定下标」** —— 下标由 `threadLocalHashCode & (len - 1)` 算出来后用线性探测找空位，遇到冲突会往后探测，不是固定位置

## 加分点

- 能说出 `threadLocalHashCode` 每次递增 `HASH_INCREMENT = 0x61c88647`，这个常量与黄金分割相关，目的是让 hash 均匀分布、减少线性探测冲突；`nextHashCode` 是一个 `AtomicInteger`
- 能解释 `expungeStaleEntry` 除了清 value，还会把探测序列后续的元素 rehash 回填，避免线性探测链上留下空洞导致查找断裂；这是开放寻址清理的关键细节
- 知道 `cleanSomeSlots` 用 `n >>>= 1` 的方式做 log2 量级的启发式抽查，所以叫 heuristic clean，而不是精确清理
- 提到 MDC（日志上下文）、事务上下文、请求上下文这些常见用法都靠 ThreadLocal，在线程池异步化时要么手动透传要么用 TTL，否则日志会串请求
- 知道 Netty 的 `FastThreadLocal`：用数组下标代替 hash 探测，配 `FastThreadLocalThread`，读写更快且没有 stale entry 问题，`remove` 时直接把槽位置为 `UNSET`——能对比说明 ThreadLocalMap 开放寻址的探测成本
- 提到 Java 21 虚拟线程仍然支持 ThreadLocal，但虚拟线程数量可能极多，每个都带一张 map 会明显放大内存；官方因此建议用 `ScopedValue`（Java 21 起预览）来表达请求级上下文，它不可变、随作用域自动失效、也不依赖线程
- 能区分「内存泄漏」和「内存占用」：static final 的 ThreadLocal 在线程池里长期持有 value，是设计上的长期占用，但如果 value 是请求级对象且不清理，就变成了跨请求的错误持有，两者性质不同
- 提到用 `jmap` 加 `jhat` 或 MAT 分析堆转储时，这类泄漏的典型特征是大量 `ThreadLocalMap$Entry`、key 为 null 的 Entry，或大量本该随请求结束的对象被 `Thread` 的 GC Root 引用

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 1.2 | `ThreadLocal` 加入 `java.lang`，提供线程局部变量；`InheritableThreadLocal` 同期提供父线程到子线程的继承 |
| Java 8 | 新增 `ThreadLocal.withInitial(Supplier)` 工厂方法，省去继承 ThreadLocal 重写 initialValue 的样板代码 |
| Java 21 | 虚拟线程支持 ThreadLocal，但虚拟线程数量可能极大，官方建议用 `ScopedValue`（Java 21 起以预览形式提供）传递请求级上下文，它随作用域自动失效、不依赖线程 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: ThreadLocalMap 的 Entry 中，key 和 value 分别持有什么类型的引用？
    options:
      A: key 是弱引用、value 是强引用
      B: key 是强引用、value 是弱引用
      C: key 和 value 都是弱引用
      D: key 和 value 都是强引用
    answer: A
    analysis: Entry 继承 WeakReference<ThreadLocal<?>>，构造时 super(k) 让 key 成为弱引用，value 字段则是普通强引用。这样 ThreadLocal 对象本身可以被回收，但也留下了 key 为 null、value 仍被强引用的 stale entry，需要 remove 收尾。
    difficulty: 2

  - type: MULTI
    stem: 关于线程池场景下 ThreadLocal 的清理，下列说法正确的有？
    options:
      A: 应该在 try 的 finally 里调用 remove()
      B: set / get 触发的启发式清理足以保证不再泄漏
      C: 泄漏的根因之一是线程池线程长期存活、被反复复用
      D: 把 ThreadLocal 声明成 static final 就完全不需要 remove
    answer: AC
    analysis: B 错误，expungeStaleEntry 和 cleanSomeSlots 只在 set / get / remove 时被顺带触发，且只扫描探测序列上的部分槽位，线程若不再调用这些方法，stale entry 会一直留着。D 错误，static final 只是让 key 不会被回收，value 仍会随线程存活，请求级的大对象照样会一直挂在 map 上。
    difficulty: 3

  - type: JUDGE
    stem: ThreadLocal 的 key 被 GC 回收后，对应的 Entry 和 value 会立即从 ThreadLocalMap 中移除。
    answer: F
    analysis: Entry 对象仍留在 table 里，只是 key 变成 null，value 仍被 Entry 强引用。只有后续 set / get / remove 触发 expungeStaleEntry 这类清理时才可能被移除，而清理是启发式的，不保证发生。
    difficulty: 2

  - type: CLOZE
    stem: |
      补全 Entry 的弱引用构造，以及线程池中的正确收尾调用：
      ```java
      static class Entry extends WeakReference<ThreadLocal<?>> {
          Object value;

          Entry(ThreadLocal<?> k, Object v) {
              super({{1}});          // 让 ThreadLocal 对象成为弱引用的 referent
              this.value = v;        // value 是强引用
          }
      }

      // 线程池中的用法
      try {
          threadLocal.set(ctx);
          // ... 业务逻辑
      } finally {
          threadLocal.{{2}}();       // 显式清理，避免 value 随线程存活
      }
      ```
    blanks:
      - ["k", "key"]
      - ["remove"]
    analysis: super(k) 把 ThreadLocal 对象本身作为弱引用的 referent，所以 key 会被 GC 回收而 value 不会，这就是 stale entry 的成因。线程池里必须在 finally 中调用 remove()，它删除当前 Entry 并顺带 expungeStaleEntry 清理探测序列，是唯一可靠的释放手段。
    difficulty: 3
````
