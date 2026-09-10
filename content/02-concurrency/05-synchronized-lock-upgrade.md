---
slug: synchronized-lock-upgrade
title: synchronized 的锁升级是怎么回事？
module: concurrency
tags: [synchronized, 锁升级, 对象头, Mark Word, ObjectMonitor]
difficulty: 3
frequency: 3
related:
  - slug: cas-and-aba
    type: RELATED
  - slug: aqs-principle
    type: CONTRAST
  - slug: jmm-happens-before
    type: RELATED
---

## 电梯版回答

synchronized 的锁升级是指 JVM 按竞争激烈程度给同一把锁换不同的实现，路径大致是 无锁 → 偏向锁 → 轻量级锁 → 重量级锁，而且基本是单向的。加锁的本质是改对象头 Mark Word 里的 2 位锁标志位：只有一个线程反复进入时用偏向锁，把线程 ID 直接写进 Mark Word，之后同一线程进入不再执行 CAS；出现第二个线程竞争时升级为轻量级锁，线程在自己的栈帧里建 Lock Record，用 CAS 把 Mark Word 换成指向该 Lock Record 的指针；CAS 失败且自旋无果就膨胀为重量级锁，改由 HotSpot 的 ObjectMonitor 管理，竞争失败的线程被挂起放进 `_cxq` 或 `_EntryList`。要记住一个版本事实：偏向锁从 Java 15 起默认禁用，Java 18 已从 HotSpot 中移除，所以新版本 JDK 的实际升级路径是 无锁 → 轻量级锁 → 重量级锁。

## 展开讲解

### 加锁加在哪：对象头 Mark Word

HotSpot 对象头由 Mark Word 和类型指针（Klass Pointer）组成，数组对象还多一个 length。32 位 JVM 的 Mark Word 是 32 位，64 位是 64 位。以 64 位为例，Mark Word 是一块被不同锁状态**复用**的内存：

| 状态 | 62 位内容 | 2 位锁标志位（lock） | biased_lock |
|---|---|---|---|
| 无锁 | unused(25) + identity_hashcode(31) + unused(1) + 分代年龄(4) | 01 | 0 |
| 偏向锁 | 线程 ID(54) + epoch(2) + unused(1) + 分代年龄(4) | 01 | 1 |
| 轻量级锁 | 指向栈上 Lock Record 的指针(62) | 00 | — |
| 重量级锁 | 指向 ObjectMonitor 的指针(62) | 10 | — |
| GC 标记 | 空 | 11 | — |

锁标志位只有 2 位，`01` 时靠 `biased_lock` 这一位区分无锁和偏向锁。要特别注意 Mark Word 是复用的：存了线程 ID 就放不下 31 位的 identity hash code，这正是「调用 `Object.hashCode()` 会撤销偏向锁」的根因。

### synchronized 的三种用法与字节码表现

| 用法 | 锁对象 | 字节码表现 |
|---|---|---|
| 修饰实例方法 | `this` | 方法访问标志 `ACC_SYNCHRONIZED` |
| 修饰静态方法 | 该类的 `Class` 对象 | 方法访问标志 `ACC_SYNCHRONIZED` |
| 修饰代码块 | 括号里显式指定的对象 | `monitorenter` / `monitorexit` 指令 |

```java
public void block() {
    synchronized (this) {   // 编译出 monitorenter ... monitorexit
        count++;
    }
}

public synchronized void instanceMethod() { }        // ACC_SYNCHRONIZED，锁 this
public static synchronized void staticMethod() { }   // ACC_SYNCHRONIZED，锁 Class 对象
```

同步代码块编译出的 `monitorexit` 有**两条**：正常出口一条，异常出口一条（编译器生成等价的隐式 try-finally），保证抛异常时也能解锁。方法级则看不到任何指令，加解锁由 JVM 在方法调用和返回时按访问标志隐式完成。

### 升级路径

#### 偏向锁

第一个进入的线程用一次 CAS 把自己的线程 ID 写进 Mark Word，并把 `biased_lock` 置 1、`lock` 保持 01。此后同一线程再进入，只需比较 Mark Word 里的线程 ID 是不是自己，是就直接进入同步块，**不需要任何原子操作**。它优化的场景是「同一个线程反复进入同一把锁且没有其他线程竞争」，比如单线程环境下使用老的 `Vector` / `Hashtable`。

偏向锁的撤销成本很高：要等到一个全局安全点（safepoint），才能修改持有偏向锁线程的栈，判断它是否还活着、是否还在同步块中。撤销后对象要么回到无锁，要么直接膨胀为轻量级锁或重量级锁。历史上还有批量重偏向和批量撤销机制，阈值默认分别是 20 和 40（`BiasedLockingBulkRebiasThreshold` / `BiasedLockingBulkRevokeThreshold`）。

#### 轻量级锁

出现竞争、偏向锁被撤销后，升级为轻量级锁。流程是：

1. 线程在**自己的栈帧**中分配一块 Lock Record 空间，把对象头当前的 Mark Word 拷贝进去，这份拷贝叫 Displaced Mark Word。
2. 用一次 CAS 把对象的 Mark Word 替换为「指向该 Lock Record 的指针」，锁标志位变为 `00`。
3. CAS 成功即获得锁。
4. CAS 失败时先检查对象头是否已指向**当前线程自己**的 Lock Record：是则说明是重入，把新 Lock Record 的 Displaced Mark Word 置为 `null` 后直接返回，不再 CAS。
5. 否则说明是真的竞争，先做少量自适应自旋，仍失败就膨胀为重量级锁。

轻量级锁的「轻」在于不涉及操作系统互斥量、不阻塞线程，竞争失败方是**自旋等待**。它适合竞争不激烈、同步块执行很快的场景。但如果同步块里耗时较长或竞争激烈，自旋会白白烧 CPU，反而不如早点挂起。

#### 重量级锁与 ObjectMonitor

膨胀后对象头的 Mark Word 指向 HotSpot 内部的 `ObjectMonitor`，锁标志位 `10`。ObjectMonitor 的关键字段（见 `src/hotspot/share/runtime/objectMonitor.hpp`）：

| 字段 | 作用 |
|---|---|
| `_owner` | 当前持有锁的线程；未持有时为 null |
| `_recursions` | 重入次数，第一次进入为 0 |
| `_cxq` | 最近到达、竞争失败的线程组成的单向链表（contention queue） |
| `_EntryList` | 等待获取锁的线程列表，`_owner` 释放时从这类队列挑后继 |
| `_WaitSet` | 调用 `Object.wait()` 后进入的等待集合，靠 `notify` / `notifyAll` 唤醒 |
| `_succ` | 指定的继承人线程，用于减少无效唤醒（futile wakeup throttling） |

线程争抢重量级锁失败会先自旋，还不成功就通过 `LockSupport.park()` 挂起。这就是「重量级」的代价：涉及用户态到内核态切换和线程上下文切换。HotSpot 在这条路径上仍保留了**自适应自旋**，根据历史成功率决定自旋次数，尽量避免「刚挂起就立刻被唤醒」。

### 锁粗化与锁消除

这两个是 JIT 编译期优化，和运行期的锁升级不是一回事：

- **锁粗化（Lock Coarsening）**：把一串相邻的、对同一对象的加解锁合并成一次更大的锁。典型场景是循环里反复 `synchronized` 同一个对象，JIT 把锁提到循环外。
- **锁消除（Lock Elision）**：基于逃逸分析判断锁对象不会逃逸出当前线程，直接删掉加解锁。最经典的例子是在方法内部新建 `StringBuffer` 连续 `append`，`append` 是 synchronized 方法，但对象不逃逸，这些锁会被消除。它由 `-XX:+DoEscapeAnalysis`（默认开启）配合 `-XX:+EliminateLocks` 生效。

## 追问链

### Q1: synchronized 加锁到底加在哪？对象头里改了什么？

改的是对象头 Mark Word 里的 2 位锁标志位。64 位 JVM 上 Mark Word 共 64 位，被不同锁状态复用：无锁和偏向锁的标志位都是 `01`，靠 `biased_lock` 位区分；轻量级锁是 `00`，重量级锁是 `10`，GC 标记是 `11`。锁标志位一变，同一块内存里其余位的含义就完全不同了。

#### Q1.1: 那轻量级锁具体是怎么用 CAS 把 Mark Word 换掉的？

线程先在自己的 Java 栈帧里分配一条 Lock Record，把对象头当前的 Mark Word 拷贝到 Lock Record 的第一个字段（Displaced Mark Word），再用一次 CAS 尝试把对象头的 Mark Word 改成指向这条 Lock Record 的指针。语义大致是：

```java
// HotSpot 轻量级锁加锁的语义（伪代码，不是 Java 源码）
mark = obj->mark();
if (mark->is_neutral()) {
    lock->set_displaced_header(mark);        // ① 原 Mark Word 存进栈上 Lock Record
    if (obj->cas_set_mark(lock, mark) == mark) {
        return;                              // ② CAS 成功，获得轻量级锁
    }
}
```

CAS 成功就拿到锁。Lock Record 在**线程自己的栈**上，不分配堆内存、不做系统调用，这是它比重量级锁快的原因。

##### Q1.1.1: 如果 CAS 失败了会怎样？

先判断是不是重入：如果对象头的 Mark Word 已经指向**当前线程自己**的 Lock Record，说明这个线程已经持有锁，于是再压入一条 Lock Record，把它的 Displaced Mark Word 置为 `null`，然后直接返回、不再 CAS。`null` 就是重入标记，解锁时看到 `null` 只弹出这条记录、不写回对象头。

如果不是重入，说明确实有别的线程在竞争。这时会先做自适应自旋，自旋期间锁也许就被释放了；如果还拿不到，就把锁**膨胀**为重量级锁，把 Mark Word 改成指向 ObjectMonitor，尚未获得锁的线程进入 ObjectMonitor 的等待队列并挂起。

#### Q1.2: 偏向锁看起来连 CAS 都省了，凭什么敢这么做？

因为它的前提是「只有一个线程在用它」。第一个线程进入时仍要做一次 CAS，把线程 ID 写进 Mark Word 并把 `biased_lock` 置 1；但从第二次开始，只需读一下 Mark Word 里的线程 ID 是不是自己，是就直接进入同步块，连 CAS 都不需要。

它赌的是「无竞争」这个事实不会变。一旦出现另一个线程，就得先撤销偏向锁，撤销代价远高于省下的那几次 CAS——所以这个赌注只有在确实无竞争时才划算。

### Q2: 偏向锁什么时候会被撤销？

**其他线程尝试获取锁**是最主要的场景。此外还有几个容易漏掉的：

- 对象调用了 `Object.hashCode()`（或未重写的 `System.identityHashCode`）——偏向锁的 Mark Word 里放不下 31 位的 identity hash code，必须撤销。
- 对象调用了 `Object.wait()` / `notify()`——这些方法要求重量级锁，直接膨胀。
- 触发了批量重偏向 / 批量撤销，或对 `Class` 对象做了相关操作。

撤销必须走到**全局安全点（safepoint）**，停下来遍历持有偏向锁的线程栈，判断它是否还活着、是否还在同步块里。

#### Q2.1: 撤销为什么要等 safepoint？代价是什么？

因为要让正在使用该锁的线程「停下来」并检查它的栈帧状态：持有偏向锁的线程可能正在同步块里，也可能已经退出同步块但 Mark Word 还没改回来。只有停在安全点，JVM 才能安全地修改它的栈和对象头。

代价是撤销期间**所有线程一起停顿**（STW）。如果一批同类型对象在高并发下被反复偏向又撤销，会出现「偏向锁撤销风暴」，表现为大量 safepoint 停顿，反而比直接用轻量级锁更慢。这也为后来禁用偏向锁埋下了伏笔。

##### Q2.1.1: 既然偏向锁有收益，为什么 Java 15 要默认禁用它、Java 18 直接移除？

JEP 374（JDK 15）的结论是：偏向锁的收益已经远小于它的维护与性能成本。理由有三：

- 撤销需要 safepoint，在高并发、锁竞争频繁的现代应用里，撤销风暴带来的 STW 停顿会拖垮响应时间，收益可能为负。
- 实现深度耦合对象头、类元数据、safepoint 和线程栈，维护成本高，还限制了 Mark Word 布局的演进。
- 现代 JVM 的轻量级锁加自适应自旋已经足够便宜，偏向锁省下的那次 CAS 相对整体开销常常微不足道。

于是 JDK 15 默认关闭（`-XX:+UseBiasedLocking` 仍可显式开启，但会打 deprecation 警告），JDK 18 移除了实现，该选项变成 obsolete。**这个版本事实很关键：面试里说「锁升级一定经过偏向锁」在高版本 JDK 上已经是错的。**

### Q3: 锁粗化和锁消除又是什么？跟锁升级是一回事吗？

不是一回事。锁升级是运行期的**竞争自适应**，发生在同步块真正执行、线程争抢锁的时候；锁粗化和锁消除是 JIT 编译期的**代码优化**，发生在字节码编译成机器码的阶段。

- 锁消除靠逃逸分析：`StringBuffer sb = new StringBuffer(); sb.append(a).append(b);` 里的 `append` 是 synchronized 方法，但 `sb` 只在方法内使用、不逃逸，JIT 直接把锁去掉。
- 锁粗化把相邻的、同一对象的加解锁合并，循环里反复加解锁尤其明显。

#### Q3.1: 那是不是可以依赖 JIT 优化，随便加 synchronized？

不能，有三个现实约束：

- 锁消除只在**不逃逸**时生效。对象一旦被发布出去（赋给字段、作为返回值、传给别的线程），逃逸分析判定为逃逸，锁消除失效。
- 锁消除属于 C2 编译期优化。方法还没被 JIT 编译时（解释执行阶段）锁是实实在在存在的，低流量时反而可能看到加锁开销。
- 关掉逃逸分析（`-XX:-DoEscapeAnalysis`）或使用某些调试、低配模式时，优化不生效。

所以结论是：这些优化用来减少**不必要的**锁，而不是给「到处加锁」背书。该缩小同步块还是要缩小，该换 `ReentrantLock` 的场景（可中断、可超时、支持公平、多个条件变量）还是要换。

## 常见坑

- **「synchronized 一直是重量级锁」** —— 这是 Java 6 之前的情况。JDK 6 引入了偏向锁、轻量级锁和自适应自旋，只有真正竞争时才膨胀为重量级锁
- **「锁会随竞争缓解自动降级」** —— 升级基本是单向的。一旦膨胀为重量级锁就不会回到轻量级锁；偏向锁被撤销后也不会再偏回去（批量重偏向是特例，不算降级）
- **「轻量级锁是靠自旋实现的」** —— 不准确。轻量级锁靠的是 **CAS 替换 Mark Word**，自旋只在 CAS 失败后作为过渡手段，超过阈值就膨胀
- **「偏向锁就是没加锁」** —— 偏向锁仍然是锁，只是无竞争时省掉了原子操作；而且它的撤销需要 safepoint
- **「调用 hashCode() 对锁没有影响」** —— 对偏向锁有影响：Mark Word 存不下 identity hash code，会触发撤销
- **「锁消除是运行时根据竞争情况做的优化」** —— 锁消除、锁粗化是 JIT 编译期基于逃逸分析的优化，跟竞争状况无关
- **「方法上的 synchronized 会编译出 monitorenter」** —— 方法级用的是 `ACC_SYNCHRONIZED` 访问标志，只有同步代码块才编译出 `monitorenter` / `monitorexit`
- **「Java 21 的 synchronized 完全不会阻塞载体线程」** —— Java 21 里虚拟线程在 synchronized 里阻塞仍会 pin 住载体线程，直到 JDK 24 的 JEP 491 才解决

## 加分点

- 能说出轻量级锁的可重入实现细节：重入时压入的 Lock Record 的 Displaced Mark Word 是 **null**，解锁时看到 null 只弹栈不写回。这和偏向锁的「线程 ID 比较」、重量级锁的 `_recursions` 计数是三套完全不同的重入机制
- 知道 ObjectMonitor 里 `_cxq` 和 `_EntryList` 的分工：新到达的竞争线程先进 `_cxq`，`_owner` 释放时按策略把 `_cxq` 的节点转移到 `_EntryList` 再挑后继；`_WaitSet` 是 `wait()` 待的地方，和竞争队列完全分开，这也是 `notify` 只唤醒一个等待者的实现基础
- 知道 `_succ` 是「继承人」标记，作用是减少无效唤醒（futile wakeup throttling）
- 能给出验证手段：用 JOL（`org.openjdk.jol:jol-core` 的 `org.openjdk.jol.info.ClassLayout`）打印对象头，或启动时加 `-XX:+PrintFlagsFinal` 查看 `UseBiasedLocking` 的实际取值
- 知道偏向锁的批量重偏向 / 批量撤销阈值历史上默认是 20 / 40，用于应对「一批同类对象被不同线程轮流使用」的场景，避免每个对象都单独走一次 safepoint 撤销
- 能横向对比 synchronized 和 ReentrantLock：前者由 JVM 管、不可中断、不可超时、无法指定公平策略、只有一个条件队列；后者基于 AQS，可中断、可超时、可选公平、可建多个 Condition。而 JVM 层优化（锁升级、锁消除）是 synchronized 独有的
- 提到「对象头在 GC 期间被复用为标记位（lock = 11）」，说明 Mark Word 是 JVM 里被反复复用的稀缺资源，这能解释为什么偏向锁的 Mark Word 布局受限

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 6 | 引入偏向锁、轻量级锁与自适应自旋，synchronized 不再「一上来就是重量级锁」 |
| Java 8 ~ 13 | 偏向锁默认开启，可用 `-XX:-UseBiasedLocking` 关闭 |
| Java 15 | JEP 374：偏向锁默认禁用并标记为 deprecated，`-XX:+UseBiasedLocking` 需显式开启且会告警 |
| Java 18 | 从 HotSpot 移除偏向锁实现，`-XX:+UseBiasedLocking` 变成 obsolete 选项；实际路径只剩 无锁 → 轻量级锁 → 重量级锁 |
| Java 21 | 虚拟线程在 synchronized 中阻塞会 pin 住载体线程，官方建议此时改用 ReentrantLock |
| Java 24 | JEP 491：虚拟线程获取、持有、释放 monitor 不再绑定载体线程，synchronized 基本不再造成 pinning |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 轻量级锁加锁时，CAS 把对象头的 Mark Word 替换成了什么？
    options:
      A: 指向 ObjectMonitor 的指针
      B: 指向线程栈上 Lock Record 的指针
      C: 当前线程的 ID
      D: 对象的 identity hash code
    answer: B
    analysis: 轻量级锁在获得锁的线程自己的栈帧里分配 Lock Record，把原 Mark Word 存为 Displaced Mark Word，再用 CAS 把对象头换成指向该 Lock Record 的指针，锁标志位为 00。指向 ObjectMonitor 的是重量级锁（标志位 10），线程 ID 是偏向锁。
    difficulty: 2

  - type: MULTI
    stem: 以下关于对象头 Mark Word 与偏向锁的说法正确的有？
    options:
      A: 锁标志位为 00 表示轻量级锁
      B: 重量级锁的 Mark Word 指向 ObjectMonitor
      C: 调用 Object.hashCode() 可能触发偏向锁撤销
      D: 偏向锁从 Java 8 起默认禁用
    answer: ABC
    analysis: D 错误。偏向锁在 Java 8 到 Java 13 默认开启；JEP 374 在 Java 15 才默认禁用，Java 18 移除实现。C 正确，因为偏向锁的 Mark Word 里没有空间存放 31 位 identity hash code。
    difficulty: 2

  - type: JUDGE
    stem: synchronized 修饰实例方法时，编译后的字节码里会生成 monitorenter 和 monitorexit 指令。
    answer: F
    analysis: 方法级 synchronized 用的是方法访问标志 ACC_SYNCHRONIZED，加解锁由 JVM 在方法调用和返回时隐式完成，字节码里没有 monitorenter/monitorexit。只有 synchronized 代码块才编译出这两条指令。
    difficulty: 1

  - type: CLOZE
    stem: |
      补全轻量级锁加锁流程中被替换的值，以及重入时的标记：
      ```java
      // 线程在栈上分配 Lock Record，保存原 Mark Word 到 displaced header
      lock.set_displaced_header(mark);
      // CAS 把对象头的 Mark Word 换成指向 Lock Record 的指针，
      // 成功后对象头的 2 位锁标志位是 {{1}}
      if (obj.cas_set_mark(lock, mark) == mark) {
          return;  // 加锁成功
      }
      // 若是重入，新压入的 Lock Record 的 displaced header 会被置为 {{2}}，
      // 解锁时看到它只弹栈、不写回对象头
      ```
    blanks:
      - ["00", "0b00"]
      - ["null", "NULL", "空"]
    analysis: 轻量级锁的锁标志位是二进制的 00。重入时不需要再次 CAS，只需压入一条 Displaced Mark Word 为 null 的 Lock Record，null 就是重入标记，解锁时据此只弹出记录而不把 Mark Word 写回对象头。
    difficulty: 3
````
