---
slug: string-immutable-pool
title: String 为什么设计成不可变？常量池和 intern 是怎么回事？
module: java-basics
tags: [String, 不可变, 常量池, intern]
difficulty: 2
frequency: 3
related:
  - slug: equals-hashcode-contract
    type: RELATED
  - slug: hashmap-internals
    type: RELATED
---

## 电梯版回答

String 被设计成不可变，主要为了三件事。第一是可缓存 hashCode，String 内部有一个 `hash` 字段，第一次调用 `hashCode` 后结果就存下来，这让它作为 HashMap 的 key 非常高效；如果内容可变，插入之后哈希值变了就再也查不到。第二是天然线程安全，不可变对象可以被多线程自由共享，不需要同步。第三是安全，String 被大量用作类名、文件路径、网络地址和权限校验的输入，如果内容能被改，就可能在检查通过之后被替换，形成 TOCTOU 漏洞。实现上 String 是 final class，内部是 private final 的字节数组（Java 9 之前是 `char[]`，Java 9 起改成 `byte[]` 加 `coder` 的紧凑字符串），并且从不泄露、也不修改这个数组。字符串常量池不在独立区域里，它其实就是堆里的一张哈希表（Java 7 起从永久代移到堆），存放字面量和编译期常量折叠的结果。`intern()` 就是查这张表：池里有内容相等的就返回池中那个，没有就把当前对象放进去并返回它。所以 `new String("a")` 和字面量 `"a"` 不是同一个对象，必须 intern 之后 `==` 才成立。

## 展开讲解

### 不可变是怎么实现的

```java
public final class String
    implements java.io.Serializable, Comparable<String>, CharSequence,
               Constable, ConstantDesc {

    @Stable
    private final byte[] value;   // Java 9 起：紧凑字符串
    private final byte coder;     // LATIN1 = 0 / UTF16 = 1
    private int hash;             // 缓存 hashCode，默认 0
    private boolean hashIsZero;   // 区分「没算过」和「算出来正好是 0」
}
```

三条纪律缺一不可：

1. `final class` 防止子类覆盖 `hashCode` / `equals` / `length` 等行为。
2. 字段全部 `private`，且不提供任何修改 `value` 的方法。
3. 任何可能泄露内部数组的出口都返回副本：`toCharArray()` 返回拷贝，`String(char[])` 构造用 `Arrays.copyOf` 复制，`substring` 返回新数组而不是共享原数组。唯一的例外是包内私有的 `String(byte[] value, byte coder)` 会直接接管数组，但那是 JDK 内部使用，调用方不会再持有该数组。

要特别注意：`private final byte[]` 里的 `final` 只约束引用不能改指向，**并不保证数组内容不可变**。不可变性来自「不修改 + 不泄露 + 构造时复制」，不是靠一个 final 关键字自动得到的。

版本差异：Java 8 及以前是 `private final char[] value`，每个字符固定 2 字节（UTF-16）；Java 9 的 JEP 254 紧凑字符串改成 `byte[] + coder`，纯 Latin-1 字符每字符 1 字节，含非 Latin-1 时退回 UTF-16，整体更省内存。

### 不可变的三个理由

**一、可缓存 hashCode。** String 的哈希按 `s[0]*31^(n-1) + ... + s[n-1]` 计算，算一次就写进 `hash` 字段：

```java
public int hashCode() {
    int h = hash;
    if (h == 0 && !hashIsZero) {
        h = isLatin1() ? StringLatin1.hashCode(value)
                       : StringUTF16.hashCode(value);
        if (h == 0) hashIsZero = true;
        else hash = h;
    }
    return h;
}
```

String 是 HashMap / HashSet 最常用的 key，缓存让「同一个 key 反复查」不必反复算哈希。反过来看更清楚：如果 String 可变，插入后内容变了，它在哈希表里的位置就和新的 hashCode 不匹配，`get` 再也找不到——这正是「可变对象不适合当 HashMap key」的根本原因。

**二、线程安全。** 不可变对象的状态在构造之后不再改变，多线程可以自由共享和发布，不需要任何同步；而且 `final` 字段有初始化安全保证（JLS 17.5），别的线程看到的字段值一定是构造完成后的值，不存在「看到半初始化对象」的问题。

**三、安全性。** String 会作为类名传给 `ClassLoader`、作为文件路径、网络主机名、SQL / URL、权限判断的输入。如果它可变，就存在 TOCTOU（检查后再使用）漏洞：代码先检查某个文件路径是否在允许目录内，检查通过之后攻击者改掉这个 String 的内容，实际打开的就是被篡改后的路径。字符串不可变，这个窗口就不存在。

还有一个附带的设计理由：常量池的本质是共享同一个对象，只有不可变才能安全共享——否则 `String a = "x"` 和 `String b = "x"` 指向同一对象，改一个会影响所有引用者。

### 常量池在哪：堆里的一张哈希表

字符串常量池（String Table，也叫 string pool）不是一块独立的内存区域，而是 JVM 维护的一张哈希表，用来复用内容相同的字符串。

- **Java 6 及以前**：它属于方法区，HotSpot 用永久代（PermGen）实现方法区，所以大量 `intern()` 会报 `OutOfMemoryError: PermGen space`。
- **Java 7 起**：字符串常量池被移到**堆**。注意这是 JDK 7 的改动，和 JDK 8 用 Metaspace 取代 PermGen 是两件不同的事。所以现在 intern 出来的对象占的是堆内存，受 `-Xmx` 约束。
- 桶数量由 `-XX:StringTableSize` 控制（JDK 21 默认 65536），可以用 `-XX:+PrintStringTableStatistics` 查看桶数、条目数和平均桶长。

什么时候进池：

- 字面量 `"abc"`、以及编译期常量表达式（如 `"a" + "b"`）的结果，会在类加载 / 首次 `ldc` 时入池并复用。
- `new String(...)` 创建的对象在堆上，**不入池**，除非显式调用 `intern()`。
- 运行期拼接的结果不入池。

### intern() 做了什么

```java
public native String intern();
```

JDK 文档的语义：返回一个内容相同、但保证来自唯一池的字符串。具体行为：

- 池中已存在 `equals` 相等的字符串 → 返回池中那个引用，不是当前对象；
- 池中没有 → 把当前字符串加入池，返回当前引用。

Java 7 起池在堆里，池可以保存对象引用，所以「没有则放入 this 并返回 this」是可行的。可以这样验证：

```java
new String("a").intern() == "a"   // true
new String("a") == "a"            // false
"a" + "b" == "ab"                 // true，编译期常量折叠
String x = "a"; (x + "b") == "ab" // false，运行期拼接
```

### new String("a") 创建几个对象

答案取决于执行到这一句时 `"a"` 是否已经在池里：

- 池里还没有 `"a"`（本类第一次用到该字面量）：`ldc` 先把 `"a"` 放进池（1 个），`new` 再在堆上建一个新 String（1 个），共 **2 个**；
- 池里已经有 `"a"`：只 `new` 出 1 个对象，共 **1 个**。

所以标准答案说「2 个」（池中一个字面量 + 堆上一个新对象）是常见口径，但更严谨的回答要点出「如果池中已存在则只 new 一个」。另外，`String(String original)` 构造是直接共享 `original.value`，不复制字符数组，所以「2 个对象」不等于「2 份字符数据」。

### 编译期常量折叠

JLS 把由字面量、`final` 常量变量和常量表达式经 `+` 等运算得到的结果也视为常量表达式，javac 在编译期就求值，把结果作为字面量写进 class 文件常量池：

```java
static final String A = "a";
String s1 = "a" + "b";        // 编译期折叠成 "ab"，与字面量 "ab" 同一对象
String s2 = A + "b";          // A 是编译期常量，同样折叠成 "ab"
String a = "a";
String s3 = a + "b";          // a 不是常量变量，运行期拼接，s3 == "ab" 为 false
```

运行期拼接在 Java 9 之前走 `StringBuilder`，Java 9 起（JEP 280）改成 `invokedynamic` + `StringConcatFactory.makeConcatWithConstants`，语义不变（结果同样不入池），但给 JIT 留了优化空间。

## 追问链

### Q1: 你说 String 不可变，那 final 字段就能保证不可变吗？

不能。`private final byte[] value` 里的 `final` 只保证引用不能指向别的数组，数组元素本身仍然可写。String 的不可变是三条纪律共同保证的：类不提供修改 `value` 的方法；任何暴露内部数据的方法都返回副本（`toCharArray()`）；接收外部数组的构造方法先复制（`String(char[])` 用 `Arrays.copyOf`）。所以「不可变」是一个设计约束，不是靠一个 `final` 关键字自动得到的。

#### Q1.1: 那 final class 又是为了什么？

防止子类覆盖方法。如果 String 可以被继承，子类就能重写 `equals`、`hashCode`、`length` 等，制造出「看起来是 String、行为不一样」的对象，破坏以 String 为前提的各种假设，比如 HashMap 的 key 契约、常量池的共享。`final class` 从类型层面堵死这条路，同时也让 JIT 能放心做去虚拟化和内联。

##### Q1.1.1: 反射能不能绕过不可变性改掉 String 的内容？

在旧 JDK（Java 8 及以前）上，可以用 `setAccessible(true)` 拿到 `String.value`，再修改数组元素（改的是元素，不是 final 引用本身），从而改掉内容。但这种做法会破坏常量池的共享假设——你改的可能是一个被所有 `"abc"` 引用共享的池对象，影响面不可控。从 Java 9 起 `java.base` 模块对核心类加了强封装，对 `java.lang.String` 的私有字段调用 `setAccessible` 会抛 `InaccessibleObjectException`（除非用 `--add-opens` 打开，而且不同 JDK 对 final 字段 `Field.set` 的限制还不一样）。所以面试里说「Java 8 能靠反射破坏、Java 9+ 被模块系统挡住」比断言「一定能改」稳妥。

### Q2: 常量池到底在哪个内存区域？为什么老有人说它在永久代？

那是 Java 7 之前的说法。Java 7 以前字符串常量池属于方法区，HotSpot 用永久代实现方法区，所以池在永久代，`intern()` 太多会报 `OutOfMemoryError: PermGen space`。Java 7 把字符串常量池移到了堆里；Java 8 才用 Metaspace 取代 PermGen。这两件事经常被混在一起说，记住顺序是「先把池移到堆，再换 Metaspace」。

#### Q2.1: 池移到堆里有什么实际影响？

池里的字符串不再受永久代 / Metaspace 大小限制，而是和普通对象一起占堆内存，由 `-Xmx` 约束，不会再报 PermGen OOM。而且可以被 GC 回收：池里的字符串如果没有任何强引用，会被回收，所以池不会无限增长。但哈希表的桶数是固定的，大量 `intern()` 会让冲突变多、查找退化，桶数可以用 `-XX:StringTableSize` 调大。

##### Q2.1.1: intern() 和 G1 的字符串去重有什么区别？

`intern()` 是逻辑去重：保证内容相同的 `String` 是同一个对象，`==` 为 true，代价是要维护池、而且调用 `intern()` 本身有开销，适合少量会被反复比较的标识字符串，比如状态名、枚举式常量。G1 的字符串去重（Java 8u20 起，`-XX:+UseStringDeduplication`，JEP 192）是物理去重：两个 `String` 仍是不同对象、`==` 仍为 false，只是让它们共享同一份底层数组来省内存，由 GC 在并发标记阶段自动完成，适合堆里存在大量重复字符串缓存的场景。要省内存用后者，要快速比较用前者。

### Q3: 编译期常量折叠具体怎么影响 == 的结果？

javac 会把常量表达式在编译期算好，结果作为字面量写进 class 文件常量池，运行时 `ldc` 拿到的就是池里同一个对象。所以 `"a" + "b" == "ab"` 是 true，`static final String A = "a"; A + "b" == "ab"` 也是 true（A 是编译期常量）。而 `String a = "a"; a + "b" == "ab"` 是 false，因为 a 不是常量变量，拼接发生在运行期，结果是堆上的新对象、不入池。这也给出了「字符串比较必须用 equals」的真正边界：只有字面量、编译期折叠结果和显式 `intern()` 过的对象才能用 `==`。

#### Q3.1: 为什么 Java 9 要把字符串拼接从 StringBuilder 换成 invokedynamic？

为了给 JIT 留优化空间。用 StringBuilder 时，拼接被编译成一串固定的 `new StringBuilder().append(...).toString()` 字节码，运行期很难替换实现；改成 `invokedynamic`（JEP 280，`StringConcatFactory`）之后，首次执行时动态生成一个 MethodHandle 策略，JIT 可以针对实际的参数类型和长度选择更优实现，比如预先算好总长度、一次性分配并拷贝，省掉 StringBuilder 的扩容和中转数组。语义完全不变，结果同样不入池，只是把实现变成了可优化的。

## 常见坑

- **「String 不可变是因为 value 数组是 final 的」** —— final 只锁引用，数组元素仍然可写；不可变来自「不修改 + 不泄露 + 构造时复制」。
- **「字符串常量池在永久代 / 方法区」** —— Java 7 起已移到堆，Java 8 才用 Metaspace 取代 PermGen；这个说法只对 Java 6 及更早成立。
- **「new String("abc") 一定创建 2 个对象」** —— 常见口径是 2 个（池里一个 + 堆里一个），但严格说取决于 "abc" 是否已在池中，已存在时只 new 出 1 个；不要背死数字。
- **「new String("abc") == "abc" 是 true，因为有常量池」** —— false。`new` 出来的是堆上新对象，和池里的字面量不是同一个；只有 `intern()` 之后 `==` 才成立。
- **「String s = "a" + "b" 会走 StringBuilder」** —— 两边都是编译期常量，javac 直接折叠成 "ab"；只有运行期拼接才走 StringBuilder（Java 9+ 是 invokedynamic）。
- **「调 intern() 一定会把当前对象放进池并返回它」** —— 只有池中没有相等字符串时才是「放入 this 返回 this」；池已有则返回池中那个，所以不能用 `s == s.intern()` 判断是否新入池。
- **「用 == 比较字符串也能对，因为有常量池」** —— 只有字面量、编译期折叠、显式 intern 的结果才保证是同一对象；`new String`、运行期拼接、`substring`、IO 读入的都是不同对象，必须用 `equals`。

## 加分点

- **hash 为 0 的边界**：Java 8 的 String 只有 `hash` 字段，`hashCode()` 里判断 `if (h == 0 && value.length > 0)`，导致「哈希值正好为 0」的字符串每次都会重算；较新的 JDK 增加了 `hashIsZero` 标志来区分「没算过」和「结果是 0」（JDK 11 还没有这个字段，JDK 21 有）。这个细节说出来很能体现看过源码。
- **常量池是可被 GC 的**：池中字符串如果不再被类常量或其他对象引用，会被回收，这是把池移到堆之后的直接好处；`-XX:+PrintStringTableStatistics` 可以观察条目数和桶冲突。
- **`substring` 的历史坑**：Java 6 及更早，`substring` 会让子串共享原字符串的 `char[]`，从一个巨大字符串里取几个字符也会一直持有整个大数组，是经典内存泄漏来源；Java 7 起改为复制（本卡核对的 JDK 8 / 11 / 21 源码里，`substring` 都返回新数组）。
- **运算符顺序坑**：`"a" + 1 + 2` 得到 `"a12"`（从左到右，第一次 `+` 就变成字符串拼接），而 `1 + 2 + "a"` 得到 `"3a"`。
- **不可变的代价**：每次修改都产生新对象，循环里用 `+` 拼接是经典性能坑，应改用 `StringBuilder`；Java 9+ 的 invokedynamic 让单条表达式的拼接自动变快，但循环内累积仍然要用 StringBuilder。
- **和 equals / hashCode 的关系**：String 的 `equals` 比较内容、`hashCode` 基于内容且被缓存，正好满足作为 HashMap key 的契约；反过来也说明任何可变对象都不适合做 key。

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 6 及以前 | 字符串常量池在方法区（HotSpot 永久代）；`substring` 共享底层 `char[]`，存在内存泄漏隐患 |
| Java 7 | 字符串常量池从永久代移到**堆**；`substring` 改为复制底层数组，不再共享 |
| Java 8 | String 仍是 `private final char[] value`，只有 `hash` 字段、没有 `hashIsZero`；G1 在 8u20 提供 `-XX:+UseStringDeduplication`（JEP 192） |
| Java 9 | JEP 254 紧凑字符串：`char[]` 改为 `byte[] + coder`（LATIN1 / UTF16）；JEP 280 拼接改为 `invokedynamic` + `StringConcatFactory` |
| 较新 JDK（JDK 21 已核对） | String 增加 `hashIsZero` 字段避免哈希为 0 时重算；实现 `Constable` / `ConstantDesc`；`-XX:StringTableSize` 默认 65536 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: String 的不可变性主要由什么保证？
    options:
      A: value 数组被声明为 final
      B: final class 加 private final 数组，且不修改、不泄露内部数组
      C: hashCode 被缓存
      D: 常量池的存在
    answer: B
    analysis: final 只锁住引用，数组元素仍可写。不可变性由三条纪律共同保证：类不可继承、不提供修改方法、任何出口都返回副本（toCharArray、构造方法复制）。

  - type: JUDGE
    stem: 在 Java 8 中，字符串常量池位于永久代（PermGen）。
    answer: F
    analysis: 字符串常量池在 Java 7 就移到了堆；Java 8 才用 Metaspace 取代 PermGen。所以 Java 8 里池在堆，不在永久代。

  - type: MULTI
    stem: 关于 intern()，下列说法正确的有？
    options:
      A: 池中已有相等字符串时，返回池中那个引用
      B: 池中没有相等字符串时，把当前字符串加入池并返回当前引用
      C: intern() 会把字符串池中的对象复制一份到堆上
      D: 大量调用 intern() 会给常量池带来压力，可能增加 GC 负担
    answer: ABD
    analysis: C 错误。intern 是让相同内容指向池中同一个对象，不会复制池对象到堆上。A、B 是 intern 的两种分支，D 成立，因为池里的条目同样占堆内存。

  - type: CLOZE
    stem: |
      补全下面代码，使第一行的 == 结果为 true、第二行为 false：
      ```java
      String s = new String("a").{{1}}();   // 与字面量 "a" 的 == 结果为 {{2}}
      boolean b = new String("a") == "a";   // 结果为 {{3}}
      ```
    blanks:
      - ["intern"]
      - ["true"]
      - ["false"]
    analysis: new String("a") 是堆上的新对象；intern() 返回池中与它内容相等的字面量，所以 == 为 true。不调用 intern 时 new 出来的对象与字面量不是同一个，== 为 false。
    difficulty: 2
````
