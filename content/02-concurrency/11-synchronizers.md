---
slug: synchronizers
title: CountDownLatch、CyclicBarrier、Semaphore 有什么区别？
module: concurrency
tags: [CountDownLatch, CyclicBarrier, Semaphore, AQS, 线程协作]
difficulty: 2
frequency: 2
related:
  - slug: aqs-principle
    type: PREREQUISITE
  - slug: reentrantlock-fairness
    type: RELATED
  - slug: concurrent-collections
    type: RELATED
  - slug: thread-pool-parameters
    type: RELATED
---

## 电梯版回答

三者都是线程协作工具，用途完全不同。CountDownLatch 是一次性的倒计数闩：主线程 await 等 N 个任务各自 countDown，计数到 0 就永久打开、不能重置；countDown 本身不阻塞，await 阻塞且支持超时。CyclicBarrier 是可重用的栅栏：一组线程互相等待，每个线程都要 await，到齐后一起放行，await 的返回值是到达序号（先到的值大、最后到的返回 0），可以让最后到的线程做汇总；某一代被打破时其余线程会抛 BrokenBarrierException。Semaphore 是许可计数器，用来限流：acquire 拿许可、release 还许可，还支持非阻塞的 tryAcquire，而且不要求同一线程释放。选型口诀：等事件用 CountDownLatch，等彼此用 CyclicBarrier，限并发用 Semaphore。

## 展开讲解

### CountDownLatch：一次性事件闩

底层是 AQS 的共享模式，`state` 就是剩余计数。

```java
public void countDown() { releaseShared(1); }              // 递减计数，不阻塞
public void await() throws InterruptedException {          // 阻塞到计数为 0
    sync.acquireSharedInterruptibly(1);
}
public boolean await(long timeout, TimeUnit unit) { ... }  // 超时返回 false
public long getCount() { return sync.getCount(); }
```

- `countDown()` 走 `releaseShared(1)`，递减 `state`；减到 0 时 `tryReleaseShared` 返回 true，唤醒所有等待的共享节点
- `await()` 走 `acquireSharedInterruptibly(1)`，计数为 0 才放行，可被中断，也可带超时
- `state` 只减不增，到 0 后永久敞开，**没有加回去的 API，所以不能重置**（要复用只能新建，或改用 CyclicBarrier）
- 典型用法：主线程提交 N 个任务后 `await()`，每个任务在 `finally` 里 `countDown()`

### CyclicBarrier：可循环的栅栏

内部是 `ReentrantLock` + 一个 `Condition`（`trip`），再加 `Generation`（代）和 `count` 两个字段，不是直接继承 AQS。

```java
public int await() throws InterruptedException, BrokenBarrierException {
    return dowait(false, 0L);
}
// dowait 内部
int index = --count;
if (index == 0) {                    // 最后一个到达的线程
    // 执行构造时传入的 barrierAction
    nextGeneration();                // 复位 count，signalAll，开启新的一代
    return 0;
}
// 其余线程在 trip.await() 上等待
```

- **可重用**：所有线程到齐后 `nextGeneration()` 自动复位计数，进入下一代；也可以 `reset()` 强制重置
- `await()` 返回**到达序号**：先到的返回值大（最大 `parties - 1`），最后到的返回 0，并负责执行 `barrierAction`
- `BrokenBarrierException` 表示「这一代同步作废」：某个等待线程被中断、`await` 超时、有人调用了 `reset()`、或者 `barrierAction` 抛了异常，都会执行 `breakBarrier()` 把当前代标记为 broken 并唤醒所有等待者，被唤醒的线程检查到 broken 就抛这个异常
- 线程之间是**互相等待**：每个参与者都必须调 `await()`，否则其他人永远等不到

### Semaphore：许可计数

AQS 共享模式，`state` 是当前可用许可数。

```java
semaphore.acquire();        // 拿 1 个许可，拿不到就阻塞（可中断）
semaphore.acquire(n);       // 一次拿 n 个
boolean ok = semaphore.tryAcquire();                  // 非阻塞，立即返回
boolean ok2 = semaphore.tryAcquire(1, 1, TimeUnit.SECONDS);  // 限时等待
semaphore.release();        // 归还许可，唤醒等待者
```

- 用来**限流**：初始化 N 个许可表示最多 N 个并发，进入前 acquire，结束后在 finally 里 release
- **不记录持有者**，`release()` 可以由任意线程调用，甚至可以是没 acquire 过的线程——这是它和锁最本质的区别，所以它能用来做「跨线程的资源计数」，但做不了「谁加锁谁解锁」的严格互斥
- 构造可以指定公平模式：`Semaphore(int permits, boolean fair)`。公平模式按 FIFO 分配许可，非公平允许新来的线程插队，吞吐更高但可能饿死早到的线程
- 许可数为 1 时也能当互斥锁用，但因为没有所有者语义，`release` 错了不会报错

### 选型判断

| 需求 | 选择 | 理由 |
|---|---|---|
| 一个 / 一组线程等 N 个事件完成，一次性 | `CountDownLatch` | 计数只减不增，语义就是「等事件」 |
| 一组线程互为等待点，需要反复使用 | `CyclicBarrier` | 自带 Generation，到齐后自动复位 |
| 限制同时访问某资源的并发数 | `Semaphore` | 许可是可加可减的计数，可跨线程归还 |

## 追问链

### Q1: countDown() 和 await() 有什么区别？

`countDown()` 把计数减一然后立刻返回，不阻塞；`await()` 阻塞当前线程，直到计数变成 0（或超时 / 被中断）。两者通常由不同角色的线程调用：工作线程负责 `countDown()`，等待方（比如主线程）负责 `await()`。注意 `await()` 不消耗计数，多个线程可以同时 `await()` 同一个闩。

#### Q1.1: 为什么 CountDownLatch 用完不能重置？

因为计数就是 AQS 的同步状态 `state`，`countDown()` 是 `releaseShared(1)`，只会做减法，JDK 没有提供把它加回去的公开 API，所以到 0 之后状态永久停在 0、所有 `await` 都直接通过。要反复使用同一个同步点，应该换 `CyclicBarrier`，或者每次新建一个 `CountDownLatch`。

##### Q1.1.1: 那 CyclicBarrier 是怎么做到可重用的？

它内部有一个 `Generation`（代）对象和 `count` 字段。`dowait` 里把 `count` 减到 0 的那个线程会调用 `nextGeneration()`：新建一个 `Generation`、把 `count` 复位成 `parties`、`signalAll()` 唤醒所有等待者，大家就进入了下一代。`reset()` 则是强行打破当前代再复位，正在等待的线程会收到 `BrokenBarrierException`。

### Q2: CyclicBarrier 和 CountDownLatch 除了能不能复用，还有什么本质区别？

等待关系不同。CountDownLatch 是「一个或几个线程等一组事件」，`await` 的线程和 `countDown` 的线程可以完全分离，而且 `await` 不消耗计数。CyclicBarrier 是「一组线程互相等」，每个参与者都必须自己调用 `await`，少一个就到不齐、其他线程会一直等下去。另外 CyclicBarrier 可以带一个 `barrierAction`，由最后到达的线程执行，相当于一次「到齐后的汇总」。

#### Q2.1: CyclicBarrier.await() 的返回值有什么用？

返回的是到达序号：先到的线程拿到大值（最大 `parties - 1`），**最后到达的线程返回 0**，并且就是它执行 `barrierAction`。所以可以在 `await()` 返回 0 时做汇总、统计、切换阶段，其他线程直接进入下一步，省掉一次额外的同步。注意返回的是序号不是到达的线程数。

##### Q2.1.1: BrokenBarrierException 什么时候会抛？

当这一代被打破时。触发场景有四个：等待中的线程被中断；`await(timeout)` 超时；有人调用 `reset()`；构造时传入的 `barrierAction` 抛了异常。这些情况都会执行 `breakBarrier()`，把当前 `Generation` 标记为 broken 并唤醒所有等待者，被唤醒的线程发现 broken 就抛 `BrokenBarrierException`。它的语义是「这次同步作废，别假设大家都到齐了」，通常意味着这一轮结果不可信，需要重试。

### Q3: Semaphore 和 ReentrantLock 有什么区别？

Semaphore 是许可计数，可以有多个许可、可以被任意线程归还，不记录持有者；ReentrantLock 是独占锁，同一时刻只有一个线程持有、记录所有者并且可重入。所以 Semaphore 能做「限制 N 个并发」这种资源池语义，锁只能做互斥。反过来，如果你需要「谁加的锁谁释放」这种约束，Semaphore 给不了——别的线程调用 `release()` 它照单全收。

#### Q3.1: 用 Semaphore 做限流时，tryAcquire 和 acquire 有什么区别？

`acquire()` 拿不到许可会一直阻塞（可中断），`tryAcquire()` 立即返回 true / false，`tryAcquire(timeout)` 阻塞但限时。限流场景如果无脑用 `acquire()` 又没有超时或降级，一旦许可迟迟不释放（比如下游卡住），请求线程会全部堆在这一行上，把上游的 Web 线程池也拖垮——限流器自己变成了故障点。

##### Q3.1.1: 那正确做法应该怎么配？

用 `tryAcquire(timeout, unit)` 拿许可，超时就快速失败或降级返回，别让调用方无限期等待；`release()` 放在 finally 里，保证异常路径也会归还。另外公平模式能避免线程被饿死，但因为要维护 FIFO 队列吞吐会低一些，按场景权衡。

## 常见坑

- **说 CountDownLatch 可以重置或复用** —— 不能，计数只减不增，是一次性工具；要复用只能用 CyclicBarrier 或新建一个
- **说 countDown() 会阻塞等待其他线程** —— 它只是递减计数然后立即返回，阻塞的是 `await()`
- **说 CyclicBarrier 是一个线程等 N 个任务** —— 那是 CountDownLatch；CyclicBarrier 是 N 个线程互相等待
- **说 Semaphore 的许可必须由 acquire 的同一线程 release** —— 错。它不记录持有者，任意线程都能 `release()`（这是它和锁的本质区别），但反过来说它也保证不了「谁拿谁还」
- **说 CyclicBarrier.await() 返回的是到达的线程数** —— 返回的是到达序号，最后到达的线程返回 0 并执行 barrierAction
- **说 CyclicBarrier 被打破后会自己恢复** —— 破了就是破了，必须 `reset()`（或新建），而且等待中的线程会抛 `BrokenBarrierException`
- **说三者都直接继承 AQS** —— 严格说不准确：CountDownLatch 和 Semaphore 内部是 AQS 子类（共享模式），CyclicBarrier 是基于 `ReentrantLock` + `Condition` 实现的，而 ReentrantLock 本身才是 AQS 的独占模式
- **说 Semaphore 的许可数和线程数是一回事** —— 许可是资源计数，与线程无关，一个线程可以持有多个许可，也可以由 A 线程 acquire、B 线程 release

## 加分点

- 能说出 CountDownLatch 的映射关系：`state` 是计数，`countDown` = `releaseShared(1)`，`await` = `acquireSharedInterruptibly(1)`，计数归零时 `tryReleaseShared` 返回 true 从而唤醒所有共享节点
- 提到 AQS 共享节点的 `PROPAGATE` 状态：它是 `await` 能一次唤醒所有等待线程的关键，共享式的唤醒会沿队列传播
- 解释 CyclicBarrier 为什么需要 `Generation`：用它区分「这一代正常到齐」和「上一代被打破」，`reset()` 时换新代可以避免把上一代已经结束的线程误唤醒
- 知道 Semaphore 可以拿来做资源池：许可数 = 连接数上限，配合 `tryAcquire` 做快速失败
- 提到虚拟线程场景下 Semaphore 的新用途：虚拟线程创建成本极低，很容易无节制地打满下游，用信号量限制对数据库等资源的并发是常见做法
- 主动说 `CountDownLatch` 配合线程池的惯用法：提交 N 个任务，每个任务在 `finally` 里 `countDown()`，主线程 `await(timeout)`，避免永久等待

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 5 | `CountDownLatch` / `CyclicBarrier` / `Semaphore` 随 JSR-166（`java.util.concurrent`）一起引入，语义至今未变 |
| Java 8 | 同期引入的 `CompletableFuture` 提供了另一种「等 N 个任务完成」的写法（`allOf`），纯编排场景下不必再用 CountDownLatch |
| Java 21 | 虚拟线程转正后，Semaphore 常被用来限制对下游资源的并发，因为虚拟线程创建成本极低、不加许可容易打满连接池 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 需要一组线程互相等待、到齐后一起继续，而且这个同步点要反复使用，应该选哪个？
    options:
      A: CountDownLatch
      B: CyclicBarrier
      C: Semaphore
      D: ReentrantLock
    answer: B
    analysis: CyclicBarrier 自带 Generation，到齐后自动复位，可循环使用；CountDownLatch 计数只减不增、一次性的；Semaphore 是许可计数用于限流。

  - type: JUDGE
    stem: Semaphore 的 release() 必须由之前调用 acquire() 的同一个线程执行。
    answer: F
    analysis: Semaphore 只维护可用许可数、不记录持有者，任何线程都能 release。这正是它和 ReentrantLock 的本质区别，也意味着它保证不了「谁加锁谁解锁」。

  - type: MULTI
    stem: 关于 CountDownLatch，下列说法正确的有？
    options:
      A: countDown() 递减计数且不会阻塞调用线程
      B: await() 可以带超时参数，超时后返回 false
      C: 计数到 0 后所有 await 都会立即通过
      D: 可以通过某个方法把计数重新加回去以重复使用
    answer: ABC
    analysis: D 错误。计数是 AQS 的同步状态，只减不增，没有加回去的 API，到 0 后永久敞开、不能重置。要复用只能换 CyclicBarrier 或新建实例。

  - type: CLOZE
    stem: |
      补全 Semaphore 限流的标准写法：
      ```java
      // 进入前获取许可，拿不到就阻塞
      semaphore.{{1}}();
      try {
          doBusiness();
      } finally {
          // 无论如何都要归还许可
          semaphore.{{2}}();
      }
      ```
    blanks:
      - ["acquire"]
      - ["release"]
    analysis: acquire() 获取许可、拿不到会阻塞（也有非阻塞的 tryAcquire 和限时的 tryAcquire(timeout)）；release() 归还许可，必须放在 finally 里，否则异常路径会导致许可泄漏，最终所有线程都被卡住。
    difficulty: 2
````
