---
slug: java-17-21-features
title: 你用过 Java 17 / 21 的哪些新特性？
module: java-basics
tags: [Java 17, Java 21, Record, 虚拟线程, 模式匹配]
difficulty: 2
frequency: 2
related:
  - slug: virtual-threads
    type: DEEPEN
  - slug: stream-and-lambda
    type: RELATED
---

## 电梯版回答

我分语法和运行时两类说。语法上，Record 在 Java 16 转正，用一行声明不可变数据载体，自动生成构造器、访问器和 equals/hashCode/toString，适合做值对象和 Map 的 key；文本块是 Java 15 转正；instanceof 模式匹配是 Java 16，省掉显式强转；密封类是 Java 17 的 sealed 和 permits 限定子类，配合 Java 21 正式的模式匹配 switch，编译器能做穷尽性检查；switch 表达式和 yield 在 Java 14 转正。运行时最重要的是 Java 21 的虚拟线程，把「一个请求一个线程」的成本降到可以用阻塞式写法；还有 Java 21 的分代 ZGC 和 Sequenced Collections。小一点的还有 Java 16 的 Stream.toList() 和 Java 10 的 var。

## 展开讲解

### Record

```java
public record Point(int x, int y) {
    // 编译器自动生成：
    // 私有 final 字段 x、y
    // 规范构造器 Point(int x, int y)
    // 访问器 x()、y()（与组件同名，没有 get 前缀）
    // 基于全部组件的 equals、hashCode、toString
}
```

- 隐式 final，且隐式继承 `java.lang.Record`，**不能继承其他类**，但可以实现接口
- 组件是 private final，没有 setter；可以做紧凑构造器做校验：

```java
public record Range(int lo, int hi) {
    public Range {
        if (lo > hi) {
            throw new IllegalArgumentException("lo 不能大于 hi");
        }
    }
}
```

- 适合做值对象和 Map 的 key：`equals`/`hashCode` 基于全部组件生成，
  两个内容相同的 Record 必然相等且哈希一致，不会像手写类那样漏字段
- 可以有静态字段、静态方法、实例方法，但不能新增实例字段

### 密封类 sealed / permits

```java
public sealed interface Shape permits Circle, Rectangle, Triangle {}

public record Circle(double radius) implements Shape {}
public final class Triangle implements Shape {}
public non-sealed class Rectangle implements Shape {}
```

- `sealed` 声明父类型，`permits` 列出允许的直接子类
- 子类必须显式声明为 `final`、`sealed` 或 `non-sealed` 三者之一
- 如果子类和父类在同一个源文件里，可以省略 `permits`
- Java 17 正式（JEP 409）。它的价值在于把「谁能继承我」写进类型系统，
  让父类型的可能子集变成**封闭且已知**的，这是穷尽性检查的前提

### 模式匹配

**instanceof 模式**（Java 16 正式，JEP 394）：匹配成功后直接绑定变量，作用域由编译器流分析决定。

```java
if (obj instanceof String s && !s.isEmpty()) {
    System.out.println(s.length());
}
```

**switch 模式匹配**（Java 21 正式，JEP 441）：

```java
static String describe(Object o) {
    return switch (o) {
        case Integer i -> "int " + i;
        case String s when s.isEmpty() -> "empty";
        case String s -> "string " + s;
        case null -> "null";
        default -> "other";
    };
}
```

- 守卫条件用 `when`（Java 17/18 的 preview 用 `&&`，Java 19 的 preview 起改为 `when`）
- 选择器为 `null` 且没有 `case null` 时会抛 `NullPointerException`
- **穷尽性**：如果选择器类型是 sealed 且所有子类型都被覆盖，可以省略 `default`；
  漏掉一个则编译报错。普通接口或 `Object` 无法证明穷尽，必须写 `default`
- **Record 解构**（Java 21 正式，JEP 440）：
  `case Point(int x, int y) -> x + y;`，直接解出组件

### 文本块与 var

文本块（Java 15 正式，JEP 378）用 `"""` 写多行字符串，会自动去除公共缩进；
行尾的 `\` 可以去掉换行，`\s` 可以保留行尾空格。

```java
String json = """
        {
          "name": "jis"
        }
        """;
```

var（Java 10，JEP 286）是局部变量类型推断，类型在编译期就确定了，不是动态类型。
只能用于局部变量，不能用于字段、方法参数、返回值，也不能写 `var x = null`。

### switch 表达式与 yield

Java 14 正式（JEP 361）：

```java
int numLetters = switch (day) {
    case MONDAY, FRIDAY, SUNDAY -> 6;
    case TUESDAY -> 7;
    default -> {
        int computed = day.name().length();
        yield computed;   // 块内用 yield 返回值
    }
};
```

`->` 形式不贯穿（不需要 `break`），需要多条语句时用代码块加 `yield`。
switch 表达式必须有值，所以枚举/密封类型的 switch 要覆盖全部情况或写 `default`。

### 虚拟线程（Java 21 正式，JEP 444）

- 由 JVM 调度的轻量线程，运行时挂在少量平台线程（**载体线程** carrier）上
- 阻塞时 unmount，把载体线程让给其他虚拟线程，所以「一请求一线程」的阻塞式写法
  也能支撑高并发，不用再为线程池大小纠结
- `Thread.ofVirtual().start(task)`、`Executors.newVirtualThreadPerTaskExecutor()`
- 适合 IO 密集；CPU 密集没有收益，反而多一层调度开销
- **不要池化**：创建成本极低，池化会限制并发且失去意义；要限制下游资源
  应该用 `Semaphore`
- Java 21 里在 `synchronized` 内阻塞会 **pin 住**载体线程，官方建议改用 `ReentrantLock`

### 分代 ZGC（Java 21，JEP 439）

原来的 ZGC 不分代，每次回收都要扫描整个堆：停顿虽短，但 CPU 开销大。
分代 ZGC 引入年轻代/老年代，利用「绝大多数对象朝生夕死」的分代假设，
只回收年轻代就能回收大部分垃圾，从而降低 CPU 占用、提高吞吐，
同时保留亚毫秒级停顿。Java 21 里它需要显式开启：
`-XX:+UseZGC -XX:+ZGenerational`（不分代模式仍是默认）。

### Sequenced Collections（Java 21，JEP 431）

新增 `SequencedCollection`、`SequencedSet`、`SequencedMap` 三个接口，
统一了首尾访问 API：`getFirst()` / `getLast()` / `addFirst()` / `addLast()` /
`reversed()`。此前 `List`、`Deque`、`LinkedHashSet`、`SortedSet` 各有各的写法，
甚至有的没法方便地取最后一个元素。现在这些集合类型都实现了对应的接口。

### Stream.toList()（Java 16）

```java
List<String> names = stream.map(User::name)
    .toList();
```

比 `collect(Collectors.toList())` 更短，且明确返回**不可变** List
（修改会抛 `UnsupportedOperationException`）。`Collectors.toList()` 返回的
List 规范上不保证可变性，所以新代码用 `toList()` 语义更清晰。

## 追问链

### Q1: Record 和普通类有什么本质区别？为什么它能安全地做 Map 的 key？

Record 是语言级的「透明数据载体」：组件列表就是它的公开 API，
构造器、访问器、`equals`/`hashCode`/`toString` 全部由编译器按规范生成，
而且它隐式 final、隐式继承 `java.lang.Record`，不能有额外实例字段。
用它做 Map 的 key 安全，是因为 `equals`/`hashCode` 由**所有组件**派生，
两个内容相同的 Record 必然相等且哈希一致；手写类很容易漏掉某个字段，
或者在对象被放进 Map 后又改了状态，导致再也找不回来。

#### Q1.1: 既然 Record 字段是 final，那它就是完全不可变的吗？

不是。final 只保证组件引用不能被重新赋值，不保证引用指向的对象不可变。
`record Order(List<Item> items)` 里的 `items` 字段虽然 final，
外部仍能拿到这个 List 并增删元素。要深不可变，得在紧凑构造器里做防御性拷贝，
比如 `items = List.copyOf(items);`。只有所有组件本身都是不可变类型
（String、包装类、其他 Record）时，Record 才天然是深不可变的。

##### Q1.1.1: 那 Record 能用来做 JPA 实体吗？

标准 JPA 实体不适合。Hibernate 这类实现依赖无参构造器、非 final 字段
来生成代理、做脏检查，而 Record 的字段是 final、构造器是规范构造器，
不满足这些前提。Record 更适合作为查询投影的结果类型（把查询结果映射成只读 DTO），
较新的 Hibernate 版本也开始支持这类用法。需要可变状态、需要 setter 的场景，
还是老老实实写普通类。

### Q2: 密封类解决了什么问题？为什么它和 switch 模式匹配是一对？

密封类把继承关系从「任何类都能实现」变成「只有 permits 列表里的类能实现」，
让一个父类型的可能子集变成编译期已知的封闭集合。
模式匹配 switch 要判断是否**穷尽**，只有编译器能枚举出所有子类型时，
才能不写 `default` 就通过检查。两者结合带来的是「新增子类型时编译器报错
提醒你补分支」这种编译期安全，普通接口加模式匹配做不到这一点。

#### Q2.1: switch 模式匹配里没有 default 会怎样？

分情况。如果选择器类型是 sealed，且所有允许的子类型都被 case 覆盖，
编译器认为穷尽，可以省略 `default`，编译通过；漏掉任何一个则编译报错。
如果类型不是 sealed（比如 `Object` 或普通接口），编译器无法证明穷尽，
就必须写 `default`，否则编译失败。
另外 `null` 要单独处理：选择器为 `null` 且没有 `case null` 时会抛 NPE。

##### Q2.1.1: 如果我以后给密封接口加了新的实现类，会发生什么？

要加实现类就必须改 `permits` 列表，否则新类编译不过。
而所有对该接口做穷尽 switch 的地方，会因为不再覆盖全部子类型而**编译失败**——
这正是它的价值：把「漏改分支」从运行时 bug 变成了编译错误。
但要注意，如果原来的 switch 写了 `default`，新增子类型不会报错，
新类型会悄悄掉进 `default`。想吃到穷尽性检查的红利，就不要随手写 `default`。

### Q3: 虚拟线程和平台线程有什么区别？为什么不能池化？

平台线程是 OS 线程的 1:1 包装，创建/切换成本高，栈内存固定
（默认几百 KB 到 1 MB 量级），所以数量有限。
虚拟线程由 JVM 调度，栈存在堆上且按需增长，挂起（unmount）成本极低，
可以创建几十万个。池化的目的是复用昂贵资源，而虚拟线程创建成本极低，
池化既没有收益，又会把并发数限制在池大小上，失去它的意义。
要限制下游资源（比如数据库连接），应该用 `Semaphore` 而不是线程池。

#### Q3.1: 那虚拟线程里还能用 synchronized 吗？

能用，但 Java 21 里在 `synchronized` 块中阻塞会让虚拟线程 **pin 住**载体线程：
该虚拟线程阻塞时无法 unmount，载体线程跟着被占住。
如果大量虚拟线程都卡在 `synchronized` 上，载体线程会被耗尽，
退化成平台线程模型甚至死锁。JNI 调用和 `Object.wait()` 也可能造成 pinning。
这个限制在 JDK 24 的 JEP 491 里被解决。

##### Q3.1.1: 官方推荐怎么替代？

用 `java.util.concurrent.locks.ReentrantLock` 替代 `synchronized`：
虚拟线程在 `LockSupport.park` 上阻塞时能正常 unmount，载体线程会被释放。
实践上还要把临界区尽量改小，或者改成无锁/不可变设计；
`ReentrantReadWriteLock`、`StampedLock` 也可以。
同时注意别在虚拟线程里做 CPU 密集计算，那会一直占着载体线程不放。

### Q4: 分代 ZGC 和原来的 ZGC 差在哪？

原来的 ZGC 不分代，每次回收都要扫描整个堆，代价是 CPU 开销大，
对「绝大多数对象很快死亡」的常见负载不够经济。
分代 ZGC 引入年轻代和老年代，只回收年轻代就能回收大部分垃圾，
扫描范围和 CPU 占用显著下降，吞吐更好，同时保留亚毫秒级停顿。
Java 21 里它是可选模式（`-XX:+UseZGC -XX:+ZGenerational`），
后续版本变成默认并废弃了不分代模式。

## 常见坑

- **「Record 是 Java 17 转正的」** —— Record 在 Java 16 转正（14、15 是 preview），
  Java 17 转正的是密封类，文本块则是 Java 15 转正，这几个版本号最容易混
- **「Record 的访问器是 getX()」** —— 是 `x()`，与组件同名，没有 get 前缀；
  依赖 getter 命名规范的框架（部分序列化、JPA）需要额外适配
- **「Record 是不可变的，所以线程一定安全」** —— final 只保证引用不再指向别的对象，
  组件若是可变集合，内容仍能被修改；要深不可变得在紧凑构造器里做防御性拷贝
- **「密封类的子类可以随便定义」** —— 子类必须显式声明为 `final`、`sealed`
  或 `non-sealed`，且必须在 `permits` 列表里，否则编译报错
- **「switch 模式匹配的守卫条件用 &&」** —— Java 21 正式版用 `when`；
  Java 17/18 的 preview 用过 `&&`，Java 19 的 preview 起改成了 `when`，不要混用
- **「虚拟线程可以池化以提高性能」** —— 创建成本极低，池化没有收益，
  反而限制并发；要限制下游资源应该用信号量
- **「虚拟线程能让 CPU 密集任务变快」** —— 它解决的是阻塞等待时的线程占用，
  CPU 密集任务没有收益，还可能多一层调度开销
- **「Stream.toList() 和 collect(Collectors.toList()) 完全等价」** ——
  `toList()` 返回不可变 List，修改会抛 `UnsupportedOperationException`，
  不要把它当可变集合用
- **「var 是动态类型」** —— 类型在编译期就确定了，只是省略书写；
  不能用于字段、方法参数、返回值，也不能写 `var x = null`

## 加分点

- 密封接口 + Record + 模式匹配三者合起来就是代数数据类型：
  sealed interface 表示「和类型」，Record 表示「积类型」，
  编译器帮你做穷尽性检查，可以用它表达 Result、状态机等
- Record 的一个坑：如果组件里有数组，自动生成的 `equals`/`hashCode`
  对数组用的是引用比较（`Objects.equals`），会导致两个内容相同的实例不相等，
  需要自己重写这两个方法
- 结构化并发 `StructuredTaskScope` 在 Java 21 是 preview（JEP 453），
  它和虚拟线程是配套的：把子任务的生命周期限制在一个作用域内，
  避免线程泄漏和取消传播丢失
- `BufferedReader.readLine()` 用文本块时要注意 `\` 续行与 `\s` 的语义，
  它们分别用于去掉和保留换行/尾随空格
- 分代 ZGC 的默认值变化：Java 23 的 JEP 474 把分代模式设为默认、
  不分代模式标记为废弃，升级 JDK 时启动参数要相应调整

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 10 | `var` 局部变量类型推断（JEP 286） |
| Java 14 | switch 表达式正式，块内用 `yield` 返回值（JEP 361）；Record 首次 preview；helpful NullPointerException（JEP 358） |
| Java 15 | 文本块正式（JEP 378）；密封类进入 preview；helpful NPE 默认开启 |
| Java 16 | Record 正式（JEP 395）；`instanceof` 模式匹配正式（JEP 394）；新增 `Stream.toList()` |
| Java 17 | 密封类正式（JEP 409）；switch 模式匹配、Record 解构进入 preview |
| Java 21 | switch 模式匹配正式（JEP 441）；Record 解构正式（JEP 440）；虚拟线程正式（JEP 444，19/20 为 preview）；分代 ZGC（JEP 439，用 `-XX:+ZGenerational` 开启）；Sequenced Collections（JEP 431）；结构化并发仍为 preview |
| Java 23 | 分代 ZGC 成为默认，不分代模式被废弃（JEP 474） |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: Record 是在哪个 Java 版本正式转正的？
    options:
      A: Java 14
      B: Java 15
      C: Java 16
      D: Java 17
    answer: C
    analysis: Record 在 Java 14、15 是 preview，Java 16 随 JEP 395 正式转正。Java 15 转正的是文本块，Java 17 转正的是密封类，这三个版本号经常被混淆。

  - type: JUDGE
    stem: 虚拟线程适合用来加速 CPU 密集型的计算任务。
    answer: F
    analysis: 虚拟线程解决的是阻塞等待时线程被占住的问题，适合 IO 密集场景。CPU 密集任务会一直占着载体线程，没有收益，还可能因为多一层调度而更慢。

  - type: MULTI
    stem: 关于密封类和模式匹配，下列说法正确的有？
    options:
      A: 密封类的直接子类必须声明为 final、sealed 或 non-sealed
      B: 对密封接口做穷尽 switch 时可以省略 default
      C: 给密封接口新增实现类，所有对该接口的穷尽 switch 都会编译失败
      D: switch 模式匹配的守卫条件在 Java 21 正式版里用 && 书写
    answer: ABC
    analysis: D 错误。Java 21 正式版用 when 写守卫条件，&& 只在 Java 17/18 的 preview 里用过。C 的前提是那个 switch 真的穷尽了且没写 default，写了 default 的话新类型会掉进 default 而不报错。

  - type: CLOZE
    stem: |
      补全下面的 Java 代码：声明一个不可变的点坐标类型，以及给 switch 分支加上守卫条件。

      ```java
      public {{1}} Point(int x, int y) {}

      static String sign(Object o) {
          return switch (o) {
              case Integer i {{2}} i > 0 -> "positive";
              case Integer i -> "non-positive";
              default -> "other";
          };
      }
      ```
    blanks:
      - ["record"]
      - ["when"]
    analysis: record 关键字声明记录类，编译器自动生成构造器、访问器与 equals/hashCode/toString。Java 21 正式版的 switch 模式匹配用 when 引入守卫条件，Java 17/18 的 preview 阶段用的是 && 语法。
    difficulty: 2
````
