---
slug: aqs-principle
title: AQS 的原理是什么？为什么说它是模板方法模式？
module: concurrency
tags: [AQS, ReentrantLock, 同步器, CLH 队列, Condition]
difficulty: 3
frequency: 3
related:
  - slug: synchronized-lock-upgrade
    type: CONTRAST
  - slug: reentrantlock-fairness
    type: DEEPEN
  - slug: concurrent-collections
    type: RELATED
  - slug: synchronizers
    type: DEEPEN
---

## 电梯版回答

AQS 是 `AbstractQueuedSynchronizer`，它把各种同步器的公共部分抽了出来：一个 volatile 的 int `state` 表示同步状态，一条 CLH 变体的双向 FIFO 队列负责排队、阻塞和唤醒，而「state 该怎么改」留给子类实现。它确实是模板方法模式——`acquire` / `release` 是 final 的模板方法，定义了「先调 `tryAcquire`，失败就入队、自旋、`park`，被前驱 `unpark` 后再重试」这套骨架；子类只重写 `tryAcquire` / `tryRelease`（独占）或 `tryAcquireShared` / `tryReleaseShared`（共享），在里面用自己的语义操作 state。所以 `ReentrantLock` 用 state 存重入次数，`Semaphore` 存剩余许可，`CountDownLatch` 存剩余计数，`ReentrantReadWriteLock` 用高 16 位存读锁数、低 16 位存写锁重入数。队列节点 `Node` 的 `waitStatus` 表达节点状态：CANCELLED、SIGNAL、CONDITION、PROPAGATE。共享模式的 `tryAcquireShared` 返回 int 而不是 boolean，是为了区分「成功但不必传播」和「成功且后继节点也可能获取」。

## 展开讲解

### 两个核心：state 与 CLH 变体队列

`AbstractQueuedSynchronizer` 的字段极少，核心就是两个：

```java
private volatile int state;                 // 同步状态，含义由子类定义
private transient volatile Node head;       // 队列头（虚拟节点）
private transient volatile Node tail;       // 队列尾
```

- **state**：一个 int，用 `getState()` / `setState()` / `compareAndSetState()` 访问。它没有固定含义，子类赋予它语义。
- **队列**：一条双向 FIFO 队列，常被称为「CLH 变体」。注意它和经典的 CLH 自旋锁队列**不一样**：经典 CLH 是单向隐式链表、节点在前驱上自旋；AQS 的 `Node` 有显式的 `prev` / `next` 指针和 `thread` 引用，是双向链表，而且阻塞用 `LockSupport.park()` 而不是纯自旋，避免大量线程空转烧 CPU。

`Node` 的关键字段：

```java
static final class Node {
    volatile int waitStatus;      // 节点状态
    volatile Node prev;           // 前驱
    volatile Node next;           // 后继
    volatile Thread thread;       // 关联的线程
    Node nextWaiter;              // 条件队列里的后继（或共享模式标记）
}
```

head 一开始是**虚拟节点**（dummy node），第一个真实等待者是 `head.next`。这样「出队」就是 `setHead(node)` 把头指针后移，不需要处理空队列的特例。

### Node 的 waitStatus（经典实现）

面试常问的是 JDK 8 到 JDK 13 这版的常量定义：

| 常量 | 值 | 含义 |
|---|---|---|
| `CANCELLED` | 1 | 节点已取消（等待超时或被中断），不再参与竞争 |
| `SIGNAL` | -1 | **后继节点需要被唤醒**：当前节点释放锁时必须 `unpark` 它的后继 |
| `CONDITION` | -2 | 节点在条件队列（`Condition`）里等待 |
| `PROPAGATE` | -3 | 共享模式下需要把释放操作向后传播 |
| 初始值 | 0 | 节点刚入队时的默认状态 |

除了 CANCELLED 是正值，其余都是负值或 0。`shouldParkAfterFailedAcquire` 正是用 `ws > 0` 来判断前驱是否已取消。

### 独占模式：acquire / release 是模板方法

`acquire` 定义了完整的加锁骨架，而它是 `final` 的，子类改不了流程：

```java
public final void acquire(int arg) {
    if (!tryAcquire(arg) &&
        acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
        selfInterrupt();
}
```

拆开看就是模板方法的四步：① 先给子类一次机会（`tryAcquire`），能拿到就直接返回；② 拿不到就 `addWaiter` 入队；③ `acquireQueued` 里自旋加重试、必要时 `park`；④ 如果在等待期间被中断过，用 `selfInterrupt()` 把中断标志补回来。

```java
final boolean acquireQueued(final Node node, int arg) {
    boolean failed = true;
    try {
        boolean interrupted = false;
        for (;;) {
            final Node p = node.predecessor();
            if (p == head && tryAcquire(arg)) {   // 只有前驱是 head 才尝试获取
                setHead(node);
                p.next = null;                    // help GC
                failed = false;
                return interrupted;
            }
            if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt())
                interrupted = true;
        }
    } finally {
        if (failed)
            cancelAcquire(node);
    }
}
```

`shouldParkAfterFailedAcquire` 是「前驱负责唤醒」这套约定的落地点：

```java
private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
    int ws = pred.waitStatus;
    if (ws == Node.SIGNAL)
        return true;                  // 前驱已承诺唤醒我，可以安心 park
    if (ws > 0) {                     // 前驱已取消，顺着 prev 往前跳过
        do {
            node.prev = pred = pred.prev;
        } while (pred.waitStatus > 0);
        pred.next = node;
    } else {
        compareAndSetWaitStatus(pred, ws, Node.SIGNAL);   // 请前驱记住要唤醒我
    }
    return false;
}
```

释放侧的模板方法更短，核心就一句：改 state 成功后，唤醒 head 的后继。

```java
public final boolean release(int arg) {
    if (tryRelease(arg)) {
        Node h = head;
        if (h != null && h.waitStatus != 0)
            unparkSuccessor(h);
        return true;
    }
    return false;
}
```

模板方法模式的分工一目了然：**「改 state」是子类的钩子，「排队、自旋、阻塞、唤醒」全在 AQS 里**，而且这些流程是 final 的，保证了并发正确性不会被子类写坏。

### 共享模式：tryAcquireShared 为什么返回 int

| 返回值 | 含义 |
|---|---|
| 负数 | 获取失败，需要入队等待 |
| 0 | 获取成功，但后续节点不可能也成功，不需要向后传播 |
| 正数 | 获取成功，且后继节点可能也能获取，需要继续唤醒传播 |

返回 int 而不是 boolean，就是为了多表达一层「传播」信息。`CountDownLatch` 的实现最直观：

```java
private static final class Sync extends AbstractQueuedSynchronizer {
    Sync(int count) { setState(count); }
    int getCount() { return getState(); }

    protected int tryAcquireShared(int acquires) {
        return (getState() == 0) ? 1 : -1;   // 计数归零才成功
    }

    protected boolean tryReleaseShared(int releases) {
        for (;;) {
            int c = getState();
            if (c == 0) return false;
            int nextc = c - 1;
            if (compareAndSetState(c, nextc))
                return nextc == 0;           // 只有减到 0 才触发唤醒
        }
    }
}
```

`await` 侧是 `acquireSharedInterruptibly`，`countDown` 侧是 `releaseShared`，计数变成 0 时一次性放行所有等待线程——这就是共享模式的价值。

### 条件队列 ConditionObject

每个 `Condition` 对应一个 `ConditionObject`，它内部维护一条**单向**条件队列（`firstWaiter` / `lastWaiter`，用 `nextWaiter` 串联），和 AQS 的同步队列是两条独立的队列：

- `await()`：`addConditionWaiter()` 把当前线程包装成 `CONDITION` 状态的节点挂到条件队列 → `fullyRelease(node)` 释放锁 → `LockSupport.park()` 阻塞。
- `signal()`：`doSignal(firstWaiter)` 取出一个节点 → `transferForSignal` 把它从条件队列**转移**到同步队列，并把状态从 `CONDITION` 改成 0，必要时唤醒。
- 被唤醒的线程不是立刻拿到锁，而是重新回到同步队列参与竞争。

**为什么 `await` 必须释放全部重入次数（fullyRelease）**：如果只释放一次，持锁线程仍持有锁，`signal` 方拿不到锁执行 `signal`，自己也会永远被 park——直接死锁。

### 子类是怎么用 state 的

| 同步器 | state 的含义 | 模式 |
|---|---|---|
| `ReentrantLock` | 0 空闲；>0 表示被占用的重入次数 | 独占 |
| `ReentrantReadWriteLock` | 高 16 位读锁持有数，低 16 位写锁重入数（`SHARED_SHIFT = 16`） | 读写各一套 |
| `Semaphore` | 当前可用许可数（permits） | 共享 |
| `CountDownLatch` | 剩余未倒数完的计数 | 共享 |
| `ThreadPoolExecutor.Worker` | 0 / 1，表示 worker 是否在运行（**不可重入**） | 独占 |

`ReentrantLock.NonfairSync.tryAcquire` 把 state 的用法讲得最清楚：

```java
protected final boolean tryAcquire(int acquires) {
    final Thread current = Thread.currentThread();
    int c = getState();
    if (c == 0) {
        if (compareAndSetState(0, acquires)) {   // volatile 读 + CAS 写
            setExclusiveOwnerThread(current);
            return true;
        }
    }
    else if (current == getExclusiveOwnerThread()) {
        int nextc = c + acquires;
        if (nextc < 0)
            throw new Error("Maximum lock count exceeded");
        setState(nextc);                          // 已是 owner，单线程写，无需 CAS
        return true;
    }
    return false;
}
```

注意两处不对称：`c == 0` 时要用 CAS（可能多个线程同时抢），而重入分支只有 owner 自己能进来，直接 `setState` 即可。

## 追问链

### Q1: AQS 就一个 int 加一条队列，凭什么撑起整个 java.util.concurrent 的同步器？

因为同步器的难点可以拆成两个正交的问题：**同步状态怎么表示、怎么改**，以及**没抢到的线程怎么排队、怎么阻塞、怎么被唤醒**。AQS 把第二个问题完全包圆了，并且保证它并发正确；第一个问题留给子类，因为不同同步器对 state 的语义完全不同。

所以 `ReentrantLock` 关心的是「独占 + 可重入」，`Semaphore` 关心的是「许可数增减」，`CountDownLatch` 关心的是「计数归零放行」，但它们都用同一套队列和阻塞唤醒机制。

#### Q1.1: state 已经是 volatile 了，为什么改它还要用 CAS？

volatile 只保证单个读或单个写的可见性与有序性，不保证「读—判断—写」这个复合操作是原子的。加锁的典型逻辑是「读到 state 是 0，才把它改成 1」，两个线程可能同时读到 0，然后先后写 1，两个都以为自己拿到了锁——这就是 check-then-act 竞态。

所以 AQS 提供了 `compareAndSetState(expect, update)`，底层是 `Unsafe.compareAndSwapInt`（一条 CAS 指令，带内存屏障）。`ReentrantLock` 正是用 `compareAndSetState(0, 1)` 来抢锁。

##### Q1.1.1: 那 ReentrantLock 的重入次数具体是怎么存进 state 的？

state 存的就是重入次数：0 表示空闲，第一次抢到锁从 0 变 1，同一线程每重入一次就 `setState(c + acquires)` 加一；释放时 `tryRelease` 做减法，减到 0 才把 `exclusiveOwnerThread` 置 null 并唤醒后继。

```java
protected final boolean tryRelease(int releases) {
    int c = getState() - releases;
    if (Thread.currentThread() != getExclusiveOwnerThread())
        throw new IllegalMonitorStateException();
    boolean free = false;
    if (c == 0) {
        free = true;
        setExclusiveOwnerThread(null);
    }
    setState(c);
    return free;
}
```

这里有个细节值得说：释放时用 `setState(c)` 而不是 CAS。因为只有锁的持有者能调用 `unlock`（否则抛 `IllegalMonitorStateException`），不存在写竞争，一次 volatile 写就够了。**判断是不是当前线程持有，靠的是 `exclusiveOwnerThread` 字段，而不是 state**——state 只记录次数，不记录所有者。

#### Q1.2: 队列里到底是谁唤醒谁？为什么不是释放锁的线程直接唤醒一个等待者？

AQS 的约定是**每个节点只关心它的前驱**：节点在 park 之前会通过 `shouldParkAfterFailedAcquire` 把前驱的 `waitStatus` 改成 `SIGNAL`，意思是「我后面有人，你释放时记得唤醒他」。释放锁时只 `unparkSuccessor(head)`，即只唤醒 head 的直接后继。被唤醒的线程在 `acquireQueued` 里发现自己的前驱正是 head，就再试一次 `tryAcquire`；失败的话再把自己挂到新的 head 后面继续 park。

这样做的收益是**避免惊群**：释放锁只唤醒队首的一个线程，不是唤醒全部等待者一起抢。抢不到的线程继续睡，不会白白消耗 CPU。代价是唤醒是有链式延迟的——每个线程被唤醒后可能只是发现自己拿不到锁，又睡回去。

### Q2: 入队之后线程是怎么"睡着"的？`waitStatus` 那几个值分别在什么场景出现？

入队只改链表指针，真正阻塞发生在 `shouldParkAfterFailedAcquire` 返回 true 之后，调用 `LockSupport.park()` 把线程挂起。`waitStatus` 的几个值对应不同阶段：

- **0**：节点刚入队，还没人告诉前驱「要唤醒我」。
- **SIGNAL（-1）**：节点 park 之前给前驱打的标记，承诺「我释放时唤醒你」。
- **CANCELLED（1）**：线程等待超时或中断，在 `cancelAcquire` 里把自己标记为取消，之后要被跳过。
- **CONDITION（-2）**：节点当前在 `Condition` 的条件队列里，不在同步队列上。
- **PROPAGATE（-3）**：共享模式下，一次释放可能需要连续唤醒多个后继节点时使用。

#### Q2.1: 前驱节点如果中途被取消了，后继岂不是永远醒不过来？

不会，AQS 有两道保险：

第一道在 `shouldParkAfterFailedAcquire` 里。它在 park 之前检查前驱的 `waitStatus`，如果发现 `ws > 0`（CANCELLED），就顺着 `prev` 指针一直往前找，跳过所有已取消的节点，然后把后继直接接到一个有效的非取消节点后面：

```java
if (ws > 0) {
    do {
        node.prev = pred = pred.prev;
    } while (pred.waitStatus > 0);
    pred.next = node;
}
```

第二道在 `cancelAcquire` 里。取消节点时会尝试把自己的前驱和后继直接连起来（`pred.next = node.next` 之类），让链表保持可用。即使这一步因为并发 CAS 竞争失败，第一道的 `prev` 遍历也能兜底——`next` 链可能暂时不一致，但 `prev` 链总是可靠的，`unparkSuccessor` 找后继时也是从 `tail` 开始沿 `prev` 往前扫的。

##### Q2.1.1: `unparkSuccessor` 唤醒的为什么可能不是 `head.next`？

因为 `head.next` 可能已经被取消（`waitStatus > 0`），或者处于其他不可唤醒的状态。`unparkSuccessor` 的逻辑是：先看 `node.next`，如果它不为 null 且 `waitStatus <= 0`，直接唤醒它；否则就从 `tail` 开始沿着 `prev` 往前找，找到离 head 最近的、`waitStatus <= 0` 的那个节点再唤醒。

从 tail 往前扫而不是从 head 往后扫，正是为了绕开 `next` 链在并发取消时可能出现的不一致，只用可靠的 `prev` 链。

### Q3: 说 AQS 是模板方法模式，那到底哪部分是模板、哪部分是钩子？

模板是 `acquire` / `acquireInterruptibly` / `acquireShared` / `release` / `releaseShared` / `tryAcquireNanos` 这些 `final` 方法，它们把「尝试获取 → 入队 → 自旋 → 阻塞 → 被唤醒后重试 → 处理中断和取消」的完整流程写死了。钩子是 `tryAcquire` / `tryRelease` / `tryAcquireShared` / `tryReleaseShared` / `isHeldExclusively`，由子类实现，AQS 里默认实现是直接抛 `UnsupportedOperationException`。

这种设计的价值在于：**并发正确性最容易写错的部分（队列操作、park/unpark、中断和取消处理）由 Doug Lea 一次性写对并复用**，子类开发者只需要实现一个相对简单的「改 state」逻辑，出错面小得多。

#### Q3.1: 那为什么独占的 tryAcquire 返回 boolean，共享的 tryAcquireShared 却要返回 int？

因为共享模式下「获取成功」还不够，还要回答**「后续节点能不能也获取成功」**这个问题：

- 返回负数：失败，入队。
- 返回 0：成功，但后继不可能成功，不用再唤醒别人。比如 `Semaphore` 的公平模式里，一个线程拿走了最后一个许可，后面就没许可可发了。
- 返回正数：成功，且后继也可能成功，应该继续传播唤醒。

如果只返回 boolean，`CountDownLatch` 计数归零时一次 `releaseShared` 就没法确保把所有等待线程都放行。这正是共享模式需要「传播」的原因。

##### Q3.1.1: 共享模式的"传播"具体是怎么发生的？

释放侧走 `releaseShared`，它调用 `doReleaseShared` 唤醒 head 的后继。获取侧被唤醒的共享节点成功后，会走 `setHeadAndPropagate(node, r)`：

```java
private void setHeadAndPropagate(Node node, int propagate) {
    Node h = head;
    setHead(node);
    // propagate > 0，或旧 head 是 SIGNAL/PROPAGATE，都说明可能要唤醒后继
    if (propagate > 0 || h == null || h.waitStatus < 0 ||
        (h = head) == null || h.waitStatus < 0) {
        Node s = node.next;
        if (s == null || s.isShared())
            doReleaseShared();
    }
}
```

也就是说，`tryAcquireShared` 返回的正数（`propagate`）以及 `SIGNAL` / `PROPAGATE` 状态，都在告诉 AQS「后面可能还有人能拿到」，于是链式地继续 `doReleaseShared`，最终 `CountDownLatch` 里所有等待线程都会被依次唤醒并放行，而不是只醒一个。

### Q4: `Condition` 的条件队列和 AQS 的同步队列是什么关系？

是两条独立的队列，节点在两者之间转移。`ConditionObject` 自己维护一条单向的条件链表（`firstWaiter` → `nextWaiter`），同步队列则是 AQS 那条双向 FIFO。

`await()` 的流程是：把线程包成 `CONDITION` 状态的节点挂到条件队列 → `fullyRelease` 释放锁 → `LockSupport.park` 阻塞。`signal()` 的流程是：从条件队列取一个节点 → `transferForSignal` 把它移到同步队列、状态从 `CONDITION` 改成 0 → 等前驱释放锁时被唤醒，再正式去抢锁。**所以 `signal` 不等于「立刻拿到锁」，只是「从条件队列毕业，重新排队」**。

#### Q4.1: 为什么 await 必须用 fullyRelease 释放全部重入次数？

因为 `await` 之后这个线程要挂起，它必须真正把锁交出去，让 `signal` 方能拿到锁来发信号。如果只释放一次而线程重入了 N 次，锁仍然被它自己持有，`signal` 方永远进不来，条件永远改变不了，`await` 的线程也就永远等不到唤醒——直接死锁。

`fullyRelease` 的做法是一次性把当前 state 全部释放，并返回释放前的 state（`savedState`）；线程从 `await` 返回后会带着这个 `savedState` 重新走一遍获取流程（回到同步队列竞争锁，成功后再把 state 恢复到原来的重入次数）。这样 `await` 对调用方来说就是「睡着了又醒来」，重入语义连贯。

## 常见坑

- **「AQS 的 state 只能是 0 和 1，就是锁的开关」** —— state 的含义完全由子类定义：`ReentrantLock` 用它存重入次数（可以大于 1），`Semaphore` 存许可数，`ReentrantReadWriteLock` 还把 32 位劈成读写两半
- **「AQS 的队列就是标准的 CLH 锁队列」** —— 是 CLH 的**变体**。经典 CLH 是单向隐式链表、靠前驱节点自旋；AQS 是双向显式链表，用 `LockSupport.park` 阻塞而不是纯自旋
- **「acquire 会调用子类的 tryAcquire，所以 AQS 是策略模式」** —— 更准确的定性是模板方法：`acquire` 是 final 的骨架，子类只填钩子，流程不可替换。策略模式强调的是整个算法可替换
- **「waitStatus 等于 0 表示节点正常运行」** —— 0 只是刚入队的初始值；有意义的等待状态是 SIGNAL，取消是 CANCELLED（唯一的正值）
- **「释放锁时会唤醒所有等待线程」** —— 独占锁的 `release` 只 `unparkSuccessor(head)`，唤醒队首一个；共享模式才会靠传播链式唤醒后续节点
- **「signal() 会直接把等待线程唤醒去抢锁」** —— `signal` 只是把节点从条件队列转移到同步队列，真正被唤醒要等锁的持有者释放锁
- **「await() 只释放一次锁」** —— 用的是 `fullyRelease`，一次性释放全部重入次数，否则会和 signal 方死锁
- **「AQS 本身是一把锁」** —— AQS 是同步器框架，不是锁。`ReentrantLock` 才把内部 `Sync`（AQS 子类）包装成 `Lock` 接口
- **「tryAcquireShared 返回 boolean 会更简洁，返回 int 是历史包袱」** —— int 是为了表达传播语义，`CountDownLatch` 的「一次放行所有等待者」完全依赖它

## 加分点

- 知道 `ThreadPoolExecutor.Worker` 也继承 AQS，用的是一把**不可重入**的独占锁（`tryAcquire` 只在 `state == 0` 时 CAS 成 1），代表 worker 是否正在运行。`shutdown` 时要先 `tryLock` 拿到这个锁，才能安全地中断空闲 worker——这说明 AQS 的适用面远不止用户可见的同步器
- `ReentrantReadWriteLock` 把 state 劈成两半的设计值得单独讲：高 16 位是读锁持有数（共享模式），低 16 位是写锁重入数（独占模式），`SHARED_SHIFT = 16`。读写互斥靠写锁 `tryAcquire` 检查读计数是否为 0、读锁 `tryAcquireShared` 检查写计数是否为 0 来保证
- 能解释 `acquireQueued` 最后的返回值为什么需要 `selfInterrupt()` 收尾：`parkAndCheckInterrupt` 里的 `Thread.interrupted()` 会**清除**中断标志，AQS 记录下「曾被中断」后不立刻抛异常，而是退出等待、在 `acquire` 末尾用 `selfInterrupt()` 把标志补回去，把中断的处理权交还给上层
- 提到 head 是虚拟节点：第一个真实等待者是 `head.next`，出队用 `setHead` 把头后移，这样空队列、单节点队列都不需要特判
- 知道 JDK 14 的 JSR 166 刷新重写了 AQS 内部实现：Node 状态从 `SIGNAL` / `PROPAGATE` 那套改成位标记（`WAITING = 1`、`CANCELLED = 0x80000000`、`COND = 2`），但公开 API 和「独占/共享 + 排队阻塞 + 传播」的语义完全不变。能说出「面试讲的 waitStatus 常量是 JDK 13 之前的实现」是明显的加分
- 能横向对比 `ReentrantLock` 与 `synchronized`：前者基于 AQS，可中断、可超时、可选公平、支持多个 `Condition`；后者由 JVM 管，有锁升级和锁消除等 JVM 层优化。选择依据不是「谁更快」，而是「要不要中断/超时/公平/多条件」
- 知道公平与非公平就差 `hasQueuedPredecessors()` 一次判断：公平模式在 CAS 前先看队列里有没有更早的等待者，有就老老实实去排队

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 5 | AQS 随 JSR 166 引入（Doug Lea），`ReentrantLock` / `Semaphore` / `CountDownLatch` 同时发布 |
| Java 8 ~ 13 | `Node` 使用 `waitStatus`：`CANCELLED = 1`、`SIGNAL = -1`、`CONDITION = -2`、`PROPAGATE = -3`；`ConditionObject` 用 `firstWaiter` / `lastWaiter` |
| Java 14 | JSR 166 刷新重写 AQS 内部实现：`Node` 状态改为位标记（`WAITING = 1`、`CANCELLED = 0x80000000`、`COND = 2`），不再有 `SIGNAL` / `PROPAGATE` 常量；公开 API、state 语义与模板方法结构不变 |
| Java 21 | 同一套重写后的内部实现；`ThreadPoolExecutor.Worker`、`ReentrantLock` 等仍然基于 AQS，虚拟线程的 pinning 改动不在 AQS 这一层 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 关于 AQS 的 state 字段，下列说法正确的是？
    options:
      A: state 固定表示锁是否被持有，只能是 0 或 1
      B: state 的含义由子类定义，ReentrantLock 用它存重入次数
      C: state 是普通 int，修改它不需要 CAS
      D: state 只在公平模式下有意义
    answer: B
    analysis: state 是一个 volatile int，语义由子类赋予。ReentrantLock 用 0 表示空闲、大于 0 表示重入次数；Semaphore 表示剩余许可数；CountDownLatch 表示剩余计数。修改复合逻辑必须用 compareAndSetState，光靠 volatile 不保证原子性。
    difficulty: 2

  - type: MULTI
    stem: 以下关于 AQS 的说法正确的有？
    options:
      A: acquire 和 release 是 final 的模板方法，子类只实现 tryAcquire / tryRelease
      B: tryAcquireShared 返回 int 是为了表达共享模式的传播语义
      C: 独占模式下释放锁时只唤醒队首的一个后继节点
      D: 节点在 park 之前会把前驱的 waitStatus 置为 SIGNAL
    answer: ABCD
    analysis: 四项都正确。A 是模板方法模式的分工；B 中负数表示失败、0 表示成功但不传播、正数表示成功且需要继续传播；C 是避免惊群；D 是「前驱负责唤醒」约定的落地。
    difficulty: 3

  - type: JUDGE
    stem: Condition 的 signal() 会直接把等待线程从条件队列唤醒去竞争锁。
    answer: F
    analysis: signal 只是把节点从条件队列转移到 AQS 的同步队列，状态从 CONDITION 改为 0；被唤醒的线程仍要等锁持有者释放锁、并且自己重新竞争到锁之后才真正返回。
    difficulty: 2

  - type: CLOZE
    stem: |
      补全 ReentrantLock 非公平模式下判断「无竞争」并抢锁的代码，以及判断前驱已取消的条件：
      ```java
      protected final boolean tryAcquire(int acquires) {
          final Thread current = Thread.currentThread();
          int c = getState();
          if (c == {{1}}) {
              if ({{2}}(0, acquires)) {
                  setExclusiveOwnerThread(current);
                  return true;
              }
          }
          // ... 重入分支省略
          return false;
      }

      // shouldParkAfterFailedAcquire：ws > 0 表示前驱已被取消
      if (ws > {{3}}) {
          // 顺着 prev 指针跳过已取消的前驱
      }
      ```
    blanks:
      - ["0"]
      - ["compareAndSetState"]
      - ["0"]
    analysis: state 为 0 表示锁空闲，此时必须用 compareAndSetState(0, acquires) 原子地抢锁，否则多个线程会同时认为自己拿到了锁。waitStatus 中只有 CANCELLED 是正值 1，所以用 ws > 0 判断前驱已取消。
    difficulty: 3
````
