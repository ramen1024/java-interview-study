---
slug: deadlock
title: 死锁是怎么产生的？线上怎么排查？
module: concurrency
tags: [死锁, jstack, ThreadMXBean, 加锁顺序, 数据库死锁]
difficulty: 2
frequency: 2
related:
  - slug: synchronized-lock-upgrade
    type: RELATED
  - slug: reentrantlock-fairness
    type: RELATED
  - slug: innodb-locks
    type: RELATED
---

## 电梯版回答

死锁是多个线程互相持有对方需要的锁、又都不释放，形成环路，谁都走不下去。产生死锁必须同时满足四个条件：互斥、请求与保持、不可剥夺、循环等待；破坏其中任意一个就能预防，实践中最可行的是破坏循环等待，也就是让所有代码约定同一个加锁顺序。线上排查首选 `jstack`，它会自动检测死锁并打印 `Found one Java-level deadlock:`，把每个线程持有什么锁、在等谁持有的锁列出来；代码里可以用 `ThreadMXBean.findDeadlockedThreads()` 编程检测；`jconsole`、`jvisualvm` 有图形化的检测按钮，Arthas 用 `thread -b` 看阻塞线程。要分清两件事：JVM 死锁不会自愈，一般只能重启或等外部干预；而数据库死锁是另一回事，InnoDB 会自动检测并回滚其中代价较小的事务，客户端收到 1213 错误码。

## 展开讲解

### 四个必要条件

| 条件 | 含义 | 破坏方式 |
|---|---|---|
| 互斥 | 资源同一时刻只能被一个线程占用 | 用无锁结构、CAS、并发容器替代锁 |
| 请求与保持 | 已持有资源，又去申请新资源且不释放已有的 | 一次性申请所有资源，或申请不到就释放已持有的 |
| 不可剥夺 | 资源只能由持有者主动释放，不能被抢走 | 用带超时的 `tryLock`，超时就放弃并回退 |
| 循环等待 | 存在线程与资源的等待环路 | 固定全局加锁顺序，按同一顺序获取所有锁 |

四个条件是「同时成立才死锁」，所以只要破坏任意一个就足够了。前两个往往受业务语义
约束，不太好动；不可剥夺可以通过超时改写；**实践中最通用的是破坏循环等待**，因为它
只要求代码遵守一个约定，改造代价最小。

### 一个最小的死锁示例

```java
Object lockA = new Object();
Object lockB = new Object();

// 线程 1：先 A 后 B
synchronized (lockA) {
    synchronized (lockB) {
        // 业务
    }
}

// 线程 2：先 B 后 A
synchronized (lockB) {
    synchronized (lockA) {
        // 业务
    }
}
```

两个线程各自拿到第一把锁后去申请第二把，而第二把正握在对方手里，循环等待形成。
注意这不需要两把锁「同时」被抢——只要两个线程的推进时序错开成上面这样就会发生，
所以这类 bug 在压测时可能跑几万次才复现一次，非常隐蔽。

破坏循环等待的改法是统一顺序：所有获取 `lockA` 和 `lockB` 的代码都先 A 后 B，
就不可能出现环路。

### 用 jstack 自动检测

`jstack <pid>` 会分析对象监视器和 ownable synchronizer 的等待关系，
发现环路时直接输出结论（下面地址因进程而异，格式是固定的）：

```
Found one Java-level deadlock:
=============================
"Thread-1":
  waiting to lock monitor 0x000000076adf5b20 (object 0x00000007d5f0b8e8, a java.lang.Object),
  which is held by "Thread-0"
"Thread-0":
  waiting to lock monitor 0x000000076adf5b10 (object 0x00000007d5f0b8a8, a java.lang.Object),
  which is held by "Thread-1"

Java stack information for the threads listed above:
===================================================
"Thread-1":
        at com.example.DeadlockDemo.lambda$main$1(DeadlockDemo.java:20)
        - waiting to lock <0x000000076adf5b20> (a java.lang.Object)
        - locked <0x000000076adf5b10> (a java.lang.Object)
"Thread-0":
        at com.example.DeadlockDemo.lambda$main$0(DeadlockDemo.java:13)
        - waiting to lock <0x000000076adf5b10> (a java.lang.Object)
        - locked <0x000000076adf5b20> (a java.lang.Object)
Found 1 deadlock.
```

读法就两步：先看 `waiting to lock` 的是哪个对象、`which is held by` 谁，
再看每个线程栈里的 `- locked <...>` 确认它已经持有了什么。两者拼起来就是等待环路。
`jstack -l` 还会额外打印 ownable synchronizers 段，`ReentrantLock` 这类
AQS 同步器就在那里体现。

### 编程检测：ThreadMXBean

```java
ThreadMXBean bean = ManagementFactory.getThreadMXBean();
long[] ids = bean.findDeadlockedThreads();      // 包含监视器和 ownable synchronizer
if (ids != null && ids.length > 0) {
    ThreadInfo[] infos = bean.getThreadInfo(ids, true, true);
    for (ThreadInfo info : infos) {
        String lock = info.getLockName();          // 在等哪个锁
        String owner = info.getLockOwnerName();    // 锁被谁持有
        MonitorInfo[] monitors = info.getLockedMonitors();  // 已持有哪些锁
    }
}
```

注意两个方法的区别：`findMonitorDeadlockedThreads()` 只管对象监视器
（`synchronized`）；`findDeadlockedThreads()` 还会覆盖 ownable synchronizer
（`ReentrantLock` 等），前提是 `isSynchronizerUsageSupported()` 为 true。
做监控告警时后者更实用，适合搭一个定时巡检。

### 排查步骤

1. `top -H -p <pid>` 或 `ps -mp` 找出 CPU 高的线程，或直接怀疑接口卡死
2. `jstack <pid> > stack.log`，先搜 `Found` 关键字看有没有直接结论
3. 没有结论的话，找处于 `BLOCKED` 状态的线程，看 `waiting to lock` 和 `- locked`
4. 把等待关系串成图，检查有没有环；跨进程还要考虑数据库行锁、分布式锁

### JVM 死锁与数据库死锁不是一回事

- **JVM 死锁**：JVM 不会自动解除，`synchronized` 死锁没有超时机制，
  一般只能重启进程，或者由外部工具干预。所以重点在预防。
- **数据库死锁**：InnoDB 维护等待关系图，检测到环路会**主动回滚其中一个事务**
  （通常是代价较小的那个），被回滚的事务收到错误码 1213
  `Deadlock found when trying to get lock; try restarting transaction`，
  应用层捕获后重试即可。开启 `innodb_deadlock_detect`（默认开启）时是主动检测；
  关闭后退化为依赖 `innodb_lock_wait_timeout` 超时报错。

诊断数据库死锁用 `SHOW ENGINE INNODB STATUS` 里的 `LATEST DETECTED DEADLOCK` 段，
MySQL 8.0 还可以查 `performance_schema.data_locks` 和 `data_lock_waits`。

### 避免手段

- 加锁顺序全局一致（最根本的一条）
- 用 `ReentrantLock.tryLock(timeout)`，拿不到就释放已持有的锁并重试
- 缩小锁范围、缩短持锁时间，尤其别在锁内做远程调用
- 缩短事务，别在事务里等用户输入或调外部接口
- 减少锁的数量，能用一把锁就不用两把；能用并发容器 / 原子类就不用显式锁

## 追问链

### Q1: 死锁产生的四个必要条件是什么？

互斥、请求与保持、不可剥夺、循环等待。互斥是说资源一次只能给一个线程；请求与保持是
已持有资源还去申请新资源、且不放手已有的；不可剥夺是资源只能主动释放、不能被抢；
循环等待是存在一条线程互相等的环路。四者必须同时成立。

#### Q1.1: 那破坏哪个条件最可行？

破坏循环等待。互斥通常由资源本身决定，比如写数据库就是互斥的，不好破坏；请求与保持
要改成「一次性申请所有资源」，业务上很难做到；不可剥夺要求把锁设计成可抢占的，
Java 的 `synchronized` 根本不支持。而循环等待只需要统一加锁顺序，改造成本最低、
覆盖面最广。

##### Q1.1.1: 固定加锁顺序具体怎么落地？

约定一个全局的锁顺序，所有代码按它获取。核心技巧是给锁对象定一个稳定的排序键：
对于有业务 ID 的对象，按 ID 大小排序；没有业务键时，可以按 `System.identityHashCode()`
排序。要注意 hash 相等时的兜底——两个对象 hashCode 相同时排序就不稳定了，需要再加一个
冲突时的第三把「仲裁锁」来串行化这种情况，否则仍有极小的概率死锁。

```java
// 按业务主键排序后加锁，保证不同线程拿到相同的顺序
long first = Math.min(fromId, toId);
long second = Math.max(fromId, toId);

synchronized (lockOf(first)) {
    synchronized (lockOf(second)) {
        transfer(fromId, toId);
    }
}
```

#### Q1.2: 用 tryLock 能避免死锁吗？

能，它破坏的是「不可剥夺」。`tryLock(timeout)` 等不到就返回 false，线程此时主动释放
已持有的锁、退回去重试，环路就被打断了。关键是**释放已经拿到的锁**，否则持有 A
再去试 B 失败后仍握着 A，别的线程照样可能绕成环。

```java
if (lockA.tryLock(1, TimeUnit.SECONDS)) {
    try {
        if (lockB.tryLock(1, TimeUnit.SECONDS)) {
            try {
                doWork();
            } finally {
                lockB.unlock();
            }
        }
        // 第二把没拿到：什么都不做，退出时释放 lockA，稍后重试
    } finally {
        lockA.unlock();
    }
}
```

##### Q1.2.1: 那 tryLock 重试不会导致活锁吗？

会。如果两个线程都用同样的超时同时失败、再同时重试，可能反复错过。工程上的做法是
**退避重试**：加随机抖动，或让两个线程的重试节奏错开。不过要清楚，
`tryLock` 只是打断了死锁环路，并没有消除竞争；如果资源本身不足，
表现会从「死锁」变成「拿不到锁而失败」或「活锁」，需要配合限流和退避策略。

### Q2: 线上出现死锁怎么排查？

分两步。第一步用工具拿到结论：`jstack <pid>` 会直接打印 `Found one Java-level deadlock:`，
列出线程、持有的锁、等待的锁；`jconsole` / `jvisualvm` 有「检测死锁」按钮；
Arthas 用 `thread -b` 找阻塞线程。第二步是人肉分析：把 `waiting to lock` 和
`which is held by` 串成等待图，确认环路，再看代码里对应的加锁顺序。

#### Q2.1: jstack 里的死锁信息具体长什么样，怎么读？

输出结构分三段：第一段是 `Found one Java-level deadlock:` 后面跟着每个线程的
`waiting to lock ... which is held by ...`，这直接给出了等待环；第二段是
`Java stack information for the threads listed above:`，每个线程栈里用
`- waiting to lock <...>` 标出在等哪个锁、`- locked <...>` 标出已持有哪些锁；
最后一行是 `Found N deadlock.` 汇总。

读法就是：把每个线程的「在等谁 → 谁持有」连起来，看是否回到起点。同时要**用栈帧定位代码行**，
因为锁对象的地址没有业务含义，最终要落到哪一行 `synchronized` 或哪个 `lock()` 上。

##### Q2.1.1: 如果 jstack 没有输出 `Found ... deadlock` 呢？

那说明 JVM 的等待图里没有环，线程可能不是 JVM 层的死锁，而是被别的资源卡住了：

- 卡在**数据库行锁**：查 `SHOW ENGINE INNODB STATUS` 的 `LATEST DETECTED DEADLOCK`，
  或 `performance_schema.data_lock_waits`
- 卡在**无界等待**：线程处于 `WAITING (parking)`，实际是任务永远等不到信号
- 卡在**线程池耗尽**：所有线程都在等一个提交给同一线程池的子任务（线程饥饿死锁），
  这类死锁 jstack 检测不出来，需要在栈里看出「任务在等另一个任务提交结果」
- 卡在**外部资源**：分布式锁、连接池、第三方接口没有超时

所以「jstack 没报死锁」不等于「没有死锁」，只等于「JVM 层没看到环」。

#### Q2.2: 能不能在代码里自动检测死锁？

可以，用 `ThreadMXBean.findDeadlockedThreads()` 定时巡检，发现非空就打印线程信息并告警：

```java
ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
monitor.scheduleAtFixedRate(() -> {
    ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    long[] ids = bean.findDeadlockedThreads();
    if (ids != null && ids.length > 0) {
        // 打印 ThreadInfo，或触发告警 / dump
        log.error("检测到死锁线程: {}", Arrays.toString(ids));
    }
}, 30, 30, TimeUnit.SECONDS);
```

它检测的范围比 `findMonitorDeadlockedThreads()` 广，覆盖 `ReentrantLock` 这类
ownable synchronizer。注意这只是**事后发现**，不能解除死锁，价值在于把
「接口莫名卡死」变成一条明确的告警。

##### Q2.2.1: 检测到死锁之后，线上能做什么？

JVM 层没有安全的「解开」手段，可选的动作是：

1. **先 dump 现场再重启**：`jstack` 保存证据，重启进程恢复服务
2. **分析根因并修加锁顺序**，这是唯一真正的解法
3. 如果死锁由某个可取消的批量任务引入，可以尝试中断相关线程，
   但要注意只有 `lockInterruptibly()` / 可中断的等待才响应中断，
   `synchronized` 的死锁**中断不了**，只能重启
4. 加临时开关下线出问题的功能入口，避免持续有流量走进死锁路径

### Q3: 数据库死锁和 JVM 死锁是一回事吗？

不是。相同点是都满足四个必要条件、都是循环等待。不同点在于**谁来发现、谁来回滚**：
JVM 不会自动处理 `synchronized` 死锁；InnoDB 有死锁检测器，会自动选择并回滚
代价较小的事务，让其他事务继续。所以数据库死锁的表现通常是偶发的 1213 错误，
而不是整个应用卡死。

#### Q3.1: 那数据库死锁就不用管了吗？

要管，只是处理方式不同。捕获 1213 后**重试事务**是标准做法，但要保证重试幂等。
同时要看死锁频率：如果频繁发生，说明事务里加锁顺序不一致或事务过长、锁范围过大，
应该去优化 SQL 和事务边界，而不是靠重试掩盖。还要注意数据库死锁的锁生命周期是
**整个事务**，所以「缩小事务」比「缩小代码块」更关键——事务提交前，
行锁一直有效。

## 常见坑

- **「死锁只要打断循环等待就行」** —— 四个条件破坏任意一个都可以，循环等待只是
  实践中最可行的那一个，说成唯一条件不准确
- **「jstack 只能看线程栈，检测死锁要靠人看」** —— 错，`jstack` 会主动检测并直接输出
  `Found one Java-level deadlock:`
- **「用 Thread.stop() 可以解开死锁」** —— 错。`stop()` 早已废弃，它会抛出 `ThreadDeath`
  并释放线程持有的所有监视器，可能让对象处于不一致状态，比死锁更危险
- **「数据库死锁和 JVM 死锁一样要人工介入」** —— 错，InnoDB 会自动检测并回滚其中
  一个事务，应用收到 1213 后重试即可
- **「锁粒度调小就不会死锁」** —— 粒度小只降低冲突概率，只要还存在不一致的加锁顺序，
  环路依然可能出现
- **「死锁一定是两个线程各持一把锁互相等」** —— 也可能是多个线程、多把锁组成的大环，
  还可能涉及线程池（所有线程都在等提交给同一个池的子任务）或数据库行锁
- **「用了 ReentrantLock 就不会死锁」** —— 错，`ReentrantLock` 默认也是阻塞式获取，
  一样会死锁，除非用 `tryLock(timeout)` 或统一加锁顺序
- **「jstack 没报死锁就是没死锁」** —— 只说明 JVM 层的监视器 / ownable synchronizer
  没有环，数据库行锁、线程池饥饿、无超时的外部调用都可能卡住而 jstack 检测不到

## 加分点

- 能区分死锁、活锁、饥饿：死锁是互相等、谁都动不了；活锁是都在动但一直重试失败、
  整体没进展；饥饿是某些线程长期拿不到资源。三者的排查方向完全不同
- 知道 `findDeadlockedThreads()` 与 `findMonitorDeadlockedThreads()` 的区别，
  以及 `isSynchronizerUsageSupported()` 这个前提，说明用过 `ThreadMXBean` 而不只是背 API
- 提到线程饥饿死锁（thread starvation deadlock）：任务提交到同一个线程池并同步等待结果，
  池里所有线程都占着等子任务，子任务排不进队列。这类死锁 `jstack` 检测不出来，
  处理方式是让子任务用不同线程池，或避免在池内同步等待
- 知道 `System.identityHashCode()` 排序加锁的 hash 冲突需要额外仲裁手段，
  能说清它的边界而不只是背一个技巧
- 提到 MySQL 8.0 的 `performance_schema.data_locks` / `data_lock_waits` 表，
  比对着 `SHOW ENGINE INNODB STATUS` 的文本日志看更直观
- 提到用 JFR 或周期性 `ThreadMXBean` 巡检把死锁从「被动接故障」变成「主动告警」
- 知道虚拟线程不改变死锁的本质：虚拟线程一样会因 `synchronized` 或 `ReentrantLock`
  互相等待而死锁，只是载体线程可能被 pin 住，问题更隐蔽

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 5 | 引入 `ThreadMXBean`、`findMonitorDeadlockedThreads()`（只检测对象监视器）和 `jconsole`，具备了编程检测死锁的能力 |
| Java 6 | 新增 `findDeadlockedThreads()` 与 `isSynchronizerUsageSupported()`，检测范围扩展到 `ReentrantLock` 等 ownable synchronizer |
| Java 6+ | JDK 自带 JVisualVM，提供图形化的死锁检测；Arthas 等外部工具后来补充了 `thread -b` 这类命令 |
| Java 20 | `Thread.stop()` / `suspend()` / `resume()` 退化为抛 `UnsupportedOperationException`，靠停止线程解死锁的思路彻底行不通（历史 API 早已废弃） |
| Java 21+ | 虚拟线程同样会死锁，且在 JDK 24 之前 `synchronized` 阻塞会 pin 住载体线程，使问题表现更隐蔽 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 用 jstack 排查线上死锁时，最关键的输出特征是什么？
    options:
      A: 只输出线程栈，需要人工逐帧比对
      B: 直接打印 Found one Java-level deadlock 以及线程与锁的持有 / 等待关系
      C: 输出 GC 日志
      D: 自动 kill 掉造成死锁的线程
    answer: B
    analysis: jstack 会分析对象监视器和 ownable synchronizer 的等待关系，发现环路时直接打印 Found one Java-level deadlock，并列出每个线程在等哪个锁、锁被谁持有，以及对应的线程栈。它不会自动 kill 线程。
    difficulty: 1

  - type: MULTI
    stem: 以下属于死锁产生必要条件的有？
    options:
      A: 互斥
      B: 请求与保持
      C: 不可剥夺
      D: 循环等待
      E: 优先级反转
    answer: ABCD
    analysis: 死锁的四个必要条件就是互斥、请求与保持、不可剥夺、循环等待，破坏任意一个即可预防，实践中最可行的是破坏循环等待（固定加锁顺序）。优先级反转是实时系统里的另一个问题，不是死锁的必要条件。
    difficulty: 1

  - type: JUDGE
    stem: 数据库出现死锁后必须人工 kill 会话才能解除，否则相关事务会一直阻塞。
    answer: F
    analysis: InnoDB 有死锁检测机制，会自动选择并回滚其中代价较小的事务，被回滚的事务收到 1213 错误码。开启 innodb_deadlock_detect（默认开启）时是主动检测；关闭后才退化为依赖 innodb_lock_wait_timeout 超时。
    difficulty: 2

  - type: CLOZE
    stem: |
      补全下面用超时加锁避免死锁的代码，以及异常路径下释放锁的写法：
      ```java
      if (lockA.{{1}}(1, TimeUnit.SECONDS)) {
          try {
              if (lockB.tryLock(1, TimeUnit.SECONDS)) {
                  try {
                      doWork();
                  } finally {
                      lockB.unlock();
                  }
              }
              // 第二把没拿到，不做事；退出外层 finally 时释放 lockA，稍后重试
          } finally {
              lockA.{{2}}();   // 必须放在 finally，避免异常路径漏放
          }
      }
      ```
    blanks:
      - ["tryLock"]
      - ["unlock"]
    analysis: tryLock 带超时相当于破坏「不可剥夺」——拿不到第二把锁时不无限等待，直接返回并把已持有的锁释放掉，环路就断了。释放必须放在 finally，否则业务抛异常时锁泄露，反而制造新的死锁。
    difficulty: 2
````
