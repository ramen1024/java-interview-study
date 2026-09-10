---
slug: virtual-threads
title: 虚拟线程是怎么回事？能替代线程池吗？
module: concurrency
tags: [虚拟线程, Java 21, Loom, 载体线程, Continuation]
difficulty: 3
frequency: 3
related:
  - slug: thread-pool-parameters
    type: CONTRAST
  - slug: java-17-21-features
    type: PREREQUISITE
  - slug: completablefuture
    type: RELATED
---

## 电梯版回答

虚拟线程是 Java 21 正式引入（JEP 444）的轻量级线程，由 JVM 而不是操作系统调度，底层是 Continuation 上的用户态任务，运行时挂载到少量平台线程上执行，这些被借用的平台线程叫载体线程（carrier）。它最大的价值是遇到阻塞时能把整个栈卸载下来、让出载体线程，等条件就绪再挂载回去，于是少量载体线程就能支撑海量并发任务。三条结论要记牢：第一，不能池化虚拟线程，它创建和切换成本极低，正确用法是每个任务一个，用 `Executors.newVirtualThreadPerTaskExecutor()`；第二，它只对 IO 密集有收益，CPU 密集任务用了反而多一层调度开销；第三，它不会自动帮你控制下游并发，数据库连接这类稀缺资源必须用 `Semaphore` 显式限流，否则连接池会被瞬间打爆。另外 `ThreadLocal` 在虚拟线程下数量会爆炸，长期方向是 `ScopedValue`。

## 展开讲解

### 平台线程与虚拟线程的区别

| 维度 | 平台线程 | 虚拟线程 |
|---|---|---|
| 调度者 | 操作系统内核 | JVM，调度器是一个专用的 `ForkJoinPool` |
| 执行载体 | 就是它自己 | 挂载到载体线程（平台线程）上运行 |
| 栈内存 | 默认 MB 上下，由 `-Xss` 决定，且受 OS 线程数限制 | 起始很小、按需增长，栈内容存放在堆上，可创建百万级 |
| 阻塞代价 | 占住一个 OS 线程，直到阻塞结束 | 卸载栈、让出载体线程 |
| 适用场景 | CPU 密集，或需要精细控制的场景 | 高并发 IO 密集 |
| 是否池化 | 通常要池化，创建成本高 | 不要池化，每个任务一个 |
| 优先级 | 可设置 | 恒为默认优先级，`setPriority` 无效 |
| 守护状态 | 可设置 | 恒为守护线程 |

平台线程和虚拟线程是同一个 `Thread` 类的两种实现，用 `Thread.ofVirtual()` 和
`Thread.ofPlatform()` 两个工厂区分，运行期可以用 `Thread.currentThread().isVirtual()`
判断当前是不是虚拟线程。

### 载体线程与挂载 / 卸载

虚拟线程不是一个「更轻的内核线程」，它是一段可以在任意载体线程上继续执行的
**任务**。多个虚拟线程复用同一个载体线程，但同一时刻一个载体线程只运行一个虚拟线程。
挂载（mount）是把虚拟线程的栈恢复到某个载体线程上开始执行，卸载（unmount）是把它的
栈从载体线程上取下来、存回堆里。

```java
// 伪代码：虚拟线程内部 park 时的大致路径
void park() {
    if (!canUnmount()) {
        // 处于 synchronized 监视器或 native 帧里，无法卸载，
        // 只能连同载体线程一起阻塞，这就是 pinning
        LockSupport.park();
        return;
    }
    Continuation.yield(VTHREAD_SCOPE);  // 保存栈、让出载体线程
}
```

关键在 `canUnmount()` 这个判断。栈能被安全地取下来，是因为虚拟线程的栈帧不依赖
固定的 OS 线程栈：挂载时把栈块（StackChunk）拷到载体线程的栈上继续跑，卸载时再拷回堆。
这也是它和「把任务丢进线程池」的本质差别——线程池的任务在阻塞时会一直占着那个线程。

### 创建与使用

```java
// 推荐：每个任务一个虚拟线程的 ExecutorService
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (Task task : tasks) {
        executor.submit(task::run);
    }
}   // close() 会等待所有任务结束

// 需要自定义线程名或其他属性时，用 ThreadFactory
ThreadFactory factory = Thread.ofVirtual()
    .name("downstream-call-", 0)
    .factory();
ExecutorService executor = Executors.newThreadPerTaskExecutor(factory);

// 也可以直接创建
Thread vThread = Thread.ofVirtual()
    .name("fetch-order")
    .start(() -> callRemote());
```

注意 `Executors.newVirtualThreadPerTaskExecutor()` 的语义是**每次 submit 都新建一个
虚拟线程**，而不是从一个池里借。它返回的 `ExecutorService` 在 Java 21 起支持
try-with-resources，`close()` 会先 shutdown 再等待任务全部结束。

### 为什么不能池化虚拟线程

池化的前提是「创建 / 销毁成本高，所以复用」。虚拟线程的创建成本极低，栈按需增长，
一个虚拟线程对象的开销很小，池化省下的那点开销还抵不过池本身的队列和同步开销。
更严重的是，池化会**限制并发度**：池化成 200 个虚拟线程，就相当于把并发上限压回 200，
把虚拟线程最大的优势亲手抹掉了。

所以正确的组合是「每个任务一个虚拟线程 + 用信号量限制真正稀缺的资源」，
而不是「限制虚拟线程数量」：

```java
private static final Semaphore DB_PERMITS = new Semaphore(50);

void handle() throws InterruptedException {
    DB_PERMITS.acquire();          // 限制同时打向数据库的请求数
    try {
        jdbcTemplate.query(SQL, rowMapper);
    } finally {
        DB_PERMITS.release();
    }
}
```

数据库连接池、Redis 连接、第三方接口的并发配额都是**有限的、有代价的**资源，
虚拟线程本身不是。把限制加在虚拟线程数量上是错位的，加在信号量上才是对的。

### synchronized 会 pin 住载体线程

JDK 21 到 JDK 23 里，虚拟线程在 `synchronized` 块或 `synchronized` 方法里阻塞
（包括 `Object.wait()`），无法卸载，只能连同载体线程一起阻塞，这叫 pinning。
被 pin 住的载体线程没法服务其他虚拟线程，并发度就退化了。

```java
// JDK 21~23：这里 sleep 会 pin 住载体线程
synchronized (lock) {
    Thread.sleep(1000);
}
```

官方的建议是在虚拟线程里用 `ReentrantLock` 代替 `synchronized`：

```java
private final ReentrantLock lock = new ReentrantLock();

void safe() throws InterruptedException {
    lock.lockInterruptibly();
    try {
        Thread.sleep(1000);   // 阻塞时正常卸载，不 pin 载体线程
    } finally {
        lock.unlock();
    }
}
```

必须写清版本：**JDK 24 的 JEP 491 已经解决了监视器导致的 pinning**，虚拟线程获取、
持有、释放 monitor 不再绑定载体线程，`synchronized` 基本不再是必须避开的写法。
但在 JDK 21 这条 LTS 线上，上面这段建议依然是硬性要求。另外，native 方法 /
外部函数调用期间的阻塞**仍会 pin 住载体线程**，这一条至今没变。

### ThreadLocal 会数量爆炸

`ThreadLocal` 的数据是挂在**线程对象**上的 `ThreadLocalMap`。线程池时代线程数量有限，
用 `ThreadLocal` 缓存 `SimpleDateFormat` 这类重对象是划算的；虚拟线程时代每个任务
一个新线程，缓存不会被复用，而且每个虚拟线程都带一份自己的 `ThreadLocalMap`，
数量随线程数一起膨胀。

```java
// 线程池时代可行，虚拟线程时代不合适
private static final ThreadLocal<SimpleDateFormat> FMT =
    ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));
```

替代方向是 `ScopedValue`：它把值绑定在**一段代码的作用域**上，而不是绑定在线程上，
天然适合「一次请求内共享、请求结束就失效」的场景。

```java
static final ScopedValue<User> CURRENT_USER = ScopedValue.newInstance();

ScopedValue.where(CURRENT_USER, user)
    .run(() -> handleRequest());
```

## 追问链

### Q1: 虚拟线程和平台线程的本质区别是什么？

不是「更快的线程」，而是**调度层次不同**。平台线程由 OS 调度，一个线程对应一个内核
调度实体；虚拟线程由 JVM 调度，运行在载体线程上，多个虚拟线程分时复用少量载体线程。
它真正节省的是「阻塞时占住的那条 OS 线程」，而不是让 CPU 算得更快。

#### Q1.1: 那它阻塞时是怎么把载体线程让出来的？

靠 `Continuation.yield()`。JVM 在挂载点上把虚拟线程的栈帧从载体线程的栈里拷贝到堆上
（StackChunk），然后让载体线程去执行下一个可运行的虚拟线程；等阻塞条件满足，
再把栈拷贝回某个载体线程继续执行。整个过程是「保存栈 → 让出 → 恢复栈」，
栈存放在堆里是它能被随时取下来的前提。

##### Q1.1.1: 所有阻塞都能这样卸载吗？

不是。能否卸载取决于当前栈里有没有 JVM 认为不可中断的帧。JDK 21 里最典型的是
`synchronized` 监视器帧，其次是 native 方法 / 外部函数调用帧，这两种情况只能 pin 住
载体线程一起阻塞。普通的 `Thread.sleep`、`Object.wait`（不在 synchronized 里时）、
网络 IO 都能正常卸载。JDK 24 的 JEP 491 解决了 monitor 这一类，native 帧仍然会 pin。

#### Q1.2: 那能不能把虚拟线程池化，像线程池一样复用？

不能，也没有意义。池化是为了摊薄创建成本，而虚拟线程创建成本极低、可以按任务现建现用；
把虚拟线程放进固定大小的池，等于人为把并发上限锁死，反而失去了它的价值。正确做法是
用 `Executors.newVirtualThreadPerTaskExecutor()`，每个任务一个虚拟线程。

##### Q1.2.1: 既然不能池化，那下游的数据库连接怎么控制并发？

用 `Semaphore` 显式限流，而不是限制虚拟线程数量。连接的许可证数量和连接池配置对齐，
每个用到数据库的任务先 `acquire()` 再在 `finally` 里 `release()`。这样无论上层有多少
虚拟线程在并发，真正同时打向数据库的请求数都被压在连接池能承受的范围内。

### Q2: 虚拟线程适合什么样的任务？

IO 密集。它的收益模型是「等待时不占线程」，任务里等待的比例越高，收益越大。
典型的 Web 后端场景——一次请求要串行调用几个下游——用同步写法就能获得异步的并发度，
不必再把代码拆成回调或 `CompletableFuture` 链。

#### Q2.1: 那 CPU 密集任务用虚拟线程会怎样？

不仅没有收益，通常还更慢。CPU 密集型任务很少主动阻塞，虚拟线程大部分时间都在
载体线程上真跑，而载体线程数量仍受 CPU 核数限制，所以总吞吐上不去；额外多出来的
挂载、卸载和调度开销却是净支出。CPU 密集任务应该用 `Thread.ofPlatform()` 或平台线程池，
把线程数控制在核数附近。

#### Q2.2: 既然虚拟线程挂载在 ForkJoinPool 上，是不是要注意池的并行度？

载体线程调度器是一个专用的 `ForkJoinPool`，默认并行度等于可用处理器数，可以用系统属性
`jdk.virtualThreadScheduler.parallelism` 之类的开关调整。要特别注意它**不是**
`ForkJoinPool.commonPool()`，两者是分开的，所以把 `CompletableFuture` 默认丢给
commonPool 的代码并不会自动跑在虚拟线程上。

##### Q2.2.1: 那怎么把虚拟线程和 CompletableFuture 结合？

给异步任务显式传执行器，比如 `CompletableFuture.supplyAsync(task, virtualExecutor)`，
其中 `virtualExecutor` 是 `ExecutorService` 适配成的 `Executor`。不传执行器时用的是
commonPool，里面是平台线程，拿不到虚拟线程的收益。

## 常见坑

- **「虚拟线程可以池化复用」** —— 池化对虚拟线程没有意义，还会人为锁死并发上限。
  正确用法是每个任务一个
- **「虚拟线程是更快的线程，CPU 密集也更快」** —— 收益只来自阻塞时让出载体线程，
  纯计算任务没有收益，还多一层调度开销
- **「虚拟线程由操作系统调度」** —— 由 JVM 调度，运行在载体线程（平台线程）上，OS 调度的
  是载体线程
- **「JDK 21 的虚拟线程在任何阻塞下都能卸载」** —— `synchronized` 里阻塞会 pin 住载体线程，
  直到 JDK 24 的 JEP 491 才解决
- **「用了虚拟线程就不用管数据库连接了」** —— 虚拟线程太便宜，会毫无节制地打满连接池，
  必须用 `Semaphore` 限制下游并发
- **「虚拟线程下 ThreadLocal 完全不能用」** —— 能用，但不要用它缓存重对象或做对象池，
  `ThreadLocalMap` 会随虚拟线程数量一起膨胀，应改用 `ScopedValue`
- **「虚拟线程就是把线程池的线程数调大」** —— 两者的调度层次和阻塞语义都不同，
  虚拟线程不需要池，也不能通过调大池参数来模拟

## 加分点

- 能说出载体线程调度器是 work-stealing 的 `ForkJoinPool`，且与 `ForkJoinPool.commonPool()`
  是两个池，因此 `CompletableFuture` 默认执行器与虚拟线程无关
- 知道虚拟线程的栈存放在堆上（StackChunk），挂载 / 卸载本质是栈块在原生栈与堆之间的拷贝，
  这解释了为什么它能被随时「取下来」
- 知道 JFR 里有 `jdk.VirtualThreadStart` / `jdk.VirtualThreadEnd` /
  `jdk.VirtualThreadPinned` / `jdk.VirtualThreadSubmitFailed` 事件，pinning 可以用 JFR 观测；
  JDK 21~23 还可以用 `jdk.tracePinnedThreads` 打印被 pin 的栈
- 提到「同步的写法、异步的性能」：虚拟线程让阻塞式代码重新变得可用，
  很多为了异步而引入的回调地狱、`CompletableFuture` 链，在高并发 IO 场景下可以退回去写
  直白的同步代码
- 知道虚拟线程恒为守护线程、`setPriority` 无效，因此不要再写依赖线程优先级调度的代码
- 提到结构化并发（Structured Concurrency）是配套方向：把一组子任务的生命周期约束在
  一个作用域里，避免虚拟线程泄漏；它在 Java 21 是预览特性
- 能给出选型结论：IO 密集高并发用虚拟线程并配信号量限流；CPU 密集仍用固定大小的
  平台线程池；两者可以在同一个应用里共存，各取所长

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 19 | JEP 425：虚拟线程作为预览特性首次亮相，`Thread.ofVirtual()` 等 API 还不可用 |
| Java 20 | JEP 436：第二次预览，API 继续调整 |
| Java 21 | JEP 444：虚拟线程正式转正，提供 `Thread.ofVirtual()` / `Thread.ofPlatform()`、`Thread.isVirtual()`、`Executors.newVirtualThreadPerTaskExecutor()`；此时虚拟线程阻塞在 `synchronized` 监视器上会 pin 住载体线程；`ScopedValue` 以预览形式出现（JEP 446），结构化并发也是预览（JEP 453） |
| Java 24 | JEP 491：虚拟线程获取、持有、释放 monitor 不再绑定载体线程，`synchronized` 基本不再造成 pinning；native 帧导致的 pinning 仍然存在 |
| Java 25 | `ScopedValue` 转正（JEP 506），成为替代 `ThreadLocal` 的正式方案 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 虚拟线程在阻塞时把载体线程让出来的底层机制是什么？
    options:
      A: 操作系统重新分配内核线程
      B: Continuation 的 yield，把栈保存到堆上后让出载体线程
      C: 垃圾回收器回收被阻塞的线程
      D: 交给 ForkJoinPool.commonPool 处理
    answer: B
    analysis: 虚拟线程阻塞时通过 Continuation.yield 把栈帧拷贝到堆上（StackChunk），让出载体线程去执行其他虚拟线程；条件就绪后再挂载回来。载体线程仍是平台线程，由 OS 调度的是载体线程而非虚拟线程本身。
    difficulty: 2

  - type: MULTI
    stem: 以下关于虚拟线程的说法正确的有？
    options:
      A: 虚拟线程不应该池化，推荐每个任务一个
      B: 虚拟线程适合 IO 密集任务
      C: 虚拟线程调度器的底层是一个 ForkJoinPool，默认并行度等于可用处理器数
      D: 只要用了虚拟线程，就不需要再限制数据库连接的并发
    answer: ABC
    analysis: D 错误。虚拟线程创建成本极低，会把大量请求同时压向下游，必须用 Semaphore 显式限制数据库连接等稀缺资源的并发。C 正确，且该 ForkJoinPool 与 commonPool 不是同一个池。
    difficulty: 2

  - type: JUDGE
    stem: 虚拟线程由操作系统调度，因此它的创建成本接近平台线程。
    answer: F
    analysis: 虚拟线程由 JVM 调度，运行在被复用的载体线程上，创建成本极低，栈按需增长且存放在堆上。由操作系统调度的是载体线程，不是虚拟线程。
    difficulty: 1

  - type: CLOZE
    stem: |
      补全创建「每个任务一个虚拟线程」的执行器，以及直接创建虚拟线程的工厂方法：
      ```java
      // 每个任务一个虚拟线程，不要池化
      try (ExecutorService executor = Executors.{{1}}()) {
          executor.submit(() -> callRemote());
      }

      // 直接创建虚拟线程；也可以用 Thread.ofPlatform() 创建平台线程
      Thread vThread = Thread.{{2}}()
          .name("fetch-order")
          .start(() -> callRemote());
      ```
    blanks:
      - ["newVirtualThreadPerTaskExecutor"]
      - ["ofVirtual"]
    analysis: newVirtualThreadPerTaskExecutor 每次 submit 都新建一个虚拟线程而不是从池里借，返回值支持 try-with-resources，close() 会等待所有任务结束。Thread.ofVirtual() 返回虚拟线程构建器，Thread.ofPlatform() 返回平台线程构建器。
    difficulty: 2
````
