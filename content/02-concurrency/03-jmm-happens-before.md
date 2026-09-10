---
slug: jmm-happens-before
title: 讲一下 JMM 和 happens-before 规则？
module: concurrency
tags: [JMM, happens-before, 内存模型, 重排序]
difficulty: 3
frequency: 3
related:
  - slug: volatile-visibility
    type: DEEPEN
  - slug: synchronized-lock-upgrade
    type: RELATED
---

## 电梯版回答

JMM 是 Java 语言规范里定义的一套多线程内存语义，不是某种硬件内存模型，它由 JSR-133 在 Java 5 定型，写在 JLS 第 17 章。它为每个线程抽象出工作内存、把共享变量放在主内存，用 happens-before 描述一个操作的结果何时对另一个线程可见、哪些重排序不被允许，要解决的就是原子性、可见性、有序性这三大特性。重排序有三个来源：编译器、指令级并行和内存系统，JMM 不禁止它们，只用八条 happens-before 规则来约束可观察的结果，这八条是程序顺序、监视器锁、volatile 变量、线程启动、线程终止、中断、对象终结和传递性，其中传递性把各条局部规则串成全局。对单线程，as-if-serial 保证重排序不改变结果；对多线程，JMM 承诺只要程序是正确同步的、不存在数据竞争，执行结果就等价于顺序一致性模型。反过来，有数据竞争的程序不受这套保证。

## 展开讲解

### JMM 是一份规范，不是硬件内存模型

JMM（Java Memory Model）由 JSR-133 在 Java 5 正式定型，定义在 JLS 第 17 章。
它是**语言级规范**：规定多线程程序里一个线程对共享变量的写何时对另一个线程可见、
哪些重排序允许、哪些不允许。它刻意屏蔽了 x86 的 TSO、ARM 的弱内存模型等平台差异，
让同一份 Java 代码在所有平台上得到一致的语义——一致性由 JVM 通过插入屏障指令来实现。

所以「JMM 就是 CPU 缓存和主内存」的说法是错的。JMM 描述的是**抽象的内存**，
真正落到硬件上时，线程间的可见性靠 CPU 的缓存一致性协议（如 MESI）保证，
JMM 只是在上面规定了语言层必须遵守的约束。

### 主内存与工作内存的抽象

JMM 规定：所有变量存在主内存；每个线程有自己的工作内存，线程对变量的读写都在
工作内存里进行，不能直接操作主内存。线程间传递变量值必须经过主内存，
即「A 写 → 刷回主内存 → B 从主内存重新读」。

这个「工作内存」是抽象概念，对应 CPU 寄存器、store buffer、各级缓存以及编译器优化
结果的综合，**不是每个线程真的有一块专属内存**。把工作内存当成物理内存去解释，
就容易得出「volatile 就是在线程之间拷贝变量」这种错误结论。

### 三大特性

| 特性 | 含义 | JMM 里靠什么保证 |
|---|---|---|
| 原子性 | 操作不可分割 | 规范只保证基本类型的单次读写原子（非 volatile 的 `long`/`double` 在规范上允许拆成两个 32 位操作，见 JLS 17.7）；`i++` 这类复合操作要靠 `synchronized`、锁或 CAS |
| 可见性 | 一个线程的修改其他线程能看到 | `volatile`、`synchronized`、`final` 字段、锁的释放与获取 |
| 有序性 | 禁止破坏语义的重排序 | happens-before 约束、`volatile`、`synchronized` |

注意一个关键点：JMM **并不**给复合操作提供原子性，它主要提供的是可见性和有序性
约束，再加上对「正确同步程序」的整体承诺。

### 重排序的三种来源

重排序是性能优化的正常手段，不是 bug：

1. **编译器重排序**：`javac` 和 JIT 在不改变单线程语义的前提下调整指令顺序，
   例如把循环内不变量提到循环外。
2. **指令级并行重排序**：CPU 乱序执行，多条指令重叠，改变了实际的访存顺序。
3. **内存系统重排序**：store buffer、写合并导致写操作延迟对其他核心可见，
   写入顺序与程序顺序不一致。

三层都可能重排，JMM 不禁止其中任何一层，只要求「存在 happens-before 约束的地方，
重排序不能产生可被观察到的违反」。这也是为什么讨论重排序时不能只说「编译器会乱序」。

### as-if-serial 语义

单线程内，重排序可以任意进行，只要不改变程序的执行结果——这就是 as-if-serial。
存在数据依赖的操作（写后读、写后写、读后写）不能在单线程可观察意义上被重排，
因为那会改变结果。

as-if-serial 让单线程程序员完全不必关心内存模型；而 happens-before 的作用，
是把同样的「结果不变」保证扩展到跨线程。

### happens-before 的八条规则

happens-before 是一个**偏序关系**（partial order），不是全序。JLS 17.4.5 给出的八条：

| # | 规则 | 内容 | 典型场景 |
|---|---|---|---|
| 1 | 程序顺序规则 | 同一线程内，按程序顺序靠前的操作 happens-before 靠后的操作 | 单线程前后依赖 |
| 2 | 监视器锁规则 | 对同一个监视器的解锁 happens-before 后续的加锁 | `synchronized` 释放/获取 |
| 3 | volatile 变量规则 | 对同一个 volatile 字段的写 happens-before 后续的读 | 状态标志位 |
| 4 | 线程启动规则 | `Thread.start()` 调用 happens-before 新线程里的所有操作 | 启动线程前初始化数据 |
| 5 | 线程终止规则 | 线程内所有操作 happens-before 其他线程从 `join()` 返回或 `isAlive()` 检测到终止 | 主线程等子线程结果 |
| 6 | 中断规则 | 线程 A 调用 B 的 `interrupt()` happens-before B 检测到中断（抛 `InterruptedException` 或 `isInterrupted()` 返回 true） | 中断响应 |
| 7 | 对象终结规则 | 对象构造方法结束 happens-before 该对象 `finalizer` 开始 | 终结器看到完整状态 |
| 8 | 传递性 | A happens-before B 且 B happens-before C，则 A happens-before C | 把局部规则串成全局 |

两条理解要点：

- **有 happens-before 关系，不等于在物理时间上先执行。** 它只保证前一个操作的结果
  对后一个可见，且后一个操作不能被重排到前一个之前。实际执行顺序可以不同，
  只要观察不到违反。
- **没有 happens-before 关系，不代表一定出错**，只表示 JMM 允许竞争、
  不承诺结果。规范的承诺是单向的。

传递性是这八条里最有用的一条。看这个经典组合：

```java
int x = 0;
volatile boolean ready = false;

// 线程 A
x = 1;              // (1) 普通写
ready = true;       // (2) volatile 写

// 线程 B
if (ready) {        // (3) volatile 读，读到 true
    System.out.println(x);  // (4) 一定打印 1
}
```

推导链是：`(1)` 程序顺序 happens-before `(2)`；`(2)` volatile 写 happens-before
`(3)` volatile 读；`(3)` 程序顺序 happens-before `(4)`；再由传递性得到
`(1)` happens-before `(4)`。这就是状态标志位这种用法的原理。

### JMM 对「正确同步程序」的承诺

什么叫正确同步？程序里所有对共享变量的读写都被 happens-before 串起来，
不存在数据竞争。这种程序 JMM 承诺：**执行结果与顺序一致性模型完全一致**。
换句话说，程序员可以放心地按「顺序一致」的直觉去读加锁或 volatile 的代码，
不用在脑子里模拟重排序。

反过来，存在数据竞争的未同步程序，JMM 不保证任何合理结果——可能读到旧值，
可能观察到违反直觉的重排。JMM 是给「用对了同步手段的程序」兜底的规范，
不是替所有程序消灭并发问题。

## 追问链

### Q1: 你刚提到 happens-before，它和重排序是什么关系？

happens-before 是 JMM 用来**约束**重排序的工具。JVM 实现时把 happens-before
要求翻译成具体的屏障指令：比如 volatile 写前插 StoreStore、写后插 StoreLoad，
让编译器不敢乱排、CPU 不敢乱序。所以对程序员来说 happens-before 是规范，
对 JVM 来说它是内存屏障的执行依据。

#### Q1.1: 那 happens-before 是不是就表示两个操作在时间上严格按先后执行？

不是。happens-before 是**可见性加顺序约束**，不要求真实执行顺序。
A happens-before B 的含义是：A 的结果对 B 可见，且 B 不能在 A 之前被观察到执行，
但物理时钟上 A 完全可能晚于 B 完成（只要没有第三方能观察到这个差异）。
把 happens-before 理解成「先发生」是中文翻译带来的最大误导。

##### Q1.1.1: 那单线程里的 as-if-serial 跟它又是什么关系？

两者是配套的。as-if-serial 管单线程：重排序随便做，结果不能变。
happens-before 管跨线程：把「结果不变」从「单线程内的数据依赖」推广到
「跨线程的同步动作」。在被同步动作串起来的那些操作之间，效果等同于没被重排；
不在 happens-before 链上的操作，允许重排。所以一个程序里两种情况可以同时存在：
同一线程内靠数据依赖兜底，跨线程靠 happens-before 兜底。

##### Q1.1.2: 既然 happens-before 不保证实时性，volatile 写之后另一个线程要过多久才能看到？

JMM 不承诺时间上界。它保证的是「后续对同一 volatile 变量的读取会看到这次写」，
而不是「马上看到」。可见性保证的是不会永远读到旧值、不会读到中间态，
具体传播延迟取决于缓存同步和调度。需要实时性要靠别的机制，
不能把 volatile 当通知通道做精确时序。

#### Q1.2: 传递性具体是怎么把两个线程串起来的？

它是让「本线程内的顺序」和「跨线程的同步点」拼接的胶水。上面的例子就是：
线程 A 内部程序顺序给出 `(1) → (2)`，volatile 规则给出 `(2) → (3)`，
线程 B 内部程序顺序给出 `(3) → (4)`，三段一接，`(1)` 就跨线程可见于 `(4)`。
没有传递性，前两条规则只能各自管一个线程，无法得出跨线程结论。

### Q2: 八条规则里，volatile 规则和监视器锁规则看起来都在保证可见性，那 volatile 能替代 synchronized 吗？

不能。监视器锁规则保证的是解锁 happens-before 后续加锁，它是**互斥的临界区语义**：
既保证可见性，也让临界区内的复合操作整体原子。volatile 规则只针对单个字段的
一次写和之后的一次读，管不了「读-改-写」这种复合操作，也不提供互斥。
两者是不同层级的手段，不是替代关系。

#### Q2.1: 那 final 字段有没有类似的保证？

有，但它不在八条 happens-before 规则里，而是 JLS 17.5 单独规定的 final 字段语义：
只要对象被正确构造、引用没有在构造过程中逸出，那么构造方法结束时对 final 字段的
写入对其他线程可见。这是「安全发布」的一条重要保障，也解释了为什么不可变对象
天然线程安全。

##### Q2.1.1: 如果构造方法里把 this 提前逸出，比如在构造里启动线程或者注册监听器，会怎样？

final 字段的保证会被破坏。因为引用在构造完成前就被其他线程看到，
那些线程可能读到 final 字段的默认值（0 或 null），等于拿到了半初始化对象。
正确做法是构造方法内不发布 this，先构造完再注册或启动；
这也是双重检查锁必须有 volatile 参与限制重排序的同类问题。

## 常见坑

- **说「JMM 是硬件内存模型」** —— 错。JMM 是 JSR-133 定义的**语言级规范**，
  定义在 JLS 第 17 章，屏蔽了 x86/ARM 的平台差异；硬件内存模型是 CPU 手册里的东西
- **把 happens-before 理解成「时间上先发生」** —— 错。它只约束可见性和不被重排跨过，
  不规定物理执行时刻
- **说「工作内存就是线程自己的缓存，volatile 就是把变量拷贝成多份」** —— 错。
  工作内存是抽象概念，真实可见性靠缓存一致性协议
- **说「重排序只有编译器会做」** —— 错。编译器、指令级并行、内存系统三层都会重排
- **说「八条 happens-before 规则是 JVM 的重排序实现」** —— 规则是规范约束，
  内存屏障才是 JVM 的实现手段，两者是「要求」与「实现」的关系
- **说「未同步程序 JMM 也保证顺序一致」** —— 错。只对正确同步（无数据竞争）的程序
  承诺等价于顺序一致性
- **认为「没有 happens-before 关系就一定出问题」** —— 不。它只表示 JMM 不承诺结果，
  实际是否出错取决于平台和运气

## 加分点

- 能说出 JMM 的核心定理：**DRF → SC**（Data-Race-Free implies Sequentially Consistent），
  即无数据竞争的程序等价于顺序一致，这是 JSR-133 最重要的结论，
  也是加锁编程可以凭直觉推理的依据
- 主动区分规范与实现：JMM 是规范，JVM 用内存屏障（LoadLoad/StoreStore/LoadStore/StoreLoad，
  出自 JSR-133 Cookbook）实现，x86 因 TSO 模型只需 StoreLoad 是真屏障，
  其他三种常为空操作，而 ARM 上则是真实指令
- 知道 `final` 字段语义（JLS 17.5）是八条规则之外的额外保证，与安全发布直接相关
- 知道 Java 9 起 `VarHandle` 暴露了 Plain / Opaque / Acquire / Release / Volatile
  五档内存序，能比「要么普通、要么 volatile」更精细地表达意图
- 能解释为什么规范要设计得这么弱：给 JIT 和 CPU 留优化空间。
  如果 JMM 要求所有操作顺序一致，所有优化都得关掉，性能不可接受；
  happens-before 只在必要处约束，是一种「最小必要承诺」

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 1.4 及以前 | 旧内存模型，`volatile` 语义弱、`final` 字段没有安全发布保证，双重检查锁不可靠 |
| Java 5（JSR-133） | 重新定义 JMM，正式给出 happens-before 八条规则；修订 `volatile` 与 `final` 字段语义，是这套知识点的分水岭 |
| Java 9 | `VarHandle`（JEP 193）暴露五档内存序访问模式，可在语言层显式表达比 volatile 更弱或同级的约束 |
| Java 17 / 21 | JMM 本体未再变更；虚拟线程沿用同一套 happens-before，`Thread.start()` 与 `join()` 的语义不变 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 以下哪一项不属于 JLS 规定的 happens-before 八条规则？
    options:
      A: 程序顺序规则
      B: 监视器锁规则
      C: 缓存一致性规则
      D: 传递性规则
    answer: C
    analysis: 八条规则是程序顺序、监视器锁、volatile 变量、线程启动、线程终止、中断、对象终结和传递性。缓存一致性是 CPU 硬件层面的机制（如 MESI），不是 JMM 的 happens-before 规则。
    difficulty: 2

  - type: MULTI
    stem: 关于重排序与 JMM，下列说法正确的有？
    options:
      A: 重排序可能来自编译器、指令级并行和内存系统三层
      B: as-if-serial 保证单线程程序被重排序后结果不变
      C: 两个操作存在 happens-before 关系就意味着它们在物理时间上严格先后
      D: JMM 只对正确同步、无数据竞争的程序承诺顺序一致性
    answer: ABD
    analysis: C 错误。happens-before 只保证可见性和不被重排跨过，不规定物理执行时刻。
    difficulty: 3

  - type: JUDGE
    stem: happens-before 关系保证了前一个操作的结果对后一个操作可见，但不要求两者在时间上按该顺序执行。
    answer: T
    analysis: 这正是 happens-before 容易被误解的点——它是偏序的可见性约束，不是时序。
    difficulty: 2

  - type: CLOZE
    stem: |
      下面这段状态标志位的代码，线程 B 一定能打印出 1。补全保证这一结论的规则名与读到的值：
      ```java
      int x = 0;
      volatile boolean ready = false;

      // 线程 A
      x = 1;              // (1) 普通写
      ready = true;       // (2) volatile 写

      // 线程 B
      if (ready) {        // (3) volatile 读，读到 true
          System.out.println(x);  // (4) 由 {{1}} 规则保证能看到 x = {{2}}
      }
      ```
    blanks:
      - ["传递性", "传递性规则", "happens-before 传递性"]
      - ["1"]
    analysis: 程序顺序给出 (1)→(2) 与 (3)→(4)，volatile 变量规则给出 (2)→(3)，再由传递性得到 (1)→(4)。所以 (1) 写入的 x = 1 对 (4) 可见。
    difficulty: 3
````
