---
slug: volatile-visibility
title: volatile 是怎么保证可见性和有序性的？
module: concurrency
tags: [volatile, 可见性, 内存屏障, 双重检查锁]
difficulty: 3
frequency: 3
related:
  - slug: jmm-happens-before
    type: PREREQUISITE
  - slug: synchronized-lock-upgrade
    type: CONTRAST
  - slug: cas-and-aba
    type: RELATED
---

## 电梯版回答

volatile 保证可见性和有序性，但不保证原子性。可见性上，volatile 写会把新值刷回主内存并对其他线程可见，volatile 读会绕过寄存器和本地副本直接读主内存，从而看到最新值；有序性上，JVM 在 volatile 写的前后、读的前后插入内存屏障，写前是 StoreStore、写后是 StoreLoad、读后是 LoadLoad 和 LoadStore，禁止特定重排序，其中 StoreLoad 开销最大，在 x86 上通常用带 lock 前缀的指令实现。它不保证原子性，因为 i++ 是读-改-写三步，volatile 只能保证每一步读写各自原子，整体仍会丢失更新。和 synchronized 相比，volatile 不阻塞、不提供互斥，所以不能替代锁。典型用途是状态标志位、一次性安全发布，以及双重检查锁单例里修饰 instance——没有 volatile，分配内存、初始化对象、赋值引用这三步可能重排序，别的线程会拿到还没初始化完的对象。

## 展开讲解

### 语义：volatile 是一条 happens-before 规则

从 JMM 看，volatile 的全部语义就是 JLS 17.4.5 里的「volatile 变量规则」：

> 对同一个 volatile 字段的写 happens-before 后续对该字段的读。

这一条同时带来了可见性和有序性：

- **可见性**：写 happens-before 读，意味着写在读之前的结果对读可见。
  线程 B 读到 volatile 变量能保证看到线程 A 在此之前的写入。
- **有序性**：happens-before 约束重排序。volatile 写之前的普通写不能被重排到
  volatile 写之后，volatile 读之后的普通读写不能被重排到 volatile 读之前。

### 可见性：写刷主内存、读绕开副本

线程对变量的读写本可以在工作内存（CPU 寄存器、缓存、store buffer）里进行，
这正是可见性问题的来源。volatile 做的约束是：

- **volatile 写**：写操作必须让其他线程可见，编译器不能把这个值长期留在寄存器里
  或只写进本地写缓冲；在硬件层，x86 用带 `lock` 前缀的指令把 store buffer 刷出、
  通过 MESI 一致性协议让其他核心的对应缓存行失效。
- **volatile 读**：必须重新从主内存（或一致性协议保证一致的位置）读取，
  不能复用寄存器里缓存的旧值，也不能被 JIT 常量折叠掉。

所以「写后其他线程能读到新值」的准确表述是：后续对同一 volatile 变量的读
会看到这次写。JMM 不承诺「立刻」，只承诺不会永远读到旧值、不会读到中间态。

### 有序性：四种内存屏障

JMM 把 happens-before 要求翻译成内存屏障。屏障按它隔开的两种访存操作命名，
共四种：

| 屏障 | 作用 | 插入位置 |
|---|---|---|
| LoadLoad | 屏障前的读先于屏障后的读完成 | volatile **读之后** |
| StoreStore | 屏障前的写先于屏障后的写对其他线程可见 | volatile **写之前** |
| LoadStore | 屏障前的读先于屏障后的写完成 | volatile **读之后** |
| StoreLoad | 屏障前的读写都先于屏障后的读写完成，最重 | volatile **写之后** |

对应到一段 volatile 访问，屏障序列是：

```
volatile 写:  StoreStore |  store volatile  | StoreLoad
volatile 读:  load volatile  | LoadLoad | LoadStore
```

为什么 volatile 写后要加 StoreLoad 而 volatile 读后只加 LoadLoad/LoadStore：
写之后如果紧跟一次读，需要保证这次写在别的线程读到之前已经可见，
这是唯一需要等待内存系统的屏障。x86 的 TSO 模型下，
LoadLoad/StoreStore/LoadStore 都退化为空操作，只有 StoreLoad 需要真实指令，
一般实现为 `lock` 前缀指令或 `mfence`。这也是「volatile 写比普通写贵得多、
volatile 读几乎和普通读一样快」的原因。

在 ARM 这类弱内存模型平台上，四种屏障都可能是真实指令，代价更明显。
这也是代码必须依赖 JMM 而不是「反正 x86 是强内存」的原因——JIT 编译器同样要被约束，
且同一份字节码要跨平台正确。

### 为什么不保证原子性

`i++` 在字节码层面是三步：

```java
// i++; 等价于
int tmp = i;    // 读
tmp = tmp + 1;  // 改
i = tmp;        // 写
```

volatile 只保证「读」和「写」各自是原子的、可见的，但三步之间可以被其他线程插入。
两个线程同时读到 i = 5，各自加 1 写回 6，最终结果就是 6 而不是 7，典型的丢失更新。
所以「volatile 保证了原子性」是这张卡片最需要纠正的错误说法。

单次读或写确实是原子的，这也是 volatile 另一个作用：`volatile long` / `volatile double`
的读写按 JLS 17.7 是原子的，而普通 `long`/`double` 在规范上允许被拆成两个 32 位操作
（实际 HotSpot 64 位实现通常也是原子的，但规范不保证）。

### volatile 与 synchronized 对比

| 维度 | volatile | synchronized |
|---|---|---|
| 可见性 | 保证 | 保证 |
| 有序性 | 保证 | 保证 |
| 原子性 | **不保证复合操作** | 保证临界区整体原子 |
| 是否阻塞 | 不阻塞、无上下文切换 | 可能阻塞、涉及监视器获取 |
| 互斥 | 无 | 有 |
| 作用范围 | 单个变量 | 代码块或方法 |
| 适用 | 状态标志、安全发布、一写多读 | 复合操作、多变量不变式 |

结论：volatile 比锁轻，但能力也弱；它不能替代锁。判断标准是——
若操作可以归结为「对单个变量的独立读写」，volatile 够用；
一旦涉及「读-改-写」或「多个变量必须一起变」，就必须用锁或原子类。

### 经典用途一：状态标志位

```java
public class Worker implements Runnable {
    private volatile boolean stopped = false;

    public void shutdown() {
        stopped = true;
    }

    @Override
    public void run() {
        while (!stopped) {
            // doWork
        }
    }
}
```

没有 volatile 时，`stopped` 可能被 JIT 优化成常量读或被缓存在寄存器里，
循环永远不退出。这是 volatile 最基础也最典型的用法。

### 经典用途二：双重检查锁单例

```java
public class Singleton {
    private static volatile Singleton instance;

    public static Singleton getInstance() {
        if (instance == null) {                  // 第一次检查，避免每次加锁
            synchronized (Singleton.class) {
                if (instance == null) {          // 第二次检查，防止重复创建
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }
}
```

`instance = new Singleton()` 不是一步，而是大致三步：

1. 给对象分配内存
2. 执行构造方法，初始化字段
3. 把引用赋给 `instance`

没有 volatile 时，2 和 3 可能被重排序。线程 A 执行到 3 但还没完成 2 时，
线程 B 在第一次检查处看到 `instance != null`，直接把一个字段还是默认值
（0 / null）的半初始化对象返回出去。volatile 写前的 StoreStore 屏障
禁止前面的构造写被排到引用赋值之后，从而杜绝这种发布。

### 经典用途三：一次性安全发布

`volatile` 的写-读构成一次同步，可以把「初始化好的整个对象状态」安全地发布出去。
这和状态标志位是同一个原理，DCL 只是它的一种特例。

## 追问链

### Q1: 你说 volatile 靠内存屏障实现，那四种屏障里为什么 StoreLoad 开销最大？

因为只有它要求「之前的写已经对其他线程可见，之后的读才开始」。
这需要把 store buffer 清空、等待缓存一致性同步完成，x86 上就是带 `lock`
前缀的指令或 `mfence`，基本是一次内存级同步。其余的 LoadLoad / StoreStore /
LoadStore 在 x86 的总存储序（TSO）模型下天然成立，编译成空操作即可。

#### Q1.1: 那是不是说明 x86 平台上 volatile 读几乎没有成本？

接近，但要说准确：在 x86 的 TSO 下 volatile 读不需要额外屏障指令，
代价和普通读差不多。但「没有硬件屏障」不等于「没有约束」——
JIT 编译器仍然不能把 volatile 读的结果缓存进寄存器，也不能把后续读写提到它前面，
否则在别的平台上就会错。

##### Q1.1.1: 为什么不能干脆依赖「目标机器是 x86 所以不用管」？

两点。第一，Java 字节码要跨平台，同一份代码可能在 ARM 服务器、Android
或其他弱内存模型上运行，JMM 保证语义一致，不能依赖具体 CPU。
第二，重排序不只发生在 CPU，JIT 编译器自己也会重排，
即使硬件是强内存模型也要用屏障约束编译器的优化。
所以屏障是语言语义的一部分，不是性能可选项。

#### Q1.2: 既然 StoreLoad 这么贵，那 volatile 写和加锁比，哪个更慢？

不能一概而论。volatile 写在 x86 上是一次 lock 前缀操作，没有线程调度、
没有监视器竞争，不阻塞，通常比一次无竞争的 synchronized 还轻；
但 synchronized 在偏向锁/轻量级锁下也很轻，竞争时才升级为重量级。
关键区别不在快慢，而在能力：volatile 不提供互斥，无法保证复合操作原子性，
遇到 `i++` 或多变量不变式时再快也不够用。

### Q2: 那为什么 i++ 加了 volatile 还是错的？

因为 volatile 的原子性只到「单次读」「单次写」这一层。`i++` 是读-改-写三步，
volatile 无法让这三步作为一个整体执行，两个线程的步骤会交错，
发生丢失更新。正确做法是用 `AtomicInteger.incrementAndGet()`（底层 CAS + volatile）
或把整个操作放进 synchronized 临界区。

#### Q2.1: AtomicInteger 是怎么做到既可见又原子的？

它内部就是一个 `volatile int value` 保存值，再配一个脏读后 CAS 的重试循环：

```java
// 思想示意
public final int incrementAndGet() {
    while (true) {
        int current = get();                 // volatile 读，保证可见
        int next = current + 1;
        if (compareAndSet(current, next)) {  // CAS，保证原子
            return next;
        }
        // 失败说明别人先改了，重新读一次再来
    }
}
```

volatile 负责让每次读看到最新值、让写入立刻可见；CAS 负责让
「比较并写入」这一步不可分割。两者组合才等价于一个原子的自增。

##### Q2.1.1: 那 CAS 一定成功吗？失败了怎么办？

不保证。CAS 是乐观的，失败就说明期间有其他线程改过值，标准做法就是循环重试（自旋）。
自旋在高竞争下会空耗 CPU，所以 Java 8 的 `LongAdder` 用分段累加（`Cell` 数组）
把热点分散来缓解；而 CAS 本身还有一个 ABA 问题，
这正是 cas-and-aba 那张卡片要展开的内容。

### Q3: 回到你刚说的双重检查锁，instance 为什么必须加 volatile？

因为对象创建不是原子的：`instance = new Singleton()` 会经历分配内存、
执行构造、赋值引用三步，没有 volatile 时后两步可能对调。
线程 B 可能在 `instance` 已非 null 但对象尚未初始化完成时通过第一次检查，
拿到一个字段全是默认值的半成品对象。volatile 写前的 StoreStore 屏障
禁止把构造写的可见性排在引用赋值之后，volatile 读的屏障又保证读到引用后
能看到构造完成的字段，于是发布是安全的。

#### Q3.1: 加了 volatile 之后 DCL 就万事大吉了吗？

不是，还有几个容易忽略的点：

- **构造方法里不能逸出 this**（启动线程、注册监听器、把 this 交给别的对象）。
  引用在构造完成前被别处看到，final 字段语义也会被破坏。
- **反射可以绕过私有构造**，枚举单例能天然防住这一点，这也是《Effective Java》
  推荐枚举实现单例的原因。
- **类加载本身提供了更好的替代**：静态内部类 holder 写法
  （`private static class Holder { static final Singleton INSTANCE = new Singleton(); }`）
  利用 JLS 12.4.2 的类初始化锁——类初始化由 JVM 保证只执行一次且对其他线程可见，
  不需要 volatile，也不需要在每次读取时做空检查。
- DCL 里两次 null 检查都不能省：第一次避免每次加锁，第二次防止重复创建。

##### Q3.1.1: 静态内部类为什么不需要加锁？

JVM 在类初始化阶段会加一把初始化锁（JLS 12.4.2 规定的 class initialization lock）。
一个线程负责执行 `<clinit>`，其他线程阻塞等待；等它完成后，
后续拿到 `INSTANCE` 引用的线程能看到完整初始化状态。
这形成了天然的安全发布，而且初始化是惰性的——只有真正访问 Holder 时才触发。

## 常见坑

- **说「volatile 保证了原子性」** —— 错。`i++`、`check-then-act` 这类复合操作
  加 volatile 照样错，volatile 只保证单次读/写原子
- **说「volatile 可以替代 synchronized」** —— 错。volatile 不阻塞、不提供互斥，
  无法保证多变量不变式
- **说「volatile 就是让每个线程各存一份副本再同步」** —— 错。工作内存是抽象概念，
  volatile 的语义是 happens-before 约束，落到硬件靠缓存一致性协议
- **说「volatile 读也要加锁，所以很慢」** —— 错。x86 的 TSO 下 volatile 读
  几乎等同普通读，代价主要在写侧
- **说「双重检查锁只要第二次 null 检查就够了，volatile 是优化」** —— 错。
  没有 volatile 的 DCL 在 Java 5 之后依然是错的（会被重排序破坏安全发布）
- **说「volatile 数组的每个元素都是 volatile」** —— 错。
  `volatile int[] arr` 只让**数组引用**可见，元素读写没有 volatile 语义
- **说「普通 long/double 和 volatile long/double 都会撕裂」** —— 错。
  JLS 17.7 明确 volatile 的 long/double 读写是原子的；非 volatile 才在规范上
  允许拆成两个 32 位操作
- **只说「volatile 禁止重排序」但说不出屏障种类** —— 面试官往往会追问
  StoreLoad 为什么最贵、x86 上到底插几条

## 加分点

- 能说出 x86 上 volatile 写的实际实现：HotSpot 用带 `lock` 前缀的指令
  （常见是 `lock addl $0x0,(%rsp)`）充当 StoreLoad 屏障，
  因为 TSO 下这是唯一需要真指令的屏障
- 知道 JLS 17.7 对 volatile long/double 原子性的特别规定，能区分
  「规范原子」与「实现碰巧原子」
- 知道相比 `volatile`，Java 9 的 `VarHandle` 提供了 `setRelease` / `getAcquire`
  等更细粒度的访问模式：写用 release、读用 acquire，语义上已满足大多数
  发布-订阅场景，但比 volatile 允许更多重排序，开销更低
- 知道 `volatile` 与伪共享的关系：多个 volatile 字段被不同线程高频写时，
  可能因为它们落在同一缓存行而互相失效。Java 8 提供 `@Contended`
  做缓存行填充，使用时要加 `-XX:-RestrictContended`
- 能给出 DCL 的替代方案并说清原理：静态内部类 holder（类初始化锁）、
  枚举单例（防反射和反序列化），而不是只会背「加 volatile」
- 提到 `volatile` 的复合用法：作为 CAS 循环里的读载体，
  `AtomicInteger` 等原子类都是这个套路

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 1.4 及以前 | 旧内存模型下 `volatile` 不保证可见性、允许重排序，双重检查锁会拿到半初始化对象 |
| Java 5（JSR-133） | `volatile` 正式获得可见性与禁止重排序语义，DCL 加 `volatile` 的写法从这时才成立 |
| Java 8 | 提供 `@Contended` 缓解伪共享（需 `-XX:-RestrictContended` 才能在用户代码生效） |
| Java 9 | `VarHandle`（JEP 193）提供 `setRelease` / `getAcquire` 等访问模式，比 `volatile` 更细粒度；`sun.misc.Unsafe` 的内存屏障方法逐步被替代 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: volatile 主要通过什么手段保证有序性？
    options:
      A: 加锁互斥
      B: 插入内存屏障禁止特定重排序
      C: CAS 自旋
      D: 让线程切换更频繁
    answer: B
    analysis: volatile 写前后分别插入 StoreStore 和 StoreLoad，读后插入 LoadLoad 和 LoadStore，用屏障禁止重排序。它不提供互斥（那是锁的事），也用不到 CAS。
    difficulty: 2

  - type: JUDGE
    stem: 用 volatile 修饰一个 int 变量后，对该变量执行 i++ 是线程安全的。
    answer: F
    analysis: i++ 是读-改-写三步，volatile 只保证单次读和单次写的原子性，无法阻止三步之间被其他线程插入，会发生丢失更新。正确做法是用 AtomicInteger 或加锁。
    difficulty: 2

  - type: MULTI
    stem: 关于 volatile 的内存屏障，下列说法正确的有？
    options:
      A: volatile 写之前插入 StoreStore 屏障
      B: volatile 写之后插入 StoreLoad 屏障
      C: StoreLoad 是四种屏障中开销最大的
      D: volatile 读之后插入 LoadLoad 和 LoadStore 屏障
    answer: ABCD
    analysis: 四项都正确。x86 的 TSO 模型下只有 StoreLoad 需要真实指令，其余三种退化为空操作，这也是 volatile 写比读贵得多的原因。
    difficulty: 3

  - type: CLOZE
    stem: |
      下面是双重检查锁单例，补全字段上必须添加的关键字，以及它保护的核心动作：
      ```java
      public class Singleton {
          private static {{1}} Singleton instance;   // 必须加的关键字

          public static Singleton getInstance() {
              if (instance == null) {                 // 第一次检查，避免每次加锁
                  synchronized (Singleton.class) {
                      if (instance == null) {         // 第二次检查，防止重复创建
                          instance = new Singleton(); // 该关键字用于禁止"赋值引用"与"{{2}}"之间的重排序
                      }
                  }
              }
              return instance;
          }
      }
      ```
    blanks:
      - ["volatile"]
      - ["构造", "对象初始化", "构造方法"]
    analysis: 没有 volatile 时，分配内存、执行构造、赋值引用三步可能重排序，别的线程会在 instance 非 null 但对象未初始化完成时通过第一次检查，拿到半初始化对象。volatile 写前的 StoreStore 屏障禁止这种重排。
    difficulty: 3
````
