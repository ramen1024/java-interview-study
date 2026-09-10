---
slug: autoboxing-cache
title: 自动装箱有什么陷阱？Integer 缓存池是怎么回事？
module: java-basics
tags: [自动装箱, Integer, 缓存池, 拆箱]
difficulty: 1
frequency: 2
related:
  - slug: equals-hashcode-contract
    type: RELATED
  - slug: stream-and-lambda
    type: RELATED
---

## 电梯版回答

自动装箱是编译器的语法糖：`Integer i = 1` 会编译成 `Integer.valueOf(1)`，`int j = i` 编译成 `i.intValue()`。`Integer.valueOf` 对 -128 到 127 的值返回缓存里预先创建好的同一个对象，所以这个区间内两个相同值的 Integer 用 `==` 比较是 true，超出这个区间就各自新建对象、`==` 为 false。上界可以用 HotSpot 的 `-XX:AutoBoxCacheMax` 调大，下界 -128 是固定的，而且这个开关只影响 Integer。缓存不是 Integer 独有：Byte、Short、Long 也是 -128 到 127，Character 是 0 到 127，Boolean 只有两个常量，Double 和 Float 完全没有缓存。对比包装类型必须用 equals 或 `Objects.equals`。最常见的两个坑是包装类型为 null 时拆箱会 NPE，以及三元表达式里 Integer 和 int 混用会触发数值提升、把 Integer 拆箱，null 直接炸。另外 `new Integer(...)` 从 Java 9 起已废弃，循环里用包装类型累加会不断装箱。

## 展开讲解

### 语法糖展开

装箱（boxing）和拆箱（unboxing）是 JLS 规定的编译期转换，只对 8 种包装类型加 `void` 有效，编译器把它翻译成工厂方法和取值方法：

```java
Integer a = 1;              // Integer.valueOf(1)
int b = a;                  // a.intValue()
Integer c = a + 1;          // a.intValue() + 1，再 Integer.valueOf(...)
List<Integer> list = new ArrayList<>();
list.add(1);                // Integer.valueOf(1)
int d = list.get(0) == 1 ? 1 : 0;  // 拆箱后按值比较
```

理解所有陷阱的关键就是：**每一处自动转换背后都是一次 `valueOf` 或 `xxxValue()` 调用**。看字节码可以直接看到它们。

### IntegerCache 的逻辑

`Integer.valueOf` 的核心逻辑（省略了 CDS 归档等细节）：

```java
public static Integer valueOf(int i) {
    if (i >= IntegerCache.low && i <= IntegerCache.high) {
        return IntegerCache.cache[i + (-IntegerCache.low)];
    }
    return new Integer(i);
}

private static class IntegerCache {
    static final int low = -128;   // 下界固定，不可配置
    static final int high;         // 默认 127，可配置
    static final Integer[] cache;  // 启动时一次性创建

    static {
        int h = 127;
        // 读取 java.lang.Integer.IntegerCache.high，取 max(配置值, 127)
        // 并夹在 Integer.MAX_VALUE - (-low) - 1 以内
        high = h;
        cache = new Integer[(high - low) + 1];
        // 依次填充 -128 ... high
    }
}
```

要点：

- 缓存区间是 **[-128, 127]**。下界 `low = -128` 是写死的，改不了。
- 上界可以通过系统属性 `java.lang.Integer.IntegerCache.high` 配置，HotSpot 提供了等价的 VM 参数 `-XX:AutoBoxCacheMax=<size>`。它只会把上界往上调（内部取 `max(配置值, 127)`），不会缩小到 127 以下。
- 缓存数组在类初始化时一次性创建，是启动成本换运行期少量分配，调得很大只是多占内存。
- **只影响 `Integer`**，`Long`、`Short`、`Byte` 的缓存是各自代码里写死的，不受这个参数影响。

### == 比较行为

```java
Integer a = 127;
Integer b = 127;
System.out.println(a == b);   // true，命中缓存，同一个对象

Integer c = 128;
Integer d = 128;
System.out.println(c == d);   // false，各自 new 出来的对象

Integer e = new Integer(127);
Integer f = 127;
System.out.println(e == f);   // false，new 一定绕开缓存

Integer g = 1000;
int h = 1000;
System.out.println(g == h);   // true，g 被拆箱，按值比较
```

最后一条是关键：**只要比较的一边是基本类型，另一边就会被拆箱**，比较变成值比较；只有两边都是包装类型时才比较引用。所以「Integer 用 `==` 比较」的结果同时取决于取值范围和另一边是不是基本类型，属于典型的「有时对有时错」。

### 各包装类型的缓存情况

| 类型 | 缓存范围 | 是否可配置 |
|---|---|---|
| `Byte` | -128 ~ 127（覆盖全部取值） | 否 |
| `Short` | -128 ~ 127 | 否 |
| `Integer` | -128 ~ 127（默认） | 上界可调（HotSpot 的 `-XX:AutoBoxCacheMax` 或系统属性 `java.lang.Integer.IntegerCache.high`），下界固定 |
| `Long` | -128 ~ 127 | 否 |
| `Character` | 0 ~ 127（覆盖 ASCII） | 否 |
| `Boolean` | 只有 `TRUE` / `FALSE` 两个静态常量 | 否 |
| `Float`、`Double` | 无缓存，每次 `valueOf` 都新建对象 | — |

所以：

```java
Boolean b1 = true;
Boolean b2 = true;
System.out.println(b1 == b2);              // true，都是 Boolean.TRUE

Double d1 = 1.0;
Double d2 = 1.0;
System.out.println(d1 == d2);              // false，没有缓存

Character ch1 = 'a';
Character ch2 = 'a';
System.out.println(ch1 == ch2);            // true，0~127 命中 CharacterCache
```

### 拆箱导致的 NPE

引用为 null 时拆箱，等价于对 null 调 `intValue()`，抛 `NullPointerException`：

```java
Integer i = null;
int j = i;                                  // NPE

Map<String, Integer> map = new HashMap<>();
int v = map.get("missing");                 // NPE，key 不存在返回 null

public int getCount() {
    return count;                            // count 是 Integer 且为 null 时 NPE
}
```

特别容易忽略的是 `Map.getOrDefault`：如果 key **存在但 value 是 null**，它返回的是 null 而不是默认值，接着拆箱照样 NPE。数据库可空列用 `Integer` 承接、JSON 可选字段用包装类型、`Integer count = 0` 后来被赋 null，是三类高频现场。

### 三元表达式的类型提升

JLS 15.25 对条件表达式做类型统一：当两个分支一个是 `int`、另一个是 `Integer` 时，会做二进制数值提升，结果类型是 `int`，`Integer` 那一支被自动拆箱。

```java
Integer a = null;
Integer b = true ? a : 0;                     // NPE！a 被拆箱
Integer c = true ? a : Integer.valueOf(0);    // 没问题，两支都是 Integer
```

同理 `Boolean` 与 `boolean` 混用也会拆箱。防法是让两个分支保持同一种包装类型，或者先判空。

### 循环里用包装类型累加

```java
Long sum = 0L;
for (long i = 0; i < 1_000_000; i++) {
    sum += i;                               // 每轮都拆箱相加再装箱
}

Integer count = 0;
for (int i = 0; i < 1_000_000; i++) {
    count++;                                // 每轮装箱，且 count 可能为 null
}
```

每次 `+=` 都是 `sum = Long.valueOf(sum.longValue() + i)`，产生大量短命对象，加重 GC；对象进入集合或被返回后，逃逸分析通常也无法把它们优化掉。热循环、大数据量场景应直接用 `long` / `int`。

### 该用什么写法

- 比较包装类型的值：`Objects.equals(a, b)`；数值大小比较用 `Integer.compare(a, b)`，避免用减法。
- 能确定非 null 且需要按值比较时才用 `==`（此时会拆箱），否则一律 `equals`。
- 只在需要表达「没有值」时用包装类型（数据库可空列、JSON 可选字段、`Map.get` 的返回值）；循环变量、计数器、热路径用基本类型。
- 集合和泛型必须用包装类型，批量数值计算改用 `int[]`、`IntStream`、`LongStream`。
- 方法声明返回 `int` 就不要实际返回可能为 null 的 `Integer`，或者在里面 null 转默认值。

## 追问链

### Q1: 为什么 `Integer a = 127; Integer b = 127; a == b` 是 true，换成 128 就变成 false？

因为 `Integer.valueOf` 对 [-128, 127] 直接返回 `IntegerCache.cache` 里预先创建好的对象，同一个值拿到的就是同一个引用，`==` 为 true；127 恰好是默认上界，128 不在缓存内，两次调用各自 `new Integer(...)`，引用不同，`==` 为 false。这说明用 `==` 比较 Integer 的结果取决于运行期的具体取值，是最坏的一类「有时对有时错」。

#### Q1.1: 缓存的上界能不能调大？

能。HotSpot 上可以用 `-XX:AutoBoxCacheMax=<size>` 把 `Integer` 缓存上界调大，等价于设置系统属性 `java.lang.Integer.IntegerCache.high`。但下界恒为 -128，改不了；上界只能往上调，不能缩到 127 以下；而且这个开关只作用于 `Integer`——`Long`、`Short`、`Byte` 的缓存是各自写死的 -128~127，`Character` 固定 0~127，`Double`、`Float` 根本没有缓存。

##### Q1.1.1: 那这类 `==` 比较的代码为什么经常是测试全绿、上线才出问题？

因为对错取决于值落在哪个区间。比如 `Integer a = 100, b = 100; Integer c = 1000, d = 1000; boolean ok = (a == b) && (c == d);`，前半段 true、后半段 false，只要测试数据的数值都小于 128 就永远绿；上线遇到大数值或用户 ID 就翻车。`Long` 的缓存上界同样只有 127，`Long id == 128L` 是 ID 场景的经典踩坑点。根治办法是永远用 `equals` / `Objects.equals`，或者先拆箱成基本类型再比。

### Q2: 你说会 NPE，具体哪些写法最容易踩？

核心是「包装类型为 null 时发生自动拆箱」：赋给基本类型、参与算术和比较、作为基本类型参数传参、从返回 int 的方法返回、进三元表达式、进 switch、以及 `count++` 这类复合赋值都会拆箱。最常见的三处是 map 取值直接赋给 int（key 不存在返回 null）、DTO 用 `Integer` 承接数据库可空列后直接参与计算、以及 `getOrDefault` 在「key 存在但 value 为 null」时照样返回 null 导致拆箱 NPE。

#### Q2.1: 三元表达式为什么特别容易踩？

因为条件表达式的两个分支会先做类型统一（JLS 15.25）：一边是 `Integer`、另一边是 `int` 字面量时，结果类型被提升为 `int`，`Integer` 分支被自动拆箱，为 null 就抛 NPE。所以 `Integer b = true ? a : 0;` 会炸，而 `Integer b = true ? a : Integer.valueOf(0);` 不会。`Boolean` 与 `boolean` 混用同理。防法是两支保持同一种包装类型，或者先判空再进表达式。

##### Q2.1.1: 那 `Integer` 和 `Long` 混在同一个三元表达式里会怎样？

不是编译错误，而是静默的数值提升：`Integer` 拆成 int、`Long` 拆成 long，结果类型是 `long`，赋值处再装箱成 `Long`。于是 `true ? someInteger : someLong` 实际得到的是 `Long` 实例，小数值看不出问题，但类型和精度语义已经变了；如果 `Integer` 为 null 同样 NPE。这类隐式提升在表达式里最难发现，不要依赖自动转换，显式拆箱和转换更可控。

### Q3: 装箱除了 `==` 和 NPE，还有什么实际代价？

对象分配和 GC 压力。`List<Integer>` 里每个元素都是独立对象（对象头加 4 字节 int，常见实现下大约 16 字节，再加上列表里的引用），远大于一个 int 的 4 字节；循环里 `sum += boxed` 会持续产生短命对象。JIT 的逃逸分析能消除一些局部变量的装箱，但对象进入集合、被返回、被闭包捕获后基本无法消除。这也是 `IntStream`、`LongStream`、`OptionalInt` 这些特化类型存在的原因。

#### Q3.1: 那什么时候必须用包装类型？

三种场景：泛型与集合（`List<int>` 不合法）；需要表达「没有值」这个语义（数据库可空列、JSON 可选字段、`Map.get` 查不到）；与既有 API 交互。其余情况优先基本类型，尤其是循环计数、大数据量数组和性能敏感路径。

##### Q3.1.1: 把 `-XX:AutoBoxCacheMax` 调大能解决装箱性能问题吗？

不能。它只放大 `Integer` 的缓存区间，减少的是小整数重复装箱的那部分分配，对超出区间的大数值、对 `Long`/`Double`、对集合本身持有对象的结构性开销都无能为力，而且缓存数组本身要占内存。真正的性能手段是避免在热路径装箱：用 `int`/`long` 累积、用 `int[]` 或特化流、用基本类型表达 ID（如果 ID 必须用 `Long`，至少别用 `==` 比）。

## 常见坑

- **「Integer 缓存的范围上下界都能调」** —— 只有上界能调（HotSpot 的 `-XX:AutoBoxCacheMax` 或 `java.lang.Integer.IntegerCache.high`），下界恒为 -128，且只影响 Integer。
- **「`==` 比较 Integer 有时对，所以可以接受」** —— 对小数对是因为缓存，对大数是 false；同一段代码的正确性取决于运行期的值，必须用 `equals` / `Objects.equals` / 先拆箱。
- **「Double、Float 也有缓存，所以 `Double d1 = 1.0, d2 = 1.0; d1 == d2` 是 true」** —— Double 和 Float 没有缓存，两个装箱的相同字面量是不同对象，`==` 为 false。
- **「`new Integer(127) == Integer.valueOf(127)` 是 true」** —— `new` 一定创建新对象、绕开缓存，`==` 为 false；`new Integer(...)` 从 Java 9 起已废弃。
- **「`Boolean` 没有缓存，两个 true 不相等」** —— `Boolean.valueOf` 返回静态常量 `Boolean.TRUE` / `Boolean.FALSE`，所以相等，Boolean 只有这两个实例。
- **「三元表达式会自动选合适的类型，不会出错」** —— `int` 与 `Integer`、`boolean` 与 `Boolean` 混用会拆箱，null 时 NPE，结果类型还会被提升为基本类型。
- **「`Integer` 和 `int` 比较比的是引用」** —— 只要一边是基本类型就会拆箱按值比较，`Integer.valueOf(1000) == 1000` 是 true；只有两边都是包装类型才比引用。
- **「装箱开销可以忽略，JVM 会优化掉」** —— 局部变量上可能被标量替换，但对象进入集合、被返回或被闭包捕获后基本无法消除，热循环里的装箱是实打实的分配和 GC 成本。
- **「`getOrDefault` 能保证拿到非 null」** —— key 存在但 value 为 null 时它返回 null，不会返回默认值，后面拆箱就 NPE。

## 加分点

- **`IntegerCache` 的高位只在类初始化时读一次**，启动后再改系统属性无效；`-XX:AutoBoxCacheMax` 本质上也是把它转成系统属性，所以必须在启动参数里给。缓存数组是一次性预分配的，调大是拿启动时间和内存换运行期的少量分配。
- **`Long` 有独立的 `LongCache`**，范围同样是 -128~127，但不受 `-XX:AutoBoxCacheMax` 影响。ID、时间戳、数量这类字段很容易踩 `Long` 引用比较的坑。
- **`Double.equals` 与 `==` 语义不同**：`Double.equals` 基于 `doubleToLongBits`，`Double.NaN.equals(Double.NaN)` 为 true，而 `+0.0` 与 `-0.0` 用 `equals` 不相等、用 `==` 相等，正好和直觉相反。包装类型的 `compareTo` 也遵循这套规则，比较浮点包装类型要特别小心。
- **自动装箱只覆盖 8 种包装类型加 void**（JLS 5.1.7），数组不参与；反编译或看字节码能看到 `Integer.valueOf` 和 `intValue`，这是理解所有相关坑的统一入口。
- **`Objects.equals` 是最省心的替代写法**，它对两个包装类型调用 `equals`、对 null 安全；数值比较用 `Integer.compare(a, b)`，它避免了 `a - b` 在极端值下的溢出。
- **想验证缓存边界可以用 `Integer.valueOf(127) == Integer.valueOf(127)` 和 `128` 对比**，再用 `-XX:AutoBoxCacheMax=1000` 启动后重复观察 128 变成 true，这样能确认参数确实作用在上界。

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 5 | 引入自动装箱与拆箱（JLS 5.1.7 / 5.1.8）以及 `IntegerCache`；`valueOf` 成为推荐的工厂方法 |
| Java 9 | `Integer(int)`、`Integer(String)` 等包装类型构造器标记为 `@Deprecated(since="9")`，文档推荐改用 `valueOf` / `parseInt` |
| 现行 JDK（JDK 21 javadoc 口径） | 上述构造器显示为 `@Deprecated(since="9", forRemoval=true)`，新代码不应再使用 `new Integer(...)` |
| HotSpot（与 Java 语言版本无关） | `-XX:AutoBoxCacheMax=<size>` 可调大 Integer 缓存上界，等价于 `-Djava.lang.Integer.IntegerCache.high=<size>`；下界 -128 固定，且只作用于 Integer |

## 自测题

````yaml
questions:
  - type: JUDGE
    stem: "`Integer a = 128; Integer b = 128; a == b` 的结果是 true。"
    answer: F
    analysis: Integer 默认只缓存 -128 到 127，128 超出范围，两次 Integer.valueOf 各自创建对象，引用不同所以 == 为 false。若把 128 改成 127 则为 true。

  - type: CHOICE
    stem: 下面哪种包装类型完全没有对象缓存，每次 valueOf 都新建对象？
    options:
      A: Integer
      B: Character
      C: Double
      D: Boolean
    answer: C
    analysis: Double 和 Float 没有缓存。Byte、Short、Integer、Long 缓存 -128~127，Character 缓存 0~127，Boolean 只有 TRUE/FALSE 两个静态常量。

  - type: MULTI
    stem: 关于自动装箱与拆箱，下列说法正确的有？
    options:
      A: "`Integer i = 1` 会被编译成 Integer.valueOf(1)"
      B: 包装类型为 null 时参与算术运算会抛 NullPointerException
      C: "-XX:AutoBoxCacheMax 既能调大也能调小 Integer 缓存的下界"
      D: "`new Integer(1)` 从 Java 9 起已被标记废弃"
    answer: ABD
    analysis: C 错误。Integer 缓存下界恒为 -128 不可配置，-XX:AutoBoxCacheMax 只能把上界往上调，且只影响 Integer。A、B、D 都成立。

  - type: CLOZE
    stem: |
      下面代码会在赋值时抛异常，补全异常类型和三元表达式里的字面量：
      ```java
      Integer a = null;
      // 这行会抛 {{1}}，因为三元表达式把 a 拆箱了
      Integer b = true ? a : {{2}};
      ```
    blanks:
      - ["NullPointerException", "NPE"]
      - ["0"]
    analysis: 三元表达式两个分支类型统一时做二进制数值提升，int 与 Integer 混用会把 Integer 拆箱，结果类型为 int。a 为 null 时拆箱抛 NullPointerException。
    difficulty: 2
````
