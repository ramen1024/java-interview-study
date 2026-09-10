---
slug: thread-pool-parameters
title: 线程池的七个参数是什么？提交一个任务后内部怎么处理？
module: concurrency
tags: [线程池, ThreadPoolExecutor, Executors, 拒绝策略]
difficulty: 2
frequency: 3
related:
  - slug: why-not-executors
    type: DEEPEN
---

## 电梯版回答

七个参数是核心线程数、最大线程数、空闲存活时间、时间单位、工作队列、线程工厂和拒绝策略。任务提交后的顺序是：核心线程没满就创建核心线程执行；核心线程满了就入队；队列满了才创建非核心线程；线程数到达最大线程数且队列也满，就走拒绝策略。这里反直觉的点是「先入队再加线程」，所以如果用了无界队列，最大线程数永远不会生效。

## 展开讲解

### 七个参数

```java
public ThreadPoolExecutor(
    int corePoolSize,                   // ① 核心线程数
    int maximumPoolSize,                // ② 最大线程数
    long keepAliveTime,                 // ③ 非核心线程空闲存活时间
    TimeUnit unit,                      // ④ ③ 的时间单位
    BlockingQueue<Runnable> workQueue,  // ⑤ 工作队列
    ThreadFactory threadFactory,        // ⑥ 线程工厂
    RejectedExecutionHandler handler    // ⑦ 拒绝策略
)
```

几点容易说错的细节：

- **核心线程默认不回收**。即使空闲也一直存活，除非调用
  `allowCoreThreadTimeOut(true)`，此时 `keepAliveTime` 对核心线程同样生效。
- **`keepAliveTime` 默认只作用于非核心线程**。
- `ThreadFactory` 不是可有可无的装饰：默认工厂创建的线程叫
  `pool-1-thread-1` 这种名字，出问题时 `jstack` 里根本认不出是谁。
  生产必须自定义，至少给线程起有业务含义的名字，并考虑设置
  `setDaemon` 和 `UncaughtExceptionHandler`。

### 执行流程

```java
public void execute(Runnable command) {
    int c = ctl.get();
    // ① 工作线程数 < corePoolSize，直接新建核心线程
    if (workerCountOf(c) < corePoolSize) {
        if (addWorker(command, true)) return;
        c = ctl.get();
    }
    // ② 核心线程已满，尝试入队
    if (isRunning(c) && workQueue.offer(command)) {
        int recheck = ctl.get();
        // 二次检查：入队后线程池被 shutdown，回滚这个任务
        if (!isRunning(recheck) && remove(command))
            reject(command);
        // 队列有任务但没有工作线程（核心线程被回收过），补一个
        else if (workerCountOf(recheck) == 0)
            addWorker(null, false);
    }
    // ③ 入队失败（队列满），尝试创建非核心线程
    else if (!addWorker(command, false))
        // ④ 线程数已达 maximumPoolSize，拒绝
        reject(command);
}
```

流程可以概括成四步，顺序**不能记反**：

```
corePoolSize 未满            → 建核心线程
corePoolSize 已满             → 入队
队列已满 & 未达 max           → 建非核心线程
队列已满 & 已达 max           → 拒绝策略
```

② 里的「二次检查」是这段代码最精妙的地方。入队成功后线程池可能刚好
被 `shutdown`，而 `shutdown` 只处理已存在的任务、不会主动清理刚入队的，
所以要回滚。同理，如果队列里有任务却没有工作线程（核心线程被
`allowCoreThreadTimeOut` 回收过），必须补建一个，否则任务永远没人执行。

### 四种拒绝策略

| 策略 | 行为 | 适用场景 |
|---|---|---|
| `AbortPolicy`（默认） | 抛 `RejectedExecutionException` | 需要调用方感知失败并重试/降级 |
| `CallerRunsPolicy` | **提交任务的线程自己执行** | 需要天然背压，不能让请求堆积 |
| `DiscardPolicy` | 静默丢弃，不抛异常 | 可丢的日志、埋点 |
| `DiscardOldestPolicy` | 丢掉队列里最老的，再重新提交 | 只关心最新数据的场景 |

`CallerRunsPolicy` 值得多说一句：它让提交者（比如 Tomcat 的工作线程）
去执行任务，期间无法提交新任务，等于**自动降低了提交速率**。
这是一种背压机制，比直接丢弃或抛异常更适合「宁可慢也不能丢」的场景。
代价是要注意别把 Web 容器线程池一起拖死。

### 为什么核心线程满了要先进队列

因为线程的创建和销毁成本远高于把一个任务放进队列。如果一超过核心线程数
就急着建新线程，那 `corePoolSize` 这个参数就没有意义了——它表达的
语义正是「常驻线程数」。队列的作用是吸收突发流量。

**代价**：队列容量决定了突发流量的上限，也决定了 `maximumPoolSize`
有没有机会生效。这直接引出下面的坑。

## 追问链

### Q1: 一个任务提交后，如果核心线程满了会怎样？

会先尝试 `workQueue.offer(command)` 入队。只有入队失败（队列满）时
才会去创建非核心线程，代码就是 `else if (!addWorker(command, false))`。
所以「核心满了就建非核心线程」是错的，中间隔着队列。

#### Q1.1: 那如果用无界队列会怎样？

`maximumPoolSize` **永远不会生效**。因为 `offer` 到无界队列永远返回 true，
永远走不到创建非核心线程那个分支。线程数会稳定在 `corePoolSize`，
队列无限增长直到 OOM。

`Executors.newFixedThreadPool` 正是这个结构：

```java
public static ExecutorService newFixedThreadPool(int nThreads) {
    return new ThreadPoolExecutor(
        nThreads, nThreads, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<Runnable>()   // 默认容量 Integer.MAX_VALUE
    );
}
```

注意 `LinkedBlockingQueue` 的**无参构造是容量 `Integer.MAX_VALUE`**，
不是有界队列。这就是「阿里规约禁止用 `Executors` 创建线程池」的直接原因。

##### Q1.1.1: 那应该怎么配队列？

用**有界队列**，让 `maximumPoolSize` 和拒绝策略真正起作用：

```java
new ThreadPoolExecutor(
    8, 16, 60L, TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(200),        // 有界，容量按业务峰值估
    new CustomThreadFactory("order-async"),
    new ThreadPoolExecutor.CallerRunsPolicy()
);
```

容量怎么估：按「能容忍的排队等待时间 × 峰值 QPS」反推。比如峰值 1000 QPS、
单任务耗时 50ms、能容忍排队 2 秒，那么需要的并发是 1000 × 0.05 = 50，
队列容量差不多 1000 × 2 = 2000 起步，再压测调整。

还有一个更稳的做法：**用不同队列表达不同语义**。

- `SynchronousQueue`：不存储，直接移交给线程。想改并发度只需改
  `maximumPoolSize`，适合「任务必须立刻执行」的场景。`newCachedThreadPool`
  用它，但把 `maximumPoolSize` 设成了 `Integer.MAX_VALUE`，所以会无限建线程。
- `PriorityBlockingQueue`：带优先级，注意它是无界的，配合 `maximumPoolSize`
  使用时要小心。

### Q2: 线程数到底该设多少？

「CPU 密集设 N+1，IO 密集设 2N」只是**起点，不是答案**。
它的依据是：CPU 密集任务的瓶颈在 CPU，多一个线程是为了在偶发的
页缺失或中断时顶上；IO 密集任务大部分时间在等待，所以可以超额。
但这套经验值假设了「IO 等待时间 ≈ CPU 计算时间」，真实业务很少满足。

更可用的估算方式是按利特尔法则反推：

```
需要的线程数 = 目标 QPS × 单个任务的平均耗时（秒）
```

比如目标 500 QPS、平均耗时 80ms，那么需要 500 × 0.08 = 40 个线程。
注意这是**稳态并发数**，实际要留余量应对峰值。

但最终仍然要压测。因为：

- 依赖的下游（数据库、Redis、第三方接口）本身有连接池上限，
  线程数超过连接池只会让请求排队在连接池上，还多占内存
- 线程数过多会导致上下文切换开销上升，吞吐反而下降
- GC 压力、锁竞争也会随线程数放大

所以正确的回答是：**先按公式估一个初值，再用压测找拐点，
同时用监控看队列积压和活跃线程数**。

#### Q2.1: 怎么监控线程池的健康状况？

`ThreadPoolExecutor` 暴露了几个方法：

```java
executor.getPoolSize();            // 当前线程数
executor.getActiveCount();         // 正在执行任务的线程数
executor.getQueue().size();        // 队列积压
executor.getCompletedTaskCount();  // 已完成任务数
executor.getLargestPoolSize();     // 历史峰值线程数
```

但更有价值的是继承并重写三个钩子，自己统计耗时分布和异常率：

```java
public class MonitoredThreadPool extends ThreadPoolExecutor {

    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        // 记录开始时间到 ThreadLocal 或任务包装里
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        // 记录耗时；t != null 说明任务抛了异常（注意 submit 会吞掉异常）
    }

    @Override
    protected void terminated() {
        // 线程池完全终止时上报
    }
}
```

**一个关键告警指标是「队列积压持续增长」**——它意味着消费速度长期
跟不上生产速度，比 CPU 打满更早暴露问题。

还有一个隐蔽的坑：`submit()` 会把任务包装成 `FutureTask`，
异常被吞进 `Future` 里，不调用 `get()` 就永远看不到。
如果用 `execute()` 则会直接抛出。排查「任务静默失败」时先看这一点。

## 常见坑

- **把顺序说成「核心满了创建非核心线程，再入队」** —— 顺序是
  核心 → 队列 → 非核心 → 拒绝。说反了会连带把无界队列的问题一起说错
- **认为 `newFixedThreadPool` 是安全的** —— 它的队列容量是
  `Integer.MAX_VALUE`，任务积压会 OOM
- **认为 `maximumPoolSize` 一定能限制并发** —— 队列无界时永远到不了它
- **背「CPU 密集 N+1，IO 密集 2N」当标准答案** —— 这只是起点，
  必须结合压测和下游容量
- **说「核心线程空闲会被回收」** —— 默认不回收，要显式
  `allowCoreThreadTimeOut(true)`
- **认为 `shutdownNow()` 会等任务跑完** —— 它中断正在执行的任务，
  并返回队列里没执行的任务；要平缓关闭用 `shutdown()`
- **只用 `DiscardPolicy` 又不说清丢了什么** —— 静默丢弃在生产上
  是事故的温床，至少要配监控计数

## 加分点

- 知道 `ctl` 这个字段的设计：一个 `AtomicInteger` 高 3 位存线程池状态、
  低 29 位存线程数，用一次 CAS 就能同时判断状态和数量，
  避免两个字段的竞态。这是「用位运算把两个状态打包进一个原子变量」的
  经典手法
- 提到**线程池隔离**：不同业务用不同线程池，避免一个慢下游
  把整个池子拖垮、连带影响其他业务。这是 Hystrix/Sentinel 隔离策略的基础
- 知道可以**运行时动态调参**：`setCorePoolSize` / `setMaximumPoolSize`，
  配合配置中心就能不重启调容量
- 提到**预热**：`prestartAllCoreThreads()` 避免流量刚来时才开始建线程
- 主动说 `CallerRunsPolicy` 的背压语义，比只列出四种策略的名称高一个层次
- 提到 Java 21 的虚拟线程改变了这个问题：`Executors.newVirtualThreadPerTaskExecutor()`
  每个任务一个虚拟线程，由 JVM 挂载到少量载体线程上。
  IO 密集场景不必再纠结线程池大小，但要注意**信号量限制下游并发**，
  因为虚拟线程太便宜，会毫无节制地打满数据库连接池

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 5 | 引入 `ThreadPoolExecutor` 与 `Executors` 工厂 |
| Java 8 | `ctl` 高低位打包状态与线程数；`CompletableFuture` 默认用 `ForkJoinPool.commonPool()`（并行度默认 CPU 核数 - 1），IO 密集任务不应直接用它 |
| Java 9 | 无结构变化，`Executors` 各工厂方法语义不变 |
| Java 21 | 虚拟线程转正，`Executors.newVirtualThreadPerTaskExecutor()` 成为 IO 密集场景的首选；虚拟线程不支持 `ThreadLocal` 缓存大对象式的用法（应改用 `ScopedValue`），也不能靠池化复用 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 线程池的核心线程数已满、工作队列未满时，新提交的任务会怎样？
    options:
      A: 创建非核心线程执行
      B: 进入工作队列等待
      C: 直接触发拒绝策略
      D: 抛 RejectedExecutionException
    answer: B
    analysis: 执行顺序是「核心线程 → 入队 → 非核心线程 → 拒绝」。核心线程满了先尝试入队，只有队列满才创建非核心线程。

  - type: MULTI
    stem: 以下关于 Executors 工厂方法的说法正确的有？
    options:
      A: newFixedThreadPool 使用容量为 Integer.MAX_VALUE 的 LinkedBlockingQueue
      B: newCachedThreadPool 的 maximumPoolSize 是 Integer.MAX_VALUE
      C: newSingleThreadExecutor 的队列是无界的
      D: newFixedThreadPool 会因为队列无界而导致 maximumPoolSize 失效
    answer: ABCD
    analysis: 四个都对。前三者都会在任务积压时无限增长（队列或线程数），这是《阿里巴巴 Java 开发手册》禁止用 Executors 创建线程池的原因。D 中 newFixedThreadPool 的 core 和 max 本来就相等，无界队列进一步保证永远不会创建额外的线程。

  - type: JUDGE
    stem: 线程池的核心线程在空闲超过 keepAliveTime 后会被回收。
    answer: F
    analysis: 默认不会。keepAliveTime 默认只作用于超过核心线程数的那部分线程。要让核心线程也超时回收，需调用 allowCoreThreadTimeOut(true)。

  - type: CLOZE
    stem: |
      补全任务提交的判定顺序（填写数字或表达式）：
      ```java
      if (workerCountOf(c) < {{1}}) {
          if (addWorker(command, true)) return;   // 先建核心线程
          c = ctl.get();
      }
      if (isRunning(c) && workQueue.{{2}}(command)) {   // 再入队
          // 二次检查...
      } else if (!addWorker(command, false)) {     // 队列满了才建非核心线程
          reject(command);
      }
      ```
    blanks:
      - ["corePoolSize"]
      - ["offer"]
    analysis: 判定顺序是核心线程数 → 入队 → 非核心线程 → 拒绝策略。入队用 offer 而不是 put，因为 offer 在队列满时立刻返回 false，好让代码走创建非核心线程的分支；put 会阻塞，那样 maximumPoolSize 和拒绝策略就都没机会生效了。
    difficulty: 3
````
