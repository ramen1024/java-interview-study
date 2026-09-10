---
slug: thread-state-interrupt
title: 线程有哪几种状态？interrupt 是怎么工作的？
module: concurrency
tags: [Thread.State, interrupt, 中断, BLOCKED, WAITING]
difficulty: 1
frequency: 2
related:
  - slug: reentrantlock-fairness
    type: RELATED
  - slug: thread-pool-parameters
    type: RELATED
  - slug: virtual-threads
    type: RELATED
---

## 电梯版回答

`Thread.State` 把线程分成六种状态：NEW、RUNNABLE、BLOCKED、WAITING、TIMED_WAITING、TERMINATED。两个最容易说错的地方：RUNNABLE 并不表示在消耗 CPU，它包含就绪、运行以及 IO 阻塞，因为 Java 不区分这些，所以线上看到 RUNNABLE 的线程也可能正卡在 socket 读上；BLOCKED 特指在等 `synchronized` 的监视器锁，而 `Object.wait()`、`Thread.join()`、`LockSupport.park()` 属于 WAITING，带超时的版本属于 TIMED_WAITING。`interrupt()` 只是把中断标志位置为 true，并不会真的停止线程，响不响应完全由被中断的线程自己决定。`isInterrupted()` 读标志、不清除；静态的 `Thread.interrupted()` 在读的同时会清除标志。如果线程正卡在 `sleep` / `wait` / `join` 上，interrupt 会让它抛 `InterruptedException`，而异常抛出的同时标志位会被清除。正确写法是捕获后要么恢复标志位要么向上抛，绝不能吞掉。`synchronized` 等锁不可中断，`ReentrantLock` 的 `lockInterruptibly()` 才可以。

## 展开讲解

### 六种状态

| 状态 | 进入条件 | 典型触发方式 |
|---|---|---|
| `NEW` | 已创建但还没 `start()` | `new Thread(...)` |
| `RUNNABLE` | 可运行、正在运行，或在 JVM 看来可运行（含 IO 阻塞） | `start()` 之后、native 层阻塞 |
| `BLOCKED` | 等待进入 `synchronized` 块 / 方法 | 监视器锁被其他线程持有 |
| `WAITING` | 无限期等待，直到被显式唤醒 | `Object.wait()`、`Thread.join()`、`LockSupport.park()` |
| `TIMED_WAITING` | 限时等待，超时或被唤醒 | `Thread.sleep()`、`wait(timeout)`、`join(timeout)`、`parkNanos` |
| `TERMINATED` | `run()` 已结束 | 正常返回或抛异常退出 |

状态迁移的主要路径：

```
NEW --start()--> RUNNABLE
RUNNABLE --等监视器--> BLOCKED --拿到锁--> RUNNABLE
RUNNABLE --wait/join/park--> WAITING --notify/unpark/目标结束--> RUNNABLE
RUNNABLE --sleep/wait(t)/join(t)--> TIMED_WAITING --超时或唤醒--> RUNNABLE
RUNNABLE --run()结束--> TERMINATED
```

### RUNNABLE 的陷阱

Java 把「就绪」「运行中」「在 native 层阻塞」都归为 `RUNNABLE`。原因是 JVM 不掌握
操作系统调度的真实状况：一个执行 `socketRead0` 的线程在内核看来是阻塞的，但 JVM 的
线程状态机只有一个 `RUNNABLE` 可以放它。所以：

- 看到 `RUNNABLE` **不能**推断它在烧 CPU
- 判断是否真在消耗 CPU，要看 `top -H` 的线程 CPU 占用，以及 jstack 里的栈顶是不是
  `socketRead0`、`epollWait`、`FileInputStream.read` 这类阻塞调用
- `Thread.getState()` 返回的只是调用瞬间的快照，高并发下很快就不准了

### BLOCKED 与 WAITING 的区别

| 维度 | `BLOCKED` | `WAITING` / `TIMED_WAITING` |
|---|---|---|
| 等的东西 | `synchronized` 的监视器锁 | `wait` / `join` / `park` 的唤醒信号 |
| 触发 API | 进入 `synchronized` 块或方法 | `Object.wait()`、`Thread.join()`、`LockSupport.park()` |
| 被 `interrupt()` 打断 | 不会抛异常，只设置标志 | 抛 `InterruptedException` |
| jstack 显示 | `BLOCKED (on object monitor)` | `WAITING (parking)` / `WAITING (on object monitor)` |
| 是否能超时 | 不能 | `TIMED_WAITING` 可以 |

注意一个容易漏的细节：持有监视器的线程调用 `wait()` 后会释放锁并进入 WAITING；
被 `notify` 唤醒后要先**重新竞争监视器**，在这段时间里它的状态是 `BLOCKED`，
不是 WAITING。所以状态是流动的，不能只看某一刻。

### interrupt() 到底做了什么

`Thread.interrupt()` 只是一个**协作式取消信号**，不改变线程状态，也不强制终止线程。
具体行为取决于目标线程此刻在干什么：

| 目标线程当前状态 | `interrupt()` 的效果 |
|---|---|
| `RUNNABLE`（纯计算） | 只把中断标志置为 true，线程继续跑 |
| `WAITING` / `TIMED_WAITING`（`wait`/`join`/`sleep`/`park(t)`） | 抛 `InterruptedException`，**同时清除中断标志** |
| 阻塞在 `InterruptibleChannel` 的 IO | 通道被关闭，抛 `ClosedByInterruptException`，标志置位 |
| 阻塞在 `Selector.select()` | `select` 立即返回，标志置位，不抛异常 |
| `BLOCKED`（等 `synchronized` 锁） | 不抛异常，只设置标志；拿到锁后代码继续执行 |
| 已 `TERMINATED` | 没有任何效果 |

所以「调用 `interrupt()` 就能停掉线程」是错的。它只是把「我希望你停」这件事告诉对方，
对方配不配合，取决于它有没有检查标志、有没有正确处理 `InterruptedException`。

### 读标志位：两个方法别混

```java
Thread t = Thread.currentThread();

t.isInterrupted();          // 实例方法：读取标志，不清除
Thread.interrupted();       // 静态方法：读取标志，并清除
```

`Thread.interrupted()` 是静态方法，作用对象是**当前线程**，而且会清标志位。
它适合放在循环条件里做一次性检查；`isInterrupted()` 不改变状态，适合观测和日志。

### 不可中断与可中断

`synchronized` 获取锁的过程是**不可中断**的：线程在 `BLOCKED` 状态下被 interrupt，
只会设置标志，它仍会一直等锁。而 `ReentrantLock` 提供了可中断的获取方式：

```java
ReentrantLock lock = new ReentrantLock();

lock.lockInterruptibly();               // 等待时被中断会抛 InterruptedException
lock.tryLock(1, TimeUnit.SECONDS);      // 等待超时或被中断也会抛
```

这也是「需要可取消任务时优先用 `ReentrantLock`」的原因之一。

### 正确的中断处理

捕获 `InterruptedException` 后有两种正确做法：能向上抛就向上抛（让调用链决定），
不能抛（比如实现 `Runnable`）就恢复标志位再退出：

```java
public void run() {
    while (!Thread.currentThread().isInterrupted()) {
        try {
            doWork();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // 恢复标志，上层才能感知
            return;
        }
    }
}
```

`Thread.currentThread().interrupt()` 这行的意义是：`InterruptedException` 抛出时
标志位已经被清除，如果这里什么都不做，中断信号就彻底丢了，上层永远等不到取消。

### 为什么 stop() / suspend() 被废弃

- `Thread.stop()`：会在目标线程里**异步抛 `ThreadDeath`**，并释放它持有的**所有监视器**。
  对象可能停在「改了一半」的状态，其他线程看到的就是不一致的数据；异常还可能在任何
  一行字节码处抛出。它比中止不了线程更危险，所以从 Java 1.2 起就被标记废弃，
  Java 20 起直接抛 `UnsupportedOperationException`。
- `Thread.suspend()`：挂起时**不释放已持有的锁**。如果被挂起的线程握着 resume 那个线程
  需要的锁，就直接死锁；而且 `resume()` 可能先于 `suspend()` 执行导致线程永久挂起。
  `destroy()` 则从未真正实现。

正确做法是只提供「请求停止」的方法，由目标线程在自己安全的检查点退出，
并用 `volatile` 或 `AtomicBoolean` 存这个请求标志。

## 追问链

### Q1: Java 线程有哪几种状态？

六种：NEW、RUNNABLE、BLOCKED、WAITING、TIMED_WAITING、TERMINATED，对应
`Thread.State` 枚举，用 `Thread.getState()` 查询。

#### Q1.1: 为什么 RUNNABLE 里会包含等 IO 的情况？

因为 Java 的线程状态机是 JVM 层的抽象，它不掌握操作系统的调度细节。
执行 `socketRead0` 的线程在内核里是被阻塞、让出 CPU 的，但 JVM 没有单独的
「IO 阻塞」状态可以放它，就统一归入 `RUNNABLE`。所以「RUNNABLE = 在跑」不成立。

##### Q1.1.1: 那线上怎么判断一个 RUNNABLE 的线程到底在不在消耗 CPU？

两件事结合看：`top -H -p <pid>` 看具体线程的 CPU 占用，`jstack <pid>` 看栈顶方法。
栈顶是 `socketRead0` / `epollWait` / `read0` 这类 native 调用，说明它在等 IO；
栈顶是自己写的业务计算方法且 CPU 高，才是真的在烧 CPU。只读 `getState()` 判断不了。

#### Q1.2: BLOCKED 和 WAITING 有什么区别？

`BLOCKED` 特指等待 `synchronized` 的监视器锁；`WAITING` 是调用 `Object.wait()`、
`Thread.join()`、`LockSupport.park()` 后进入的等待。区分它们的现实意义在于排查手段：
`BLOCKED` 成堆说明有锁竞争，jstack 里能看到 `waiting to lock`；`WAITING (parking)`
成堆更多是等着被唤醒，比如 AQS 队列里的线程，属于正常排队。

### Q2: interrupt() 是怎么工作的？

它只做一件事：把目标线程的中断标志位置为 true。是否响应完全由目标线程决定——
如果它正卡在 `sleep` / `wait` / `join` 上，会立刻抛 `InterruptedException`；
如果它在跑循环，只能靠它自己检查 `isInterrupted()`。所以它是协作式取消，不是强制停止。

#### Q2.1: isInterrupted() 和 Thread.interrupted() 有什么区别？

`isInterrupted()` 是实例方法，读标志位、**不清除**；`Thread.interrupted()` 是静态方法，
读的同时**清除**标志位，而且作用对象是当前线程，不能指定别的线程。
`Thread.interrupted()` 的清除语义有实际后果：如果代码里连调两次，第二次一定是 false。

##### Q2.1.1: 那抛了 InterruptedException 之后，标志位还在吗？

不在了。`InterruptedException` 抛出的同时会清除中断标志，这是规范要求的行为。
所以要保证取消信号不丢，必须在 `catch` 里调用 `Thread.currentThread().interrupt()`
把它恢复，或者把异常继续向上抛。记住「异常抛出等于标志被吃掉」，这是很多取消逻辑失效的根因。

#### Q2.2: 能不能中断一个正在等 synchronized 锁的线程？

不能真正打断。线程在 `BLOCKED` 状态下被 interrupt 只会设置标志位，它仍然会一直等锁；
等拿到锁进入同步块后，标志位还留着，需要代码自己检查。要获得可中断的等锁能力，
就得把 `synchronized` 换成 `ReentrantLock` 并用 `lockInterruptibly()` 或
`tryLock(timeout)`。

### Q3: 捕获 InterruptedException 后该怎么处理？

两种正确姿势：方法签名允许就继续向上抛，让调用者处理；不允许抛（实现 `Runnable`、
`Callable` 的边界）就先 `Thread.currentThread().interrupt()` 恢复标志位，再做清理并退出。
唯一错的是「捕获后什么都不做」。

#### Q3.1: 为什么 stop() 和 suspend() 被废弃？

`stop()` 会异步抛 `ThreadDeath` 并释放目标线程持有的所有锁，可能让对象停留在
中间状态，其他线程读到不一致的数据；`suspend()` 挂起时不释放锁，容易和 `resume()`
配合出死锁，而且 `resume()` 可能先于 `suspend()` 执行。它们都破坏了对共享状态修改的
原子性，所以被废弃，Java 20 起调用会直接抛 `UnsupportedOperationException`。

##### Q3.1.1: 那要停止一个线程该怎么做？

用协作式取消：定义一个 `volatile boolean` 或 `AtomicBoolean` 标志（也可以直接用线程的
中断标志），在线程的循环里定期检查，需要停止时把标志置位并 `interrupt()` 唤醒阻塞中的
线程，线程自己负责清理资源后 `return`。对线程池则用 `shutdown()` / `shutdownNow()`，
`Future.cancel(true)` 底层也是通过 interrupt 实现的。

## 常见坑

- **「RUNNABLE 就是在消耗 CPU」** —— 错。RUNNABLE 包含就绪、运行和 native 层 IO 阻塞，
  线程可能正卡在 `socketRead0` 上
- **「调用 interrupt() 就能停掉线程」** —— 错。它只设置中断标志位，是否响应由目标线程
  自己决定，纯计算线程不检查标志就会一直跑下去
- **「Thread.interrupted() 和 isInterrupted() 是一回事」** —— 错。前者是静态方法、
  读的同时清除标志位；后者是实例方法、不清除
- **「抛了 InterruptedException 后中断标志还在」** —— 错，异常抛出时会清除标志位，
  所以要主动 `Thread.currentThread().interrupt()` 恢复，否则取消信号会丢
- **「BLOCKED 包含 wait 和 sleep」** —— 错。`BLOCKED` 只指等待 `synchronized` 监视器锁；
  `wait` / `join` / `park` 是 WAITING，带超时的 `sleep` / `wait(t)` 是 TIMED_WAITING
- **「synchronized 等锁可以被 interrupt 打断」** —— 错。`synchronized` 等锁不可中断，
  被中断只设置标志；可中断的是 `lockInterruptibly()` 和 `tryLock(timeout)`
- **「捕获 InterruptedException 后吞掉没关系」** —— 错。吞掉中断让线程无法被取消，
  线程池的 `shutdownNow()`、`Future.cancel(true)` 都会因此失效
- **「stop() 只是简单地把线程停掉，没什么副作用」** —— 错。它会抛 `ThreadDeath` 并
  释放所有监视器，可能破坏对象一致性，已被废弃，Java 20 起直接抛异常

## 加分点

- 能说出 jstack 打印的状态字符串与 `Thread.State` 的对应：
  `BLOCKED (on object monitor)`、`WAITING (parking)`、`WAITING (on object monitor)`、
  `TIMED_WAITING (sleeping)`、`RUNNABLE`。看到 `RUNNABLE` 还要继续看栈顶是不是
  `socketRead0` 这类 native 阻塞
- 知道 `Object.wait()` 被唤醒后要先重新竞争监视器，这段时间状态是 `BLOCKED`，
  所以「wait 之后直接变 RUNNABLE」是错的
- 知道 AQS 里 `parkAndCheckInterrupt()` 内部调用的是 `Thread.interrupted()`（会清标志），
  所以 `acquire()` 在返回前要用 `selfInterrupt()` 把标志补回来——这是「标志被清除后
  主动恢复」的经典源码案例
- 知道 `LockSupport.park()` 对中断的响应是**直接返回、不抛异常、不清标志**，
  所以循环里必须自己判断；这和 `sleep` / `wait` 抛异常的行为不同
- 提到线程池依赖中断实现取消：`ThreadPoolExecutor.shutdownNow()` 会中断工作线程，
  `Future.cancel(true)` 也是发中断；因此任务吞掉 `InterruptedException` 会让取消机制失效
- 知道 `Thread.interrupt()` 对未启动的线程只是设置标志位，对已终止的线程没有任何效果
- 提到虚拟线程同样支持中断，且中断语义与平台线程一致；区别在于它会卸载 / 挂载载体线程，
  但「interrupt 只是协作式信号」这条不变

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 5 | 引入 `Thread.State` 枚举（六种状态）、`ThreadMXBean` 和 `jconsole`，线程状态第一次有了标准查询方式 |
| Java 1.2 起 | `Thread.stop()` / `suspend()` / `resume()` 被标记废弃，`destroy()` 从未实现 |
| Java 20 | `Thread.stop()` / `suspend()` / `resume()` 退化为抛 `UnsupportedOperationException`，这些强行控制线程的 API 彻底不可用 |
| Java 21 | 新增 `Thread.isVirtual()`；虚拟线程同样支持 `interrupt`，阻塞在 `park` 上时被中断会抛 `InterruptedException`，协作式取消的语义不变 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 一个线程的状态是 RUNNABLE，下列判断哪个正确？
    options:
      A: 它一定正在消耗 CPU
      B: 它可能在等 IO，因为 Java 不区分运行和 IO 阻塞
      C: 它一定在等 synchronized 锁
      D: 它已经终止
    answer: B
    analysis: Java 的 RUNNABLE 包含就绪、运行以及 native 层的 IO 阻塞，JVM 不掌握操作系统调度细节，所以看到 RUNNABLE 也可能是在 socketRead0 上等待。等 synchronized 锁是 BLOCKED，终止是 TERMINATED。要判断是否真在消耗 CPU，需要结合 top -H 和 jstack 栈顶。
    difficulty: 1

  - type: MULTI
    stem: 以下关于 interrupt 与中断标志的说法正确的有？
    options:
      A: interrupt() 只是设置中断标志位，不会强制停止线程
      B: isInterrupted() 读取标志位但不清除
      C: Thread.interrupted() 读取的同时会清除标志位
      D: InterruptedException 抛出后中断标志位仍然保留
    answer: ABC
    analysis: D 错误。InterruptedException 抛出时会清除中断标志，这也是为什么捕获后通常要调用 Thread.currentThread().interrupt() 恢复标志，避免取消信号丢失。A、B、C 都正确。
    difficulty: 1

  - type: JUDGE
    stem: 线程处于 BLOCKED 状态等待 synchronized 监视器锁时，调用 interrupt() 会让它抛出 InterruptedException 并停止等待。
    answer: F
    analysis: synchronized 的等锁过程不可中断。在 BLOCKED 状态下被 interrupt 只会设置中断标志，线程仍会继续等待监视器；拿到锁后标志位还在，需要代码自己检查。要可中断地等锁，应使用 ReentrantLock.lockInterruptibly() 或 tryLock(timeout)。
    difficulty: 2

  - type: CLOZE
    stem: |
      补全下面协作式取消的代码，使其正确检查中断并在捕获异常后恢复标志位：
      ```java
      while (!Thread.currentThread().{{1}}()) {
          try {
              doWork();
          } catch (InterruptedException e) {
              // 异常抛出时标志位已被清除，这里恢复它，上层才能感知取消
              Thread.currentThread().{{2}}();
              return;
          }
      }
      ```
    blanks:
      - ["isInterrupted"]
      - ["interrupt"]
    analysis: 循环条件用实例方法 isInterrupted() 检查标志且不清除；catch 里用 Thread.currentThread().interrupt() 恢复被 InterruptedException 清除的标志位，再做清理并退出。若这里吞掉异常，线程池的 shutdownNow()、Future.cancel(true) 等基于中断的取消机制就会失效。
    difficulty: 2
````
