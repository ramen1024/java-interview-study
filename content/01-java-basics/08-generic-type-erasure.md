---
slug: generic-type-erasure
title: 泛型擦除是什么？为什么要擦除？
module: java-basics
tags: [泛型, 类型擦除, PECS, 桥接方法]
difficulty: 2
frequency: 2
related:
  - slug: reflection-internals
    type: RELATED
  - slug: serialization
    type: RELATED
---

## 电梯版回答

泛型是编译期的语法糖，类型参数在编译后被擦除：无界参数替换成 Object，有界参数替换成它的第一个上界。所以 `List<String>` 和 `List<Integer>` 在运行时是同一个 Class，泛型实参只保留在 class 文件声明处的 Signature 属性里，供反射按声明读取。擦除的首要动机是向后兼容——泛型是 Java 5 引入的，擦除让新编译的字节码方法描述符与旧的裸类型版本一致，新代码能和没有泛型的既有类库互相调用，旧 JVM 也照样运行。代价是不能 `new T[]`、不能用基本类型做类型参数、静态成员不能引用类级类型参数，还会产生编译器合成的桥接方法，以及擦除后签名相同的泛型重载冲突。通配符的用法用 PECS 记：生产者用 `? extends T`，消费者用 `? super T`。

## 展开讲解

### 擦除的动机：向后兼容

Java 5 引入泛型时，已经存在海量的字节码和类库（`java.util` 里的 `Collections`、`List` 全是裸类型）。语言设计者面临两条路：

- **具化泛型**：为每一组类型参数生成独立的类/方法，运行时保留类型信息（C# 的泛型就是这条路，因此 C# 能 `new T[]`、能 `typeof(T)`）。
- **类型擦除**：编译期检查类型，编译后把类型参数抹掉，只保留一份字节码。

Java 选了擦除，核心目标就是**二进制兼容**。`List<String>` 与旧的裸 `List` 在字节码层面是同一个方法描述符，所以：

- 旧代码可以不加修改地调用新写的泛型代码（可能产生 unchecked 警告）；
- 新代码可以调用旧的非泛型类库；
- 升级是渐进的，不需要一次重写所有调用点。

代价就是运行时拿不到泛型实参，以及下面那一串限制。

### 擦除的规则

按 Java 语言规范，类型擦除后：

| 声明的类型参数 | 擦除后替换为 |
|---|---|
| 无界 `T` | `Object` |
| `T extends Number` | 上界 `Number` |
| `T extends A & B`（多上界） | 第一个上界 `A` |
| `T[]` | `Object[]` 或上界类型的数组 |
| 泛型方法自己的 `<T>` | 同样按上述规则擦除 |

擦除后编译器会在需要的地方插入强制类型转换来保证安全：

```java
List<String> list = new ArrayList<>();
list.add("a");
String s = list.get(0);
// 擦除后 get 的签名是 Object get(int)，编译器在调用点插入 (String) 强转
```

用 `javap -c` 反编译能看到一条 `checkcast java/lang/String`。所以泛型是「编译期的静态检查 + 少量运行时检查」，不是零成本。

### 运行时是同一个 Class

```java
List<String> a = new ArrayList<>();
List<Integer> b = new ArrayList<>();
System.out.println(a.getClass() == b.getClass());   // true，都是 java.util.ArrayList
```

但声明处的泛型信息并没有丢，它保存在 class 文件的 `Signature` 属性里（字段类型、方法参数/返回值、父类与接口的签名）。反射可以把它读出来：

```java
Field f = MyClass.class.getDeclaredField("names");
ParameterizedType t = (ParameterizedType) f.getGenericType();
System.out.println(t.getActualTypeArguments()[0]);   // class java.lang.String
```

关键区分：**反射能拿到「声明」的泛型信息，拿不到「实例」的元素类型**。一个 `ArrayList` 实例本身不携带元素类型；局部变量和 `new ArrayList<>()` 里推断出来的实参也不会写进字节码（`LocalVariableTypeTable` 通常只记录变量名，且推断结果无法还原），所以对 `list.getClass()` 做任何泛型推断都是徒劳。

### 擦除带来的限制

```java
// 1. 不能创建泛型数组
T[] arr = new T[10];            // 编译错误: generic array creation
T[] arr2 = (T[]) new Object[10]; // 能编译（配 @SuppressWarnings），但不类型安全

// 2. 不能用基本类型做实参
List<int> bad;                  // 编译错误
List<Integer> ok;               // 只能装箱

// 3. 静态成员不能引用类级类型参数
class Box<T> {
    static T value;             // 编译错误
    static void f(T t) {}       // 编译错误
    static <U> U g(U u) { return u; }  // 静态方法可以声明自己的类型参数
}

// 4. instanceof 不能带泛型实参
if (o instanceof List<String>) {}  // 编译错误
if (o instanceof List<?>) {}       // 可以

// 5. 泛型重载冲突
void h(List<String> x) {}
void h(List<Integer> x) {}      // 编译错误: name clash，两者都擦除成 h(List)
```

原因都是同一个：擦除后类型参数不存在，而数组必须知道自己的元素类型、方法签名必须用具体描述符、静态成员无法绑定到某个具体实例化。

### 桥接方法（bridge method）

擦除会破坏多态，桥接方法是编译器打的补丁：

```java
class Node<T> {
    T get() { return null; }
}

class StringNode extends Node<String> {
    @Override
    String get() { return "s"; }
}
```

擦除后父类方法是 `Object get()`，子类方法是 `String get()`，两者描述符不同，子类的 `get` 看起来并没有覆盖父类的方法。编译器于是在 `StringNode` 里合成一个桥接方法：

```java
// 编译器生成，等价于
Object get() {
    return this.get();   // 转调子类的 String get()
}
```

用 `javap -p -c StringNode.class` 能看到两个 `get` 方法，桥接方法带 `ACC_BRIDGE` 和 `ACC_SYNTHETIC` 标志。泛型接口的实现在赋值、反射调用时都会依赖它。这也解释了为什么 `getDeclaredMethods()` 有时会多出「自己没写过的」方法。

### 通配符与 PECS

- `? extends T`：上界通配符，表示「某种 T 的子类型」。可以安全地**读**出来当作 T，但不能往里写（除了 null）——因为不知道具体是哪个子类型，写 T 之外的都不安全。
- `? super T`：下界通配符，表示「某种 T 的父类型」。可以安全地**写**入 T 及其子类型，但读出来只能当作 `Object`。

记忆口诀 **PECS**：Producer Extends, Consumer Super。参数是数据来源（生产者）用 extends，参数是数据去向（消费者）用 super：

```java
public static <T> void copy(List<? extends T> src, List<? super T> dest) {
    for (T t : src) {
        dest.add(t);
    }
}
```

JDK 的 `Collections.copy(List<? super T> dest, List<? extends T> src)` 就是这个形态。`List<? super Integer>` 可以接收 `List<Integer>`、`List<Number>`、`List<Object>`；`List<? extends Number>` 可以接收 `List<Integer>`、`List<Double>`，但不能接收 `List<Object>`。

### `List<Object>`、`List<?>`、裸 `List` 的区别

| 写法 | 含义 | 读 | 写 |
|---|---|---|---|
| `List<Object>` | 元素类型确定是 Object 的列表 | 可以读成 Object | 可以 add 任意对象 |
| `List<?>` | 元素类型未知的列表 | 只能读成 Object | 不能 add（null 除外） |
| `List`（裸类型） | 关闭泛型检查 | 读成 Object | 能 add 任意对象，但产生 unchecked 警告 |

两个易错点：泛型**不变**，`List<String>` 不能赋给 `List<Object>`，但可以赋给 `List<?>`；裸类型不是 `List<?>`，它绕过编译检查，可能造成堆污染（heap pollution），`List<?>` 则是安全的只读视图。

## 追问链

### Q1: 泛型擦除到底擦成什么？能举个能用 javap 验证的例子吗？

无界类型参数擦成 `Object`，有界参数擦成第一个上界。例子是一个泛型方法 `T get()`，擦除后签名变成 `Object get()`；在调用点编译器会插入 `checkcast`，用 `javap -c` 能看到这条指令。所以「擦除」不是把泛型代码删掉，而是把类型参数替换成上界，再把本该类型安全的转换显式补回字节码。

#### Q1.1: 既然擦除了，为什么反射还能拿到 `List<String>` 里的 String？

因为声明处的泛型信息被写进了 class 文件的 `Signature` 属性：字段类型、方法参数和返回值、父类和接口的签名都会保留。`Field.getGenericType()`、`Method.getGenericParameterTypes()` 返回的是 `ParameterizedType`，能取到 `getActualTypeArguments()`。所以准确说法是「运行时实例不带泛型信息，但类文件保留声明处的泛型签名」。

##### Q1.1.1: 那为什么局部变量的泛型实参就反射不到？

因为局部变量和表达式的泛型实参是编译器内部的类型推断结果，不会作为「需要被其他编译单元看到的声明」写进 class 文件。`List<String> x = new ArrayList<>();` 里的 `String` 只用于编译期检查；diamond 推断出的实参更是没有对应属性可存。只有字段、方法/构造器签名、父类与接口这些「声明」才会进 `Signature` 属性。这也是为什么框架（如 Jackson）要靠匿名子类 `new TypeReference<List<String>>() {}` 把泛型签名固化到 class 文件里，才能绕过擦除做反序列化。

### Q2: 为什么 Java 要选择擦除这种看起来有损的方案？

为了向后兼容。泛型是 Java 5 才加的，当时已有大量编译好的字节码和类库。擦除让 `List<String>` 与旧的裸 `List` 拥有相同的方法描述符，新老代码可以互相调用、旧 JVM 也能运行，升级只需处理 unchecked 警告而不是重写全部调用点。换句话说，这是用运行时的类型信息换取生态的平滑迁移。

#### Q2.1: 擦除付出的代价具体有哪些？

不能 `new T[]`（数组需要具体元素类型）、不能用基本类型做实参（类型参数必须是引用类型）、静态成员不能引用类级类型参数（静态成员不属于某个实例化）、`instanceof` 不能带泛型实参、以及泛型重载冲突。这些限制的根源都是同一条：运行时不存在类型参数。

##### Q2.1.1: 泛型重载冲突为什么编译器一定要报错，不能靠方法名区分吗？

JVM 的方法解析靠「方法名 + 方法描述符（参数类型 + 返回值）」定位。两个方法 `h(List<String>)` 和 `h(List<Integer>)` 擦除后描述符都是 `h(List)`，在 JVM 里就是同一个方法，无法共存。所以编译器必须在编译期就报 `name clash`，而不是留到运行时。

### Q3: 通配符 `? extends` 和 `? super` 什么时候用哪个？

用 PECS：从参数里读数据（生产者）用 `? extends T`，往参数里写数据（消费者）用 `? super T`。`? extends T` 只保证能安全读成 T，写入不安全；`? super T` 只保证能安全写入 T，读出来只能当 Object。典型例子是 `Collections.copy(List<? super T> dest, List<? extends T> src)`。

#### Q3.1: `List<Object>`、`List<?>` 和裸 `List` 三者的区别是什么？

`List<Object>` 元素类型确定是 Object，既能读也能 add 任意对象；`List<?>` 是元素类型未知的只读视图，只能读成 Object、不能 add（null 除外）；裸 `List` 关闭泛型检查，能 add 但会产生 unchecked 警告并有堆污染风险。另外泛型不变，`List<String>` 不能赋给 `List<Object>`，但可以赋给 `List<?>`。

##### Q3.1.1: 为什么 `List<String>` 不能赋给 `List<Object>`，而 `String[]` 却能赋给 `Object[]`？

泛型是不变的，数组是协变的，这是两套不同的规则。数组协变意味着 `Object[] x = new String[1]; x[0] = 1;` 能通过编译，但运行时抛 `ArrayStoreException`——把类型检查推迟到了运行时，是被公认的历史设计缺陷。泛型选择了不变，把这类错误在编译期就拦住；如果泛型协变，`List<Object>` 里塞个 Integer 就破坏了 `List<String>` 的约定，所以必须禁止。

### Q4: 桥接方法解决了什么问题？

它解决了擦除破坏多态的问题。子类 `StringNode.get()` 返回 `String`，擦除后与父类的 `Object get()` 描述符不一致，无法构成覆盖；编译器于是合成一个返回 `Object` 的桥接方法，内部转调子类真实方法，保证多态分派仍然正确。桥接方法带 `ACC_BRIDGE` 和 `ACC_SYNTHETIC` 标志，`getDeclaredMethods()` 会把它列出来。

#### Q4.1: 桥接方法还会在哪些场景出现？

除了泛型类/接口的覆盖，还有协变返回类型（子类把返回类型改窄）、泛型接口的实现（编译器要生成把参数/Object 转换后再转发的方法）。反射调用时如果拿到的是桥接方法，实际执行的是它转调的那个实现。框架做方法解析时通常会过滤 `isBridge()`/`isSynthetic()`，避免把合成方法误当成业务方法。

## 常见坑

- **「泛型在运行时完全不存在」** —— 不准确。声明处的泛型信息保留在 `Signature` 属性里，反射能读到 `List<String>` 的 String；不存在的只是实例层面的元素类型。
- **「`List<String>` 和 `List<Integer>` 是两个不同的类」** —— 擦除后是同一个 Class，`getClass()` 相等。
- **「擦除是因为 JVM 不支持泛型」** —— 这是设计选择，为了兼容已有字节码和类库；JVM 本身保留 `Signature` 属性，说明并非能力不足。
- **「`List<?>` 等价于 `List<Object>`」** —— 前者不能添加元素（null 除外），后者可以；前者是未知类型的只读视图。
- **「裸类型 `List` 和 `List<?>` 一样」** —— 裸类型绕过泛型检查，会产生 unchecked 警告和堆污染风险，`List<?>` 是类型安全的。
- **「PECS 记成 extends 用来写、super 用来读」** —— 反了。extends 只能安全读，super 只能安全写。
- **「`new T[]` 用 `(T[]) new Object[n]` 就安全了」** —— 能编译但破坏了数组的类型契约，若把它当具体类型的数组用，运行时可能抛 `ClassCastException` 或造成堆污染，需要 `@SuppressWarnings("unchecked")` 并确保不暴露。
- **「桥接方法是自己写的，或者能用 source 看到」** —— 它是编译器合成的，源码里没有，带 `ACC_BRIDGE`/`ACC_SYNTHETIC` 标志，只能通过 `javap` 看到。

## 加分点

- **具化泛型的横向对比**：C# 泛型在运行时保留类型参数，可以 `new T[]`、`typeof(T)`，但会为每组实参生成代码（代码膨胀）；Java 擦除后泛型代码只有一份（code sharing），对 JIT 和包体积更友好。这是两种设计取舍的典型对照。
- **堆污染与 @SafeVarargs**：泛型可变参数 `T...` 擦除后是 `Object[]`，调用方传入泛型数组会产生 unchecked 警告；`@SafeVarargs` 开发者承诺实现不会污染堆，它只能标在 static、final 或 private 方法上（Java 9 起扩展到 private 实例方法）。
- **框架如何绕过擦除**：Jackson 的 `TypeReference`、Spring 的 `ParameterizedTypeReference` 都要求用匿名子类，就是为了让编译器把 `List<String>` 这个签名写进子类的 `Signature` 属性，再用反射读出来。
- **桥接方法的反射坑**：`getMethods()`/`getDeclaredMethods()` 可能返回桥接方法，做方法匹配时要用 `Method.isBridge()` 过滤，否则可能解析到错误的实现。
- **`javap` 实证习惯**：用 `javap -p -c -s` 看泛型类，能同时看到桥接方法、`checkcast` 和 `Signature` 属性，把抽象结论变成可验证证据。

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 5 | 引入泛型，采用类型擦除实现；同时引入自动装箱、可变参数、注解 |
| Java 7 | 引入 diamond `new ArrayList<>()`，编译器可从目标类型推断类型实参 |
| Java 8 | 目标类型推断增强，lambda 和方法引用的形参类型可由上下文推断 |
| Java 9 | 允许匿名类使用 diamond（`new ArrayList<>() {}`）；`@SafeVarargs` 扩展到 private 实例方法 |
| Java 10 | 引入 `var` 局部变量类型推断，只是语法糖，不影响擦除行为 |

## 自测题

````yaml
questions:
  - type: JUDGE
    stem: 在运行期，new ArrayList<String>().getClass() 与 new ArrayList<Integer>().getClass() 返回的 Class 对象相同。
    answer: T
    analysis: 泛型实参在编译后被擦除，两者都是 java.util.ArrayList。泛型信息只保留在声明处的 Signature 属性里，实例本身不携带元素类型。

  - type: CHOICE
    stem: 关于 List<?>、List<Object> 和裸类型 List，下列说法正确的是？
    options:
      A: List<?> 可以添加任意类型的元素
      B: List<?> 只能读出 Object，不能添加除 null 外的元素
      C: 裸类型 List 是类型安全的，不会有 unchecked 警告
      D: List<String> 可以直接赋值给 List<Object>
    answer: B
    analysis: "List<?> 表示元素类型未知，只能安全读成 Object，不能写入（null 除外）。A 错；裸类型会绕过检查产生警告，C 错；泛型不变，List<String> 不能赋给 List<Object>，但可以赋给 List<?>，D 错。"

  - type: MULTI
    stem: 泛型擦除会直接导致下列哪些限制？
    options:
      A: 不能创建泛型数组 new T[]
      B: 不能用基本类型作为类型参数
      C: 静态成员不能引用类级类型参数
      D: 编译后所有泛型方法都变成解释执行
    answer: ABC
    analysis: D 是编造的，擦除与执行方式无关。A、B、C 的根源都是运行时不存在类型参数：数组需要具体元素类型、类型参数必须能被 Object 承接、静态成员不属于某个实例化。

  - type: CLOZE
    stem: |
      按 PECS 原则补全下面这个泛型拷贝方法的通配符：
      ```java
      public static <T> void copy(
          List<? {{1}} T> src,
          List<? {{2}} T> dest
      ) {
          for (T t : src) {
              dest.add(t);
          }
      }
      ```
    blanks:
      - ["extends"]
      - ["super"]
    analysis: src 是数据来源（生产者），用 ? extends T 可以安全读出 T；dest 是数据去向（消费者），用 ? super T 可以安全写入 T。合起来就是 PECS——Producer Extends, Consumer Super。
    difficulty: 3
````
