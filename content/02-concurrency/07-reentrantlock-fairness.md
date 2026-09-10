---
slug: reentrantlock-fairness
title: ReentrantLock 的公平锁和非公平锁有什么区别？
module: concurrency
tags: [ReentrantLock, 公平锁, AQS, Condition, 可重入]
difficulty: 3
frequency: 2
related:
  - slug: aqs-principle
    type: PREREQUISITE
  - slug: synchronized-lock-upgrade
    type: CONTRAST
  - slug: synchronizers
    type: RELATED
---

## 电梯版回答

ReentrantLock 默认是非公平锁，构造时传 true 才是公平锁。差别只有一处判断：公平锁在 CAS 抢锁之前会先调 `hasQueuedPredecessors()`，发现有线程在排队就老实去队尾；非公平锁不做这个检查，`lock()` 一进来就直接 `compareAndSetState(0, 1)` 抢，抢不到才入队，所以刚到达的线程可以插队。非公平反而吞吐更高，因为锁是刚释放的，释放者自己大概率马上又要拿锁，让它直接在热缓存上再抢一次，比唤醒一个被 `park` 的线程再经历一次上下文切换便宜得多；公平锁的代价就是每次交接都要唤醒加切换。它相对 synchronized 的价值不只是公平性，还有可中断的 `lockInterruptibly()`、带超时的 `tryLock(timeout, unit)`、非阻塞的 `tryLock()`，以及一个锁能挂多组 `Condition` 等待队列。用法上必须在 `lock()` 之后用 `try` 包住、在 `finally` 里 `unlock()`，否则一次异常就会永久锁死。

## 展开讲解

### 两种 Sync：NonfairSync 与 FairSync

`ReentrantLock` 内部只有一个字段 `private final Sync sync`，`Sync` 是 AQS 的子类。默认构造用的是非公平实现：

```java
public ReentrantLock() {
    sync = new NonfairSync();
}

public ReentrantLock(boolean fair) {
    sync = fair ? new FairSync() : new NonfairSync();
}
```

非公平锁把「插队」写进了 `lock()`，连 AQS 的 `acquire` 都不进：

```java
// NonfairSync
final void lock() {
    if (compareAndSetState(0, 1)) {
        setExclusiveOwnerThread(Thread.currentThread());
    } else {
        acquire(1);
    }
}
```

公平锁的 `lock()` 没有这个快速路径，只调 `acquire(1)`，一切按 AQS 骨架来：

```java
// FairSync
final void lock() {
    acquire(1);
}
```

真正的差异在 `tryAcquire`。两边都处理「state 为 0」和「当前线程已是 owner」两种情况，公平模式多了一个前置条件：

```java
// NonfairSync：c == 0 时直接 CAS
if (c == 0) {
    if (compareAndSetState(0, acquires)) {
        setExclusiveOwnerThread(current);
        return true;
    }
}

// FairSync：c == 0 时先看队列
if (c == 0) {
    if (!hasQueuedPredecessors() && compareAndSetState(0, acquires)) {
        setExclusiveOwnerThread(current);
        return true;
    }
}
```

`hasQueuedPredecessors()` 回答的是「当前线程之前是否还有等待者」：队列为空（`head == tail`）返回 false；否则看 `head.next`，如果它是 null 或它的 `thread` 就是当前线程，说明没有前驱，也返回 false；其余情况返回 true。返回 false 意味着可以立刻抢，所以队列空时抢锁不算插队。

### 为什么非公平锁吞吐更高

关键在锁的交接成本是不对称的：

- **非公平**：刚执行完临界区、调用 `unlock()` 的线程，代码和数据都还在 CPU 缓存里，它自己立刻再次 `lock()` 时 CAS 命中率很高，接近零成本。新到达的线程也可能直接抢到，省掉了排队和唤醒。
- **公平**：必须走 `release` → `unparkSuccessor(head)` → 唤醒队首线程 → 被唤醒线程从被 `park` 的状态回到运行态、等待调度 → 再竞争锁。每次交接都是一次上下文切换，还可能「刚被唤醒又被新线程抢走、只能再睡」。

所以非公平锁是**有意偏向刚释放锁的线程**，用减少线程切换来换吞吐；公平锁用吞吐换「不会有线程被无限期饿死」。这里没有固定的性能倍数，差距取决于临界区长度和竞争程度——临界区越短、竞争越激烈，非公平的优势通常越明显。

### 与 synchronized 的完整对比

| 维度 | synchronized | ReentrantLock |
|---|---|---|
| 实现层 | JVM 内置，靠对象头 Mark Word 与 ObjectMonitor | JDK 层，基于 AQS 的 state 与 CLH 变体队列 |
| 加解锁方式 | 编译器生成 `monitorenter` / `monitorexit`，方法级用 `ACC_SYNCHRONIZED` | 手动 `lock()` / `unlock()`，必须在 `finally` 释放 |
| 可重入 | 是，靠 ObjectMonitor 的 `_recursions` | 是，靠 `state` 计数加 `exclusiveOwnerThread` |
| 公平性 | 不支持，天然非公平 | 可选，构造时传 true |
| 可中断 | 不支持，等待锁的线程不可被中断打断 | 支持 `lockInterruptibly()` |
| 超时 | 不支持 | 支持 `tryLock(timeout, unit)` |
| 非阻塞尝试 | 不支持 | 支持无参 `tryLock()`，立即返回 boolean |
| 条件队列 | 每个 monitor 只有一个 `_WaitSet`，靠 `wait` / `notify` | 一个锁可 `newCondition()` 多组，各有一条独立条件队列 |
| 状态查询 | 基本没有 | `getHoldCount()` / `isHeldByCurrentThread()` / `getQueueLength()` / `hasQueuedThreads()` |
| JVM 优化 | 有锁升级、锁消除、锁粗化、自适应自旋 | 没有这些 JVM 级优化 |
| 虚拟线程阻塞 | Java 24 之前会 pin 住载体线程 | 不 pin 载体线程 |

选择依据不是「谁更快」，而是「要不要中断、超时、公平、多条件」。

### 可重入是怎么实现的

AQS 的 `state` 存重入次数，`exclusiveOwnerThread` 记录持有者，两者缺一不可——`state` 只记次数，不记是谁：

```java
protected final boolean tryRelease(int releases) {
    int c = getState() - releases;
    if (Thread.currentThread() != getExclusiveOwnerThread()) {
        throw new IllegalMonitorStateException();
    }
    boolean free = false;
    if (c == 0) {
        free = true;
        setExclusiveOwnerThread(null);
    }
    setState(c);
    return free;
}
```

重入分支只有 owner 自己能进来，是单线程写，所以用 `setState(c)` 而不是 CAS；抢锁的 `c == 0` 分支可能有多个线程同时读，才必须用 `compareAndSetState`。

### Condition：一个锁多组等待队列

`synchronized` 的 `wait` / `notify` 只有一个等待集合，想区分「等非空」和「等非满」两类线程只能 `notifyAll` 再各自判断，造成大量无效唤醒。`Condition` 每条对应一个独立的 `ConditionObject`，各自维护一条条件队列：

```java
ReentrantLock lock = new ReentrantLock();
Condition notEmpty = lock.newCondition();   // 消费者等这个
Condition notFull = lock.newCondition();    // 生产者等这个
```

于是生产者可以只 `notEmpty.signal()` 唤醒一个消费者，消费者只 `notFull.signal()` 唤醒一个生产者。`await()` 会调用 `fullyRelease` 释放**全部**重入次数并挂起，被 `signal` 后用释放前的 state 恢复重入次数；`signal` 只是把节点从条件队列转移到 AQS 同步队列，不等于是立刻拿到锁。

## 追问链

### Q1: 非公平锁的「插队」具体发生在哪一步？

`NonfairSync.lock()` 第一件事就是 `compareAndSetState(0, 1)`，成功就直接 `setExclusiveOwnerThread` 返回，连 `acquire` 都不进——这时线程还没进过同步队列，属于最典型的插队。如果这一步失败，才走 `acquire(1)`，而 `acquire` 回调的 `tryAcquire` 在 `c == 0` 时同样不看队列、直接 CAS。公平锁没有这条快速路径，`lock()` 只调 `acquire(1)`，且 `tryAcquire` 里多一句 `!hasQueuedPredecessors()`。

#### Q1.1: 既然能插队，非公平锁会不会把等待线程饿死？

理论上可能，实践里很少。队列里被 `park` 的线程被唤醒后要重新走 `tryAcquire`，如果每次都被刚到达的线程抢先，它就会一直被重新 `park`，这就是饥饿。但能插队成功的主要是「刚刚释放锁的那个线程」，它和自己的下一次获取属于同一个临界区序列，不会无限循环去抢别的线程；而临界区结束后线程通常还会做一段非临界区工作，队列终究会被推进。真正要保证不饿死，就用公平模式。

##### Q1.1.1: 那公平锁是不是就绝对公平？

不是。公平模式只保证「已经在同步队列里的线程按 FIFO 顺序获得锁」，它管不到无参 `tryLock()`——`ReentrantLock.tryLock()` 固定调用 `sync.nonfairTryAcquire(1)`，**公平模式下调用它也会插队**。只有带超时的 `tryLock(timeout, unit)` 走 `tryAcquireNanos`，在 `FairSync` 里才遵守公平。所以依赖公平性时不要用无参 `tryLock()`。

### Q2: 为什么非公平锁的吞吐反而更高？

因为锁交接的成本不对称。非公平让刚释放锁的线程直接再抢一次，此时它的栈、寄存器和临界区数据都还在 CPU 缓存里，CAS 命中率很高，几乎零成本。公平锁则必须唤醒队首：`release` → `unparkSuccessor` → 被唤醒线程从内核态被调度回来 → 再竞争，每一次交接都要一次上下文切换，还可能刚醒又被抢占回去。非公平是用「允许插队」换掉了大部分唤醒和切换，所以吞吐更高。

#### Q2.1: 那公平锁的代价具体是什么？

一是每次获取都要调 `hasQueuedPredecessors()`，多一次对队列头尾的 volatile 读和分支判断；二是交接必须走唤醒链路，线程切换次数上升、锁的传递延迟变大。竞争激烈时公平锁的吞吐通常明显低于非公平锁，但差距取决于临界区长度和竞争程度，没有固定倍数。它换来的是「不会有无期限的饥饿」这个性质。

##### Q2.1.1: 那 `hasQueuedPredecessors()` 具体在判断什么？

它判断「当前线程之前是否还有等待者」。队列为空（`head == tail`）返回 false；否则取 `head.next`，若为 null 或它的 `thread` 就是当前线程，也返回 false；其余返回 true。返回 false 表示可以立刻抢，所以队列空时的抢锁不算插队。这个方法在 JDK 9 有过一次实现修正，处理了入队与出队瞬间可能漏判的边界，语义不变。

### Q3: ReentrantLock 比 synchronized 多了哪些能力？

主要是四类：可中断获取（`lockInterruptibly()`，等待中被中断会抛 `InterruptedException` 而不是继续傻等）、带超时获取（`tryLock(timeout, unit)`，超时返回 false）、非阻塞尝试（无参 `tryLock()`，立刻返回 boolean）、以及一个锁配多组 `Condition`。此外它还能查询 `getHoldCount()`、`isHeldByCurrentThread()`、`getQueueLength()`、`hasQueuedThreads()` 这些状态，synchronized 完全没有。注意可重入不是区别，synchronized 也是可重入的。

#### Q3.1: `tryLock(timeout, unit)` 和 `lockInterruptibly()` 有什么不同？

两者都会阻塞、都可被中断、中断时都抛 `InterruptedException` 且不获得锁。区别在等待终点：`lockInterruptibly()` 没有时限，会一直等到拿到锁或被中断；`tryLock(timeout, unit)` 多一个截止时间，到点还没拿到就返回 false，不抛超时异常。实现上前者走 `acquireInterruptibly`，后者走 `tryAcquireNanos`，后者在公平模式下还会遵守队列顺序。

##### Q3.1.1: `lockInterruptibly()` 被中断后，锁的状态是什么？

线程抛 `InterruptedException` 并且**没有获得锁**：AQS 在 `acquireInterruptibly` 里检测到中断后会调用 `cancelAcquire` 把自己从队列里摘掉，中断标志也随着异常抛出被清除。如果线程在进入方法那一刻中断标志已经置位，`acquireInterruptibly` 一进来就抛异常，根本不会尝试抢锁。所以它不会留下「拿到了锁却没解锁」的中间状态，但捕获异常后的清理逻辑仍要自己写对。

### Q4: Condition 比 wait / notify 强在哪？

强在「一个锁可以有多组等待队列」。`synchronized` 的 `wait` / `notify` 绑定在唯一的 ObjectMonitor 上，只有一个 `_WaitSet`，`notify` 只能在所有等待者里随机挑一个，无法区分「等非空」和「等非满」；用 `notifyAll` 又会唤醒一堆无关线程，让它们醒来发现条件不成立再睡回去，产生大量无效唤醒。`ReentrantLock` 可以 `newCondition()` 多次，每组 Condition 各自维护一条条件队列，`ArrayBlockingQueue` 就是 `ReentrantLock` 加 `notEmpty`、`notFull` 两个 Condition 实现的。

#### Q4.1: `await()` 会释放几次锁？

释放全部重入次数。`ConditionObject.await()` 内部调 `fullyRelease(node)`，一次把 `state` 减到 0、交出锁，并把释放前的 state 存为 `savedState`；线程从 `await` 返回后会带着 `savedState` 重新走一遍获取流程，把重入次数恢复回去。如果只释放一次而线程重入了 N 次，锁仍在自己手里，`signal` 方拿不到锁执行 `signal`，自己也会永远被 park，直接死锁。

##### Q4.1.1: 那 `signal()` 之后等待线程立刻拿到锁了吗？

没有。`signal` 只是把条件队列里的节点通过 `transferForSignal` 转移到 AQS 的同步队列，状态从 `CONDITION` 改成 0，真正唤醒要等当前线程 `unlock()` 释放锁时由 `release` 去 `unparkSuccessor`。被唤醒的线程还要重新在同步队列里竞争锁。所以 `signal` 的语义是「从条件队列毕业、回到锁的竞争队列」，不是「立刻执行」；`signalAll()` 则是把整条条件队列都转移过去。

## 常见坑

- **「ReentrantLock 默认是公平锁」** —— 默认是 `NonfairSync`，要公平必须 `new ReentrantLock(true)`。也不是「公平更好」，公平通常更慢
- **「非公平锁就是完全无序、随机抢」** —— 非公平的入队线程仍然按队列顺序被唤醒，只是允许新到达的线程在入队前插队；它是「允许插队的有序」
- **「公平锁性能更好，因为它减少了无效竞争」** —— 恰恰相反，公平锁每次交接都要唤醒加上下文切换，吞吐通常更低；它买的是不饿死
- **「用 tryLock() 就是公平的」** —— 无参 `tryLock()` 固定走 `nonfairTryAcquire`，公平模式下也会插队；只有带超时的版本守公平
- **「synchronized 不可重入」** —— synchronized 是可重入的，靠 ObjectMonitor 的 `_recursions` 计数，重入不是 ReentrantLock 相对它的优势
- **「把 lock() 写在 try 块里面」** —— 必须 `lock()` 在 try 外、`unlock()` 在 finally 内。如果 `lock()` 自己抛异常，或把它放进 try，异常路径上会去 unlock 一个没持有的锁，抛 `IllegalMonitorStateException` 并掩盖原始异常
- **「unlock() 可以省略，方法结束会释放」** —— JVM 不会自动释放 ReentrantLock，漏写 finally 会永久锁死
- **「Condition.await() 和 wait() 一样只释放一次锁」** —— `await` 用的是 `fullyRelease`，一次性释放全部重入次数
- **「一个 ReentrantLock 只能有一个 Condition」** —— 可以 `newCondition()` 任意多次，这正是它相对 synchronized 的核心优势

## 加分点

- 能说出非公平锁的设计动机是**减少线程切换**，而不只是「实现简单」：它有意偏向刚释放锁的线程，因为那个线程的缓存最热，把锁直接给它比唤醒一个 park 线程更省
- 知道 `hasQueuedPredecessors` 只在真正的 c == 0 竞争分支被调用，重入分支完全不看队列——所以重入是永远允许的，公平性只管第一次获取
- 知道 `ArrayBlockingQueue` 用 `ReentrantLock` + `notEmpty` / `notFull` 两个 Condition 实现有界阻塞队列，是「多条件队列」最有说服力的真实用例
- 能提 `getQueueLength()` / `hasQueuedThreads()` 这些 synchronized 没有的监控手段，说明选 ReentrantLock 有时是为了可观测性
- 知道公平锁也不保证 `tryLock()` 的无参版本公平，这个细节很容易被忽略
- 提到 Java 21 虚拟线程的 pinning：在 synchronized 里阻塞会 pin 住载体线程，`ReentrantLock` 不会，所以虚拟线程场景官方建议用 `ReentrantLock`；Java 24 的 JEP 491 才让 synchronized 不再 pin
- 能横向对比 `ReentrantReadWriteLock` 的公平策略同样由构造参数控制，而它的公平模式在写锁上还额外要求读计数为 0，比独占锁更复杂

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 5 | `ReentrantLock` 随 JSR 166 引入，同时带来公平 / 非公平可选、可中断、可超时、多 `Condition` 这些 synchronized 没有的能力 |
| Java 6 | synchronized 引入偏向锁、轻量级锁和自适应自旋，纯吞吐差距缩小，但可中断 / 超时 / 公平 / 多条件的**能力差异不变** |
| Java 9 | `hasQueuedPredecessors` 做过一次实现修正，修掉入队瞬间的漏判；对调用方语义不变 |
| Java 15 ~ 18 | 偏向锁默认禁用并最终移除，synchronized 的无竞争路径变重，但两者能力对比不受影响 |
| Java 21 | 虚拟线程中 synchronized 阻塞会 pin 住载体线程，`ReentrantLock` 不会，官方建议虚拟线程场景优先用 `ReentrantLock` |
| Java 24 | JEP 491 让虚拟线程在 synchronized 中阻塞不再 pin 载体线程，这一条差异收窄 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 公平锁与非公平锁在 tryAcquire 上的核心差别是哪个判断？
    options:
      A: 是否调用 hasQueuedPredecessors 检查有没有前驱等待者
      B: 是否用 CAS 修改 state
      C: 是否需要 setExclusiveOwnerThread
      D: 是否支持重入
    answer: A
    analysis: 公平模式在 CAS 抢锁之前多一句 !hasQueuedPredecessors()，有前驱就放弃抢、去队尾排队；非公平模式直接 CAS。B 和 C 两者都要做，D 两者都可重入。
    difficulty: 2

  - type: MULTI
    stem: 以下哪些能力是 synchronized 不具备、而 ReentrantLock 具备的？
    options:
      A: 可中断地获取锁（lockInterruptibly）
      B: 带超时的获取锁（tryLock 带时间参数）
      C: 一个锁关联多组 Condition 等待队列
      D: 可重入
    answer: ABC
    analysis: D 错误，synchronized 本身也是可重入的，靠 ObjectMonitor 的 _recursions 计数实现，可重入不是两者的区别。ABC 都是 ReentrantLock 独有的能力，它还能查询 getHoldCount、getQueueLength 等状态。
    difficulty: 2

  - type: JUDGE
    stem: ReentrantLock 的无参 tryLock() 在公平模式下也遵守公平性，会先检查队列里有没有前驱。
    answer: F
    analysis: 无参 tryLock() 固定调用 sync.nonfairTryAcquire(1)，无论构造时是否传 true 都会直接插队。只有带超时的 tryLock(timeout, unit) 走 tryAcquireNanos，在公平模式下才遵守 FIFO。
    difficulty: 3

  - type: CLOZE
    stem: |
      补全公平锁抢锁前的队列检查，以及非公平锁 CAS 成功后记录持有者的调用：
      ```java
      // FairSync.tryAcquire 中 c == 0 的分支
      if (c == 0) {
          if (!{{1}}() && compareAndSetState(0, acquires)) {
              setExclusiveOwnerThread(current);   // 记录 owner
              return true;
          }
      }

      // NonfairSync.lock() 的快速路径，抢锁成功后调用 {{2}}
      if (compareAndSetState(0, 1)) {
          {{2}}(Thread.currentThread());
      }
      ```
    blanks:
      - ["hasQueuedPredecessors", "hasQueuedPredecessors()"]
      - ["setExclusiveOwnerThread"]
    analysis: 公平锁在 CAS 之前必须先确认自己是队首之外的第一个，hasQueuedPredecessors() 返回 false 才允许抢；非公平锁没有这个检查，lock() 一进来就 CAS，成功后用 setExclusiveOwnerThread 记录锁的持有者。state 只记重入次数，持有者必须单独记录。
    difficulty: 3
````
