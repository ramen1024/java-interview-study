---
slug: initialization-order
title: 类初始化和实例初始化的顺序是怎样的？
module: java-basics
tags: [类初始化, 静态块, 实例块, 构造器]
difficulty: 2
frequency: 2
related:
  - slug: class-loading-parent-delegation
    type: PREREQUISITE
  - slug: autoboxing-cache
    type: RELATED
---

## 电梯版回答

要分两段看。类初始化只在「首次主动引用」时发生，每个类加载器里只执行一次：先父类后子类，同一个类里静态字段的显式赋值和 static 块按源码顺序执行，编译后它们被合成一个 `<clinit>` 方法。实例初始化每次 `new` 都会发生：先执行父类的实例字段赋值和实例块，再执行父类构造器；然后执行子类的实例字段赋值和实例块，最后是子类构造器。最经典的陷阱是在父类构造器里调用一个能被子类重写的方法：方法会动态绑定到子类版本，但此时子类字段的显式赋值还没执行，读到的是类型默认值，引用是 null、数值是 0，所以常看到打印 `null` 或 `0`。另一个要点是准备阶段会先把所有静态字段置为类型零值，而 `static final` 的编译期常量更特殊，它的值在准备阶段就通过 `ConstantValue` 写好，甚至会被内联，读它根本不会触发那个类的初始化。

## 展开讲解

### 类生命周期的三步

1. **加载**：读取 class 文件，生成对应的 `Class` 对象。
2. **链接**：验证（字节码合法性）、准备、解析。准备阶段为静态字段分配内存并置**类型零值**（`int` 为 0、`boolean` 为 false、引用为 null）；只有带 `ConstantValue` 属性的编译期常量会在这一刻直接赋真实值。
3. **初始化**：执行编译器合成的 `<clinit>`，也就是静态字段的显式赋值和 static 块，按源码顺序。

`<clinit>` 由 javac 按源码顺序合并生成，父类的 `<clinit>` 整体先于子类执行。JVM 保证 `<clinit>` 的执行是线程安全的，同一类在同一个类加载器里只会执行一次。

### 什么才算「主动引用」

JLS 12.4.1 规定，类在首次出现下列情况时初始化：

- `new` 一个实例；
- 调用该类声明的静态方法；
- 给该类声明的静态字段赋值；
- 读取该类声明的静态字段，且该字段**不是**编译期常量；
- 通过 `Class.forName(name)` 这类会初始化的反射调用（`Class.forName` 默认 `initialize = true`）；
- 初始化一个子类时，会先初始化它的父类；如果父类实现了声明了 default 方法的接口，那些接口也会先初始化（JLS 12.4.2）；
- `MethodHandles.Lookup.ensureInitialized` 等少数反射/句柄入口。

以下都是**被动引用**，不会触发初始化：

```java
A[] arr = new A[10];              // 只创建数组，不初始化 A
Class<?> c = A.class;             // 拿字面量，不初始化
Class<?> d = Class.forName("A", false, loader);  // 显式不初始化
Class<?> e = loader.loadClass("A");              // 只加载
int v = A.CONST;                  // 读编译期常量，被内联，不初始化
int p = Super.VALUE;              // 通过子类访问父类静态字段，只初始化父类
```

### 类初始化顺序

```java
class Parent {
    static int a = 1;

    static {
        System.out.println("Parent 静态块，a=" + a);
    }
}

class Child extends Parent {
    static int b = 2;

    static {
        System.out.println("Child 静态块，b=" + b);
    }
}

new Child();
```

输出是：

```
Parent 静态块，a=1
Child 静态块，b=2
```

要点：父类的静态部分整体先执行；同一个类内部严格按源码顺序，所以静态块里能读到前面已赋值的字段。如果把读取写在声明之前，用简单名会直接编译报错（illegal forward reference）；用 `类名.字段` 这种限定名绕过编译检查的话，读到的是准备阶段的零值。

### 实例初始化顺序

```java
class Parent {
    String tag = "parent-field";

    {
        System.out.println("Parent 实例块");
    }

    Parent() {
        System.out.println("Parent 构造器");
    }
}

class Child extends Parent {
    String tag = "child-field";

    {
        System.out.println("Child 实例块");
    }

    Child() {
        System.out.println("Child 构造器");
    }
}

new Child();
```

输出是：

```
Parent 实例块
Parent 构造器
Child 实例块
Child 构造器
```

编译后每个构造器的结构是固定的：第一步调用 `super(...)`（或 `this(...)`），接着插入本类的字段赋值和实例块（按源码顺序），最后才是构造器体。所以：

- 父类的字段赋值、实例块、构造器整体先于子类的对应部分；
- 实例块在每个构造器里都会执行一次，`new` 几次执行几次，这点和静态块完全不同；
- 如果类里有多个构造器，实例块会被复制进每一个。

### 陷阱：父类构造器里调用可重写方法

```java
class Parent {
    Parent() {
        print();
    }

    void print() {
        System.out.println("Parent.print");
    }
}

class Child extends Parent {
    private int value = 42;

    Child() {
        print();
    }

    @Override
    void print() {
        System.out.println("value = " + value);
    }
}

new Child();
```

实际输出是：

```
value = 0
value = 42
```

原因：`new Child()` 先调用 `Child` 构造器，它第一件事是 `super()` 进入父类构造器；父类构造器里的 `print()` 是虚方法调用，对象实际类型是 `Child`，所以执行 `Child.print`。但此刻父类构造器还没返回，`Child` 的字段赋值 `value = 42` 还没执行，字段停留在准备阶段给的零值 0。等父类构造器返回、子类字段赋值完成，`Child` 构造器体里的 `print()` 才打印出 42。把 `value` 换成引用类型（比如 `String name = "child"`）就会打印 `null`。

这个陷阱的实际危害有三层：读到错误的业务值（日志里出现 null、参数校验以零值通过）；不变式在构造期被绕过；以及 `this` 在构造期间逃逸出去，JLS 17.5 对 final 字段的安全发布保证也随之失效。

### 准备阶段的零值与 ConstantValue

- 准备阶段：静态字段先拿类型零值。这意味着即使代码还没执行到赋值语句，字段也已经是 0 / false / null，而不是「未定义」。
- 初始化阶段：`<clinit>` 才把显式初始值写进去。
- 例外是**常量变量**：`static final` 且类型是基本类型或 `String`、初始化为编译期常量表达式时，javac 会生成 `ConstantValue` 属性，JVM 在准备阶段就把真实值写好；更关键的是，调用方读取它时值会被**内联**进字节码，运行时没有 `getstatic`，所以不会触发目标类的初始化。

```java
class Constants {
    static {
        System.out.println("Constants 初始化");
    }

    static final int MAX = 100;            // 常量变量，有 ConstantValue
    static final String NAME = "jis";      // 常量变量，有 ConstantValue
    static final int RANDOM = compute();   // final 但不是编译期常量
    static int counter = 1;                // 普通静态字段
}

int m = Constants.MAX;      // 不打印，值被内联
int n = Constants.counter;  // 打印「Constants 初始化」
int r = Constants.RANDOM;   // 打印「Constants 初始化」
```

### 静态块只执行一次

`<clinit>` 在同一次类加载中只会跑一遍，多个线程同时首次触发初始化时，只有一个线程执行，其他线程阻塞等待同一个类的初始化完成，不会重复执行。但要注意两点：

- 同一个类被两个不同的类加载器加载，是两个不同的 `Class`，会各自初始化一次；
- 两个类的静态块互相引用，可能造成**类初始化死锁**。

## 追问链

### Q1: 静态块和静态字段赋值的执行顺序是怎样的？

严格按源码顺序。javac 把所有静态字段的显式赋值和所有静态块按出现次序合并成一个 `<clinit>`；父类的整个 `<clinit>` 先执行完，再执行子类的。所以父类静态块里读子类的静态字段是危险的：如果子类还没初始化，读到的是零值；有些写法甚至会触发反向初始化。

#### Q1.1: 如果静态块里读一个写在它后面的静态字段，会怎样？

用简单名读取会编译失败（illegal forward reference），JLS 明确禁止在字段声明之前用简单名引用它；但如果用 `类名.字段` 这种限定名，编译能通过，运行时读到的是准备阶段留下的零值。这正好印证初始化顺序：准备阶段先给所有静态字段置零值，初始化阶段才按源码顺序赋显式值。

##### Q1.1.1: 为什么 `static final` 常量能绕过类初始化？

因为 `static final` 且初始化表达式是编译期常量时，javac 会给字段写一个 `ConstantValue` 属性，JVM 在准备阶段就把真实值放进字段；调用方读取时 javac 直接把这个值内联进字节码，运行时连 `getstatic` 都没有，自然不触发 `<clinit>`。反例是 `static final int RANDOM = compute();`：它同样是 final，但不是编译期常量、没有 `ConstantValue`，读取就会触发初始化。

### Q2: 实例初始化能说得再细一点吗？

顺序是：分配对象、全字段置零值 → 执行父类的实例字段赋值和实例块（按源码顺序）→ 执行父类构造器体 → 执行子类的实例字段赋值和实例块 → 执行子类构造器体。本质原因是编译后每个构造器都被固定成「`super(...)` / `this(...)` → 本类字段赋值与实例块 → 构造器体」，所以实例块晚于父类构造器、早于自己的构造器体，并且在每个构造器里都会重放一次。

#### Q2.1: 那父类构造器里调用被子类重写的方法，为什么会读到还没赋值的字段？

因为方法调用是动态绑定，对象实际类型是子类，执行的是子类重写版本；但此时父类构造器尚未返回，子类的字段赋值代码（在 `super()` 之后）还没跑，字段仍是准备阶段的零值。这等价于把 `this` 提前暴露给子类逻辑，`final` 字段此时也还没完成冻结。

##### Q2.1.1: 这个陷阱有什么实际后果，怎么避免？

后果有三类。第一，业务值错误：父类构造器里注册回调、记录日志、做参数校验时读到 null 或 0，日志里出现 null、校验以零值通过或错误失败。第二，不变式被绕过：子类字段参与的不变式在父类构造期无法成立。第三，`this` 在构造期间逃逸，其他线程可能看到部分初始化的对象，JLS 17.5 的 final 字段安全发布保证失效。避免的办法是构造器里只调用 `private`、`final` 或 `static` 方法，不要把「可被子类重写的方法」当初始化钩子；确实需要模板方法时，拆出一个显式的 `init()`，在对象构造完成后再调用。

### Q3: 类初始化到底什么时候会发生？

只有主动引用才触发，JLS 12.4.1 列举的主要是：`new` 实例、调用静态方法、读写非常量静态字段、`Class.forName` 这类会初始化的反射、以及初始化子类时连带初始化父类（包括声明了 default 方法的父接口）。被动引用都不触发：定义数组、`A.class`、`ClassLoader.loadClass`、通过子类访问父类静态字段、读取编译期常量。

#### Q3.1: 为什么 `Class.forName` 和 `ClassLoader.loadClass` 的行为不一样？

`Class.forName(String)` 默认 `initialize = true`，会走完加载、链接并触发初始化；`Class.forName(name, false, loader)` 只加载不初始化。`ClassLoader.loadClass(name)` 只负责把类加载进来，不负责初始化。老代码用 `Class.forName("com.mysql.cj.jdbc.Driver")` 注册 JDBC 驱动，靠的正是它触发驱动类静态块的副作用。拿到 `Class` 后再用 `getDeclaredConstructor().newInstance()` 创建实例，同样会触发初始化。

##### Q3.1.1: 静态块只执行一次吗？多线程同时触发同一个类的初始化会怎样？

在同一个类加载器里只会执行一次。JVM 用类初始化锁保证：多个线程同时首次触发同一个类的初始化时，只有一个线程执行 `<clinit>`，其他线程阻塞等待，初始化完成后按「已完成」状态继续，不会重复执行。但同一个类被两个类加载器加载时是两个不同的 `Class`，会各自初始化一次，这也是「静态状态不一定全局唯一」的根源。另外，两个类的静态块互相引用时会让两个初始化线程互相等待，形成类初始化死锁。

## 常见坑

- **「父类和子类的静态字段混在一起按源码顺序执行」** —— 不是混在一起：父类的整段 `<clinit>` 先执行完，才轮到子类。
- **「子类构造器先初始化自己的字段，再调用父类构造器」** —— 反了。`super()` 永远在最前，父类字段赋值、实例块、构造器全部先执行完，子类字段才开始赋值。
- **「实例块只执行一次，和静态块一样」** —— 实例块在每个构造器里都会执行一次，`new` 几次执行几次；只有静态块是每个类加载器一次。
- **「字段没执行到赋值就是未定义的」** —— 准备阶段已经把静态字段置为类型零值，对象创建时实例字段也是零值，所以读到的是 null / 0 / false，而不是报错。
- **「在父类构造器里调用被子类重写的方法也能拿到子类字段的值」** —— 拿到的是零值，因为子类字段赋值还没执行；这是日志和校验里最容易踩的坑。
- **「`static final` 一定是编译期常量，读它不触发初始化」** —— 只有基本类型或 `String` 且初始化表达式是编译期常量时才是；`static final List<String> X = List.of()` 不是常量变量，读取会触发初始化。
- **「读常量会触发类初始化」** —— 编译期常量会被内联，运行时没有 `getstatic`，不会触发。
- **「定义数组会初始化元素类」** —— `new A[10]` 不初始化 `A`，只创建数组；`new A[]{ new A() }` 因为 `new` 了元素才初始化。
- **「类初始化只做一次，所以不会有并发问题」** —— 初始化本身是线程安全的，但两个类互相引用对方的静态块可能造成类初始化死锁。

## 加分点

- **final 字段的内存语义（JLS 17.5）**：构造器返回前会有一个冻结动作，只要构造期间 `this` 没有逃逸，另一个线程在构造完成后读到 final 字段一定是最新且正确的值，无需额外同步就能安全发布。所以「构造器里调用可重写方法」不只是读错值，还破坏了这条保证，这是它比一般「顺序问题」更严重的地方。
- **接口的初始化规则与类不同**：初始化一个类时会递归初始化父类，也会初始化声明了 default 方法的父接口（JLS 12.4.2）；普通接口不会因为实现类被初始化就顺带初始化，只有用到它的静态方法或非常量字段时才初始化。这是 default 方法引入后容易忽略的行为。
- **类初始化死锁**：`A.<clinit>` 引用 `B`、`B.<clinit>` 引用 `A`，两个线程各持一把初始化锁互相等待。表现为 `jstack` 里停在 `<clinit>` / `Class.forName`，CPU 反而不高，排查时要看线程栈里的类名对。
- **从字节码看顺序最直观**：子类构造器第一条指令必是 `invokespecial` 调 `super()` 或 `this()`，字段赋值和实例块被编译器搬进构造器，多个构造器各带一份实例块代码。把字节码看一遍，顺序问题就不用死记。
- **`<clinit>` 的线程安全顺带保证静态初始化的可见性**：初始化发生在任何使用之前，且由 JVM 串行化，所以饿汉式单例不需要额外同步就是安全的；但静态字段后续的读写没有同步，双检锁懒加载仍然需要 `volatile`。
- **`ConstantValue` 是 class 文件规范里的字段属性，只对静态字段有效**：它解释了 `static final int` 与 `static int` 赋值时机的区别，也是「常量在编译期被内联」这条规则的字节码层落点。跨模块/跨 jar 发布时，常量内联还可能导致「改了常量但调用方没重编译，读到的还是旧值」——这属于兼容性坑，值得在面试里主动提一句。

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 8 | 引入 default 方法后接口初始化规则细化：初始化一个类时会连带初始化声明了 default 方法的父接口；类初始化与实例初始化的顺序规则与现行 JLS 一致 |
| Java 9 | `Class.newInstance()` 标记 `@Deprecated`，推荐改用 `getDeclaredConstructor(...).newInstance()`；实例化本身仍会触发类初始化 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 执行 `new Child()`（Child 继承 Parent）时，下面哪个顺序是正确的？
    options:
      A: 父类字段初始化 → 子类构造器 → 父类构造器 → 子类字段初始化
      B: 父类字段初始化与实例块 → 父类构造器 → 子类字段初始化与实例块 → 子类构造器
      C: 子类字段初始化 → 父类字段初始化 → 父类构造器 → 子类构造器
      D: 父类构造器 → 父类字段初始化 → 子类构造器 → 子类字段初始化
    answer: B
    analysis: 子类构造器先调用 super()，父类的字段赋值和实例块先执行，然后执行父类构造器体；父类构造器返回后，才执行子类的字段赋值和实例块，最后是子类构造器体。

  - type: JUDGE
    stem: "`static final int MAX = 100;` 这样的编译期常量在准备阶段就被赋值为 100，其他类读取它不会触发所属类的初始化。"
    answer: T
    analysis: 编译期常量会生成 ConstantValue 属性，JVM 在准备阶段就写入真实值；调用方读取时值被内联进字节码，没有 getstatic，因此不会触发 <clinit>。若换成一个非常量表达式（如调用方法计算）则不成立。

  - type: MULTI
    stem: 关于「在父类构造器里调用可被子类重写的方法」，下列说法正确的有？
    options:
      A: 会动态绑定到子类重写后的版本
      B: 此时子类字段还是类型零值
      C: 如果子类字段是引用类型，方法里可能读到 null
      D: 编译器会禁止在构造器里调用任何非 private 方法，所以这个问题不存在
    answer: ABC
    analysis: D 错误。编译器不禁止这种写法，问题在运行时才暴露：父类构造器执行时子类字段赋值尚未进行，动态绑定却已经指向子类实现，于是读到 null 或 0。

  - type: CLOZE
    stem: |
      下面代码先补全输出，再补全原因里的关键词：
      ```java
      class Parent {
          Parent() { print(); }
          void print() { System.out.println("parent"); }
      }

      class Child extends Parent {
          private String name = "child";
          @Override void print() { System.out.println(name); }
      }

      new Child();
      ```
      // 输出 {{1}}，因为 Parent 构造器执行时 Child 的 name 字段还没有{{2}}。
    blanks:
      - ["null"]
      - ["赋值", "初始化", "完成赋值"]
    analysis: new Child() 先进入父类构造器，其中的 print() 动态绑定到 Child.print，但子类字段赋值要等 super() 返回后才执行，此时 name 还是准备阶段的 null。
    difficulty: 3
````
