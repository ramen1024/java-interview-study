---
slug: why-not-executors
title: 为什么《阿里巴巴 Java 开发手册》禁止用 Executors 创建线程池？
module: concurrency
tags: [线程池, Executors, OOM, 阿里规约]
difficulty: 2
frequency: 3
related:
  - slug: thread-pool-parameters
    type: PREREQUISITE
---

## 电梯版回答

因为 Executors 的几个工厂方法都会创建出「无界」的线程池，资源耗尽的路径是敞开的。newFixedThreadPool 和 newSingleThreadExecutor 用的是容量为 Integer.MAX_VALUE 的 LinkedBlockingQueue，任务积压时队列无限增长直到 OOM；newCachedThreadPool 和 newScheduledThreadPool 把 maximumPoolSize 设成 Integer.MAX_VALUE，任务来得快时会无限制创建线程，直到无法创建新线程而 OOM。规范的意图是让你显式传七个参数，用有界队列加明确的拒绝策略，把资源上限写死在代码里。

## 展开讲解

### 四个工厂方法的真实参数

```java
// ① 无界队列：maximumPoolSize 永远不会生效
public static ExecutorService newFixedThreadPool(int nThreads) {
    return new ThreadPoolExecutor(
        nThreads, nThreads, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<Runnable>()     // 默认容量 Integer.MAX_VALUE
    );
}

// ② 无界队列，且只有一条线程
public static ExecutorService newSingleThreadExecutor() {
    return new FinalizableDelegatedExecutorService(new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<Runnable>()     // 同样是无界
    ));
}

// ③ 线程数无上限：maximumPoolSize = Integer.MAX_VALUE
public static ExecutorService newCachedThreadPool() {
    return new ThreadPoolExecutor(
        0, Integer.MAX_VALUE,
        60L, TimeUnit.SECONDS,
        new SynchronousQueue<Runnable>()        // 不存储，直接移交
    );
}

// ④ 线程数无上限
public static ScheduledExecutorService newScheduledThreadPool(int corePoolSize) {
    return new ScheduledThreadPoolExecutor(corePoolSize);
    // 内部 super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS,
    //           new DelayedWorkQueue());
}
```

关键点：**`new LinkedBlockingQueue()` 的无参构造容量是
`Integer.MAX_VALUE`，不是有界队列**。很多人看到 `LinkedBlockingQueue`
就以为它是有界的，这是误解的来源。

### 两条资源耗尽路径

**路径一：队列无限增长（①②）**

```
任务提交速率 > 消费速率
→ 线程数停在 corePoolSize（队列无界，永远走不到创建非核心线程的分支）
→ 队列持续膨胀
→ 队列里的 Runnable 对象 + 其捕获的闭包对象占用堆
→ OutOfMemoryError: Java heap space
```

注意症状：**线程数是正常的，堆内却堆满了排队任务**。
排查时看到 `LinkedBlockingQueue` 持有几百万个 `FutureTask`
就是这个问题。

**路径二：线程数无限增长（③④）**

```
每个任务耗时较长，或提交速率极高
→ SynchronousQueue.offer 失败（当前没有空闲线程接手）
→ 创建新线程
→ 线程数持续增长
→ 每个线程默认 1MB 栈空间
→ OutOfMemoryError: unable to create native thread
```

一万条线程就是约 10GB 虚拟内存。报错信息是
`unable to create native thread`，而且**这个错误不带堆栈**，
不能靠日志定位，得从线程数入手看。

### 正确写法：显式七个参数

```java
ThreadPoolExecutor executor = new ThreadPoolExecutor(
    8,                                              // 核心线程数
    16,                                             // 最大线程数
    60L, TimeUnit.SECONDS,                          // 空闲存活
    new ArrayBlockingQueue<>(200),                  // ★ 有界队列
    new CustomThreadFactory("order-async"),         // ★ 有业务含义的线程名
    new ThreadPoolExecutor.CallerRunsPolicy()       // ★ 明确的拒绝策略
);
```

三个关键点环环相扣：

1. **有界队列**让 `maximumPoolSize` 真正生效——
   队列满了才会创建非核心线程
2. **明确的拒绝策略**让「超出容量」有确定行为，
   而不是悄悄积压。`CallerRunsPolicy` 还会带来背压
3. **自定义线程工厂**让 `jstack` 里能一眼认出线程归属，
   排查问题时这个差别巨大

### 为什么规范不直接修 Executors

一个自然的疑问是：既然 `newFixedThreadPool` 有问题，
为什么 JDK 不改掉？原因有两点：

1. **兼容性**。这些方法从 Java 5 就在了，语义改动会破坏现有代码。
   有人确实依赖「无界队列保证任务不丢」这个行为。
2. **无界本身不是错，无约束才是错**。`Executors` 提供了一个
   不关心容量、只求能跑的默认实现，适合脚本和测试。
   生产环境需要显式约束，这属于「默认值不该鼓励危险用法」的问题。

所以规约的定位是**生产代码的强制要求**，不是否定 API 本身。

## 追问链

### Q1: 用 newFixedThreadPool 时为什么 maximumPoolSize 不生效？

因为执行顺序是「核心线程 → **入队** → 非核心线程 → 拒绝」，
中间隔着队列。`newFixedThreadPool` 的队列是
`LinkedBlockingQueue`（容量 `Integer.MAX_VALUE`），
`offer` 永远成功，代码永远走不到创建非核心线程的分支。

而且它把 `corePoolSize` 和 `maximumPoolSize` 都设成了 `nThreads`，
本来就相等，即使队列有界也不会创建额外线程。

结果就是「一个参数看起来在控制上限，实际从来没有机会生效」——
这类「配置项静默失效」是排查线上问题时最耗时的一类。

#### Q1.1: 那 newCachedThreadPool 的队列有什么特别？

它用 `SynchronousQueue`：**不存储元素，只做交接**。
`offer` 只有在恰好有线程在 `take` 等待时才成功，否则立刻返回 false。

所以它的行为是：

```
提交任务 → 有空闲线程在等 → 直接交给它执行
        → 没有空闲线程   → offer 失败 → 创建新线程
60 秒空闲 → 线程被回收（corePoolSize = 0，所有线程都是"非核心"）
```

这个设计本身是合理的：适合**大量短任务**场景，线程能复用就不新建。
问题出在 `maximumPoolSize = Integer.MAX_VALUE`——
它没有给「最多创建多少线程」设任何上限。

一旦任务变慢（比如下游接口超时），空闲线程消失，
新任务不断创建新线程，线程数失控。

##### Q1.1.1: 那 SynchronousQueue 应该怎么用才安全？

**给它配一个有上限的 maximumPoolSize 和明确的拒绝策略**：

```java
new ThreadPoolExecutor(
    0, 64,                                     // ★ 上限 64，不是 MAX_VALUE
    60L, TimeUnit.SECONDS,
    new SynchronousQueue<>(),
    namedFactory,
    new ThreadPoolExecutor.CallerRunsPolicy()
);
```

这样既保留了「有闲置线程就复用、不排队」的优点，
又有明确的并发上限。这个配置适合「任务必须立刻执行、
不允许排队」的场景，配上 `CallerRunsPolicy` 就得到了一条天然的背压链路。

要注意 `SynchronousQueue` 的语义是「不排队」，
所以拒绝会比有界队列来得更早——这不是缺陷，而是它的定位。

### Q2: 如果确实需要无界队列，怎么做才安全？

真的需要「任务不能丢」时，正确做法不是用无界队列，
而是**换成有界队列 + 可持久化的拒绝策略**：

```java
new ThreadPoolExecutor(
    8, 8, 0L, TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue<>(1000),
    namedFactory,
    (task, executor) -> {
        // 队列满时不丢弃，落盘或写入消息队列，由补偿任务重投
        taskStore.save(task);
        alarm.warn("线程池队列已满，任务已落盘，当前积压 {}", executor.getQueue().size());
    }
);
```

理由是把「无限度的内存缓冲」换成「有界内存 + 持久化缓冲」：

- 无界队列的问题在于**内存是不可恢复的资源**，
  满了就是 OOM，没有补救余地
- 磁盘或 MQ 是可恢复的：积压可以慢慢消费，
  进程重启也不丢数据

另一条路是**升级消费能力**：如果任务确实多到有界队列扛不住，
说明该扩容了，而不是把队列放大。

同时必须**加监控**。至少要采集：

```
executor.getQueue().size()         // 积压量——最重要的告警指标
executor.getActiveCount()          // 活跃线程数
executor.getPoolSize()             // 当前线程数
executor.getCompletedTaskCount()   // 完成数
rejectedCount                      // 拒绝次数，需自己用 AtomicLong 统计
```

其中**队列积压持续增长**是最有价值的告警：
它比 CPU 打满更早暴露「消费跟不上生产」，
留给你扩容或限流的时间窗口。

## 常见坑

- **看到 `LinkedBlockingQueue` 就以为它是有界的** ——
  无参构造容量是 `Integer.MAX_VALUE`，必须显式传容量才有界
- **说 `newFixedThreadPool` 会无限创建线程** ——
  它的线程数固定为 nThreads，问题在队列无限增长，症状是堆 OOM 而非线程 OOM
- **说 `newCachedThreadPool` 会队列积压** ——
  它用 `SynchronousQueue` 不存储，问题是线程数无上限，
  症状是 `unable to create native thread`
- **只背「不要用 Executors」不说替代方案** ——
  面试官接下来一定会问「那你用什么」
- **把 `unable to create native thread` 当成堆内存问题** ——
  这是线程栈/系统线程数限制的问题，调大 `-Xmx` 没有用
- **认为自定义线程工厂是可选项** ——
  默认线程名 `pool-1-thread-1` 在 `jstack` 里无法区分归属，
  多线程池的线上排查会非常痛苦
- **忘记统计拒绝次数** —— `RejectedExecutionHandler` 默认抛异常，
  如果被上层吞掉就完全没有感知，必须自己计数并告警

## 加分点

- 能画出**两条资源耗尽路径的对比**：队列无界导致堆 OOM、
  线程无上限导致 native thread OOM。症状不同，排查手段也不同
- 知道 `unable to create native thread` **不带堆栈**，
  所以不能靠日志定位，要去看线程数和系统限制
  （`ulimit -u`、`/proc/sys/kernel/threads-max`、
  `/proc/sys/vm/max_map_count`）
- 提到线程池**隔离**：不同业务用不同线程池。
  这是 Hystrix / Sentinel 舱壁模式的基础，
  避免一个慢下游把整个池子占满、连带影响其他业务
- 提到用 `CallerRunsPolicy` 实现**背压**：
  提交线程自己执行任务，期间无法提交新任务，天然限速
- 知道既可以用 `execute()` 也可以用 `submit()`，
  但 **`submit()` 会吞异常**——异常被封进 `Future`，
  不调 `get()` 就永远看不到。排查「任务静默失败」时先看这一点
- 能引到 Java 21 虚拟线程的取舍：`newVirtualThreadPerTaskExecutor`
  每任务一虚拟线程，IO 密集场景不必再纠结池大小，
  但**必须用信号量限制下游并发**，否则会毫无节制地打满数据库连接池

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 5 | 引入 `Executors` 工厂与 `ThreadPoolExecutor`；`LinkedBlockingQueue` 无参构造即为 `Integer.MAX_VALUE` |
| Java 8 | `CompletableFuture` 默认使用 `ForkJoinPool.commonPool()`，并行度默认 CPU 核数 - 1，IO 密集任务不应直接用它 |
| Java 9 | `Executors` 各工厂语义不变；引入 `Executors.newThreadPerTaskExecutor`（每任务一线程，非虚拟线程） |
| Java 21 | 虚拟线程转正，`Executors.newVirtualThreadPerTaskExecutor()` 成为 IO 密集场景首选；同时 `Executors` 的老工厂方法问题依旧存在，规范仍然有效 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: newFixedThreadPool 在任务持续积压时最可能出现什么问题？
    options:
      A: 线程数无限增长，最终 unable to create native thread
      B: 队列无限增长，最终 Java heap space OOM
      C: 任务被静默丢弃
      D: 自动降级为单线程执行
    answer: B
    analysis: 它使用容量为 Integer.MAX_VALUE 的 LinkedBlockingQueue，且 core 与 max 相等，线程数固定不变，积压全部进队列，最终堆内存耗尽。

  - type: CHOICE
    stem: newCachedThreadPool 使用的队列类型是？
    options:
      A: LinkedBlockingQueue（无界）
      B: ArrayBlockingQueue
      C: SynchronousQueue
      D: DelayedWorkQueue
    answer: C
    analysis: SynchronousQueue 不存储元素，只做线程间交接：有空闲线程则直接移交，没有则 offer 失败并创建新线程。它的 maximumPoolSize 是 Integer.MAX_VALUE，因此线程数无上限。

  - type: MULTI
    stem: 相比直接使用 Executors 的工厂方法，手写 ThreadPoolExecutor 的优势包括？
    options:
      A: 可以用有界队列让 maximumPoolSize 真正生效
      B: 可以指定明确的拒绝策略，而不是静默积压或抛异常
      C: 可以自定义线程工厂，让 jstack 里能认出线程归属
      D: 可以完全避免线程池出现任何异常
    answer: ABC
    analysis: D 错误。手写参数只是让资源上限显式可控，任务超限时的行为仍需靠拒绝策略和监控来兜底，不可能「避免所有异常」。

  - type: JUDGE
    stem: "当线程池报 OutOfMemoryError: unable to create native thread 时，调大 -Xmx 可以解决。"
    answer: F
    analysis: 这是创建线程失败，属于线程栈空间或系统线程数限制的问题，与堆大小无关。每个线程默认约 1MB 栈空间，应先排查线程数是否失控（线程池配置、ThreadLocal 是否导致线程无法回收），以及 ulimit -u、/proc/sys/kernel/threads-max 等系统限制。且该错误不带堆栈，不能靠日志定位。
````
