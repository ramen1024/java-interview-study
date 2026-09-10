---
slug: class-loading-parent-delegation
title: 类的加载过程是怎样的？双亲委派有什么用？
module: jvm
tags: [类加载, 双亲委派, ClassLoader, JVM]
difficulty: 2
frequency: 3
related:
  - slug: break-parent-delegation
    type: DEEPEN
  - slug: runtime-memory-layout
    type: PREREQUISITE
  - slug: object-layout
    type: RELATED
---

## 电梯版回答

类的加载分加载、验证、准备、解析、初始化五个阶段。加载是把二进制字节流读进来、在方法区生成类的结构、再在堆上生成 Class 对象；验证检查字节码是否符合规范、不会危害虚拟机；准备给静态变量分配内存并赋**零值**，只有 static final 的编译期常量在准备阶段就赋真值；解析把常量池里的符号引用替换成直接引用，它可以推迟到初始化之后，以支持动态绑定；初始化才真正执行 `<clinit>`，也就是静态变量赋代码里的初值和静态代码块。触发初始化的是主动引用——new、读写静态字段、调用静态方法、反射、初始化子类会连带初始化父类、main 类，以及 MethodHandle；而通过子类名访问父类静态字段、定义数组、引用编译期常量都不会初始化。类加载采用双亲委派：先把请求委派给父加载器，父加载器找不到才自己调 findClass，顺序是 Bootstrap、Platform、Application 三层。双亲委派有两个作用，一是保证核心类库不被自定义同名类顶替（安全），二是同一个类只被加载一次（避免重复加载）。

## 展开讲解

### 五个阶段

类从被加载到虚拟机内存，到卸载出内存，生命周期是：

```
加载 → 验证 → 准备 → 解析 → 初始化 → 使用 → 卸载
         └──────── 连接 (Linking) ────────┘
```

前五个阶段是重点。其中**解析不一定严格发生在初始化之前**，虚拟机规范允许它在初始化之后进行，见下文。

| 阶段 | 做的事 | 关键细节 |
|---|---|---|
| 加载 Loading | 通过类的全限定名获取二进制字节流，把静态存储结构转成方法区的运行时数据结构，在堆中生成 `Class` 对象 | 字节流来源不限：class 文件、网络、动态代理生成、JSP 编译产物；这是唯一可以由用户类加载器控制「从哪读」的阶段 |
| 验证 Verification | 确保字节码安全、合法 | 四个子阶段：文件格式验证、元数据验证、字节码验证、符号引用验证 |
| 准备 Preparation | 为**静态变量**在方法区分配内存并设置**零值** | 不含实例变量；`static final` 编译期常量在此赋真值 |
| 解析 Resolution | 把常量池内的符号引用替换为直接引用 | 可以延迟到初始化之后再执行 |
| 初始化 Initialization | 执行类构造器 `<clinit>()`，即静态变量赋真值 + 静态代码块 | 由虚拟机保证父类 `<clinit>` 先执行；接口不要求父接口先初始化 |

### 准备阶段：为什么赋零值

准备阶段的动作只有「分配内存 + 赋零值」，因为此时还没有执行任何 Java 代码，虚拟机不可能知道代码里写的初值是什么。

各类型的零值：

| 类型 | 零值 |
|---|---|
| `int` / `short` / `byte` / `long` | `0` / `0` / `0` / `0L` |
| `float` / `double` | `0.0f` / `0.0d` |
| `char` | `'\u0000'` |
| `boolean` | `false` |
| 引用类型 | `null` |

```java
public class Demo {
    static int a = 10;              // 准备阶段 a = 0；初始化阶段 a = 10
    static final int B = 20;        // 编译期常量，准备阶段 B 就是 20
    static final Integer C = 20;    // 不是编译期常量，准备阶段 C = null，初始化阶段才 new
}
```

`static final` 的**基本类型或 String 字面量**属于「编译期常量」，javac 会为它生成 `ConstantValue` 属性。虚拟机规范规定：字段有 `ConstantValue` 属性时，准备阶段就按该属性赋值，不需要等 `<clinit>`。

判断标准是**赋值是否是常量表达式**。`static final int B = 20;` 是；`static final int D = new Random().nextInt();` 不是；`static final String S = "abc";` 是（String 字面量）；`static final Integer C = 20;` 不是（自动装箱，编译后是 `Integer.valueOf(20)` 的方法调用）。

### 解析为什么可以晚于初始化

解析即把「符号引用」（字符串形式的描述，如 `java/lang/Object`）换成「直接引用」（指向内存地址的指针、偏移量）。它是**唯一可以延迟执行的连接阶段**。

原因是多态：`invokevirtual` 调用某个方法时，真正执行哪个版本要在运行时根据对象实际类型才能确定（动态绑定）。如果类在初始化时还没被实例化过，就没必要提前把方法引用全部解析死。虚拟机可以选择「用到才解析」（懒解析），这是规范明确允许的优化。

这条规则也是面试高频点：说「解析必须发生在初始化之前」不准确。

### 初始化的时机：主动引用 vs 被动引用

虚拟机规范规定，类只有**首次主动使用**时才初始化。主动引用共六类：

| 触发条件 | 例子 |
|---|---|
| `new` 创建实例、读写静态字段、调用静态方法 | `new Foo()`、`Foo.count`、`Foo.staticMethod()`（对应字节码 `new` / `getstatic` / `putstatic` / `invokestatic`） |
| 反射 | `Class.forName("Foo")`（默认 `initialize=true`） |
| 初始化子类会先初始化父类 | `new Sub()` 会先初始化 `Parent` |
| 虚拟机启动时的主类 | 含 `main` 方法的那个类 |
| JDK 7 起的动态语言支持 | `java.lang.invoke.MethodHandle` 解析结果为 `REF_getStatic` / `REF_putStatic` / `REF_invokeStatic` 时 |
| 接口含 default 方法 | 实现类初始化时，其接口若有 default 方法，接口也会被初始化 |

被动引用**不**触发初始化，以下三种最常考：

```java
// ① 通过子类访问父类静态字段：只初始化父类（字段声明所在的类）
System.out.println(Sub.parentValue);   // 只初始化 Parent，不初始化 Sub

// ② 定义数组：不初始化数组元素类型
Sub[] arr = new Sub[10];               // Sub 不初始化

// ③ 引用编译期常量：常量折叠进调用方，被引用类根本不加载
System.out.println(Sub.CONSTANT);      // Sub 不初始化（常量在编译期已写进本类常量池）
```

补充一个容易漏的：`Foo.class` 这种 class 字面量只加载类、不初始化类，等价于 `Class.forName("Foo", false, ...)`。

### 双亲委派模型

三层类加载器（JDK 8 及以前）：

```
Bootstrap ClassLoader          启动类加载器，C++ 实现，无对应的 Java 对象
  ↑ 父
Extension ClassLoader          扩展类加载器，加载 <JAVA_HOME>/lib/ext
  ↑ 父
Application ClassLoader        应用类加载器，加载应用 classpath
  ↑ 父
自定义 ClassLoader
```

**「父加载器」不是「父类」，是组合关系**。`ClassLoader` 源码里有一个 `parent` 字段，通过构造器传入，不是继承：

```java
private final ClassLoader parent;
```

（`ExtClassLoader` 和 `AppClassLoader` 都继承 `URLClassLoader`，但彼此的父子关系来自 `parent` 字段，不是 extends。）

双亲委派的执行逻辑在 `ClassLoader#loadClass`：

```java
protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
        Class<?> c = findLoadedClass(name);          // ① 先查自己是否已加载
        if (c == null) {
            try {
                if (parent != null) {
                    c = parent.loadClass(name, false); // ② 委派给父加载器
                } else {
                    c = findBootstrapClassOrNull(name); // 顶层，交给 Bootstrap
                }
            } catch (ClassNotFoundException e) {
                // 父加载器找不到，忽略，走下一步
            }
            if (c == null) {
                c = findClass(name);                  // ③ 父加载器都找不到，自己加载
            }
        }
        if (resolve) {
            resolveClass(c);
        }
        return c;
    }
}
```

流程可以概括为：**自底向上委派，自顶向下加载**。任何一个加载器收到请求，先把请求往上抛，只有所有祖先都加载不到，才轮到自己 `findClass`。

注意：**双亲委派不是虚拟机强制规则，只是 `loadClass` 的默认实现**。想打破它，重写 `loadClass` 即可，Tomcat、SPI、OSGi 都这么干。

### 双亲委派的两个作用

1. **安全**：核心类库由 Bootstrap 加载。自己写一个 `java.lang.String` 放进 classpath，请求会一路上溯到 Bootstrap，而 Bootstrap 只从 `java.base` / `rt.jar` 加载，于是加载到的是 JDK 自带的那个。自定义的同名类永远轮不到被加载，防止核心 API 被篡改。
2. **避免重复加载**：同一个类被父加载器加载后，子加载器通过 `findLoadedClass` 直接复用，不会重复加载同一个 class 文件。

## 追问链

### Q1: 类加载分哪几个阶段？加载和初始化的区别是什么？

五个阶段：加载、验证、准备、解析、初始化。前三个加上解析合称「连接（Linking）」。

**加载**面向「字节码」：把二进制字节流读进来，转成方法区的运行时数据结构，并在堆上生成 `Class` 对象。这一步不执行任何 Java 代码。

**初始化**面向「代码」：执行类构造器 `<clinit>()`，也就是所有静态变量的真实赋值和静态代码块。这一步才开始跑字节码。

所以「加载完成」只意味着类被读进来了，静态变量还都是零值。

#### Q1.1: 准备阶段为什么给静态变量赋零值，而不是代码里的初值？

因为准备阶段还没有执行任何 Java 字节码。静态变量的初值可能来自方法调用、表达式、甚至读取配置，只有把 `<clinit>` 跑起来才知道结果。而「零值」是虚拟机不执行任何代码就能确定的默认值。

赋零值还有一个安全意义：**保证静态字段在初始化之前也有一个确定的值**。如果某个静态字段在 `<clinit>` 执行到一半时被其他线程读到，读到的至少是零值而不是未定义的内存残留。

##### Q1.1.1: 那 `static final int B = 20;` 也要等到初始化才变成 20 吗？

不是。它是**编译期常量**，准备阶段就赋 20。

javac 会给这种字段生成 `ConstantValue` 属性，把 20 直接写进 class 文件。虚拟机规范规定，字段带 `ConstantValue` 属性时，准备阶段按该属性赋值，不需要等 `<clinit>`。所以：

```java
static final int B = 20;              // 准备阶段 = 20
static final String S = "abc";        // 准备阶段 = "abc"（String 字面量也是常量）
static final int D = random();        // 准备阶段 = 0，初始化阶段才赋真值
static final Integer C = 20;          // 准备阶段 = null，初始化阶段才 Integer.valueOf(20)
```

`Integer C = 20` 看起来是常量，实际编译成 `Integer.valueOf(20)`，是方法调用，不属于常量表达式。这是最容易答错的边界。

#### Q1.2: 解析为什么可以发生在初始化之后？

为了支持动态绑定。

解析是把常量池里的符号引用换成直接引用。如果解析必须发生在初始化之前，那么所有方法引用在类初始化时就要确定下来。但 `invokevirtual` 调用的方法版本，要等运行时拿到对象的实际类型才能确定（多态）。提前解析既做不对，也没必要。

虚拟机规范因此允许懒解析：用到某个符号引用时才解析。典型现象是，一个类初始化了，但只要没调用到某个方法，那个方法对应的引用可能一直没被解析。

### Q2: 什么会触发类的初始化，什么不会？

会触发的叫**主动引用**：

- `new` 实例、读写静态字段、调用静态方法
- 反射 `Class.forName("X")`
- 初始化子类（会先初始化父类）
- 含 `main` 方法的主类
- `MethodHandle` 解析到静态字段或静态方法
- 实现类初始化时，其接口声明了 default 方法

不会触发的叫**被动引用**，三个高频反例：

- 通过子类名访问父类声明的静态字段，只初始化父类
- `new Sub[10]` 定义数组，不初始化 `Sub`
- 引用 `static final` 编译期常量，常量已折叠进调用方，被引用类不加载

#### Q2.1: 通过子类引用父类的静态字段，会初始化哪个类？

只初始化**父类**，子类不初始化。

关键在编译期：`Sub.parentValue` 中的 `parentValue` 声明在 `Parent` 里，javac 会把它编译成对 `Parent` 的 `getstatic` 指令，字节码里根本不出现 `Sub`。

```java
class Parent { static int value = 1; }
class Sub extends Parent { static { System.out.println("Sub init"); } }

System.out.println(Sub.value);   // 只输出父类的初始化，Sub 的静态块不执行
```

有人会想「子类继承了这个字段，所以访问字段就是使用子类」——错了。字段（和方法）的解析目标是**声明它的类**，不是被用来访问的类名。

##### Q2.1.1: 那如果访问的是父类里的 `static final` 常量呢？

更彻底：**连父类都不会初始化**。

因为常量在编译期已经被折叠进调用方的常量池，编译后那一行可能就是一个 `ldc` 指令或直接的操作数，运行时根本不访问父类的静态字段。

```java
class Parent { static final int CONST = 100; }

int x = Parent.CONST;   // 编译后等价于 int x = 100;，Parent 不加载
```

对比 Q2.1 的普通静态字段：普通字段是 `getstatic`，会触发父类初始化；常量是常量折叠，什么都不会触发。这也是为什么「改常量要重新编译所有引用它的类」——否则调用方里的旧值不会更新。

### Q3: 双亲委派是怎么实现的？

实现点在 `ClassLoader#loadClass(String, boolean)`，逻辑就三步：

1. `findLoadedClass(name)` 先看自己是否已经加载过
2. 有父加载器就调 `parent.loadClass(name, false)`，没有父加载器就交给 Bootstrap
3. 父加载器全部加载不到（抛 `ClassNotFoundException`）时，才调用自己的 `findClass(name)`

所以子类加载器通常只需要重写 `findClass`，把「从哪读字节流」这一段换成自己的实现，委派逻辑就自动继承了。这也是自定义类加载器最稳的写法。

#### Q3.1: 双亲委派解决了什么问题？

两个核心问题：

1. **安全**：同一个全限定名的类，永远优先由最顶层的加载器加载。核心类库（`java.lang.*` 等）只在 Bootstrap 的范围内查找，自定义的同名类无法顶替。
2. **避免重复加载**：父加载器加载过的类，子加载器通过 `findLoadedClass` 直接命中，不会重复加载，也不会出现「同一个类文件产生多个 Class 对象」的情况。

配套的一个概念是**类的唯一性**。虚拟机规定：同一个类由「全限定名 + 定义它的类加载器」共同确定。所以即使 class 文件完全相同，被两个不同加载器加载，也是两个不同的类——`equals` 为 false，互相强转会抛 `ClassCastException`。

##### Q3.1.1: 那如果我自己写一个 `java.lang.String` 会怎样？

加载不到你写的那个。请求会从 Application 一路上溯到 Bootstrap，Bootstrap 从核心类库（JDK 8 的 `rt.jar`，JDK 9+ 的 `java.base` 模块）里找到真正的 `java.lang.String`，直接返回。

所以自定义的 `java.lang.String` 永远轮不到 `findClass`。这正是双亲委派的安全价值。

但要注意一个例外：**JVM 对核心包名有硬校验**。即使你用完全自定义的类加载器绕开双亲委派，试图 define 一个 `java.lang` 开头的类，`defineClass` 也会直接抛 `SecurityException: Prohibited package name: java.lang`。这是比双亲委派更底层的一道防线。

#### Q3.2: 为什么说「父加载器」不是「父类」？

因为类加载器之间是**组合关系**，不是继承关系。

`ClassLoader` 里有一个 `parent` 字段，在构造时传入：

```java
private final ClassLoader parent;
```

`ExtClassLoader` 和 `AppClassLoader` 的继承树其实都指向 `URLClassLoader`，二者之间没有 extends 关系。运行时 `AppClassLoader` 的 `parent` 指向 `ExtClassLoader`，这只是字段引用。

面试时说「父加载器是 AppClassLoader 的父类」会把组合说成继承，是典型的扣分点。

## 常见坑

- **说「准备阶段把静态变量赋成代码里的初值」** —— 准备阶段只赋零值，代码里的初值要到初始化 `<clinit>` 时才写。唯一例外是带 `ConstantValue` 属性的 `static final` 编译期常量
- **说「`static final Integer C = 20;` 是常量，准备阶段就赋值」** —— 自动装箱是 `Integer.valueOf(20)` 方法调用，不是常量表达式，准备阶段仍是 `null`
- **说「解析一定发生在初始化之前」** —— 虚拟机规范允许解析推迟到初始化之后，这是为了支持动态绑定（多态）
- **说「通过子类访问父类静态字段会初始化子类」** —— 只初始化字段声明所在的父类，子类不初始化
- **说「引用了某个类的常量就会加载那个类」** —— 编译期常量会折叠进调用方，被引用类根本不加载
- **说「定义数组 `new Sub[10]` 会初始化 Sub」** —— 只创建数组对象，不初始化元素类型
- **说「`Foo.class` 和 `Class.forName("Foo")` 效果一样」** —— `Foo.class` 只加载不初始化；`Class.forName("Foo")` 默认会初始化。要等价得用 `Class.forName("Foo", false, loader)`
- **说「父加载器是父类」** —— 是组合（`parent` 字段），不是继承
- **说「双亲委派是虚拟机强制规定」** —— 它只是 `ClassLoader#loadClass` 的默认实现，重写 `loadClass` 就能打破，Tomcat 和 SPI 都这么做
- **说「同一个 class 文件在不同加载器里加载出来是同一个类」** —— 类的唯一性由「全限定名 + 定义类加载器」共同决定，不同加载器加载的同名类是不同类

## 加分点

- 能说出 `<clinit>()` 和 `<init>()` 的区别：前者是类构造器，收集静态变量赋值和静态代码块，由虚拟机保证线程安全（多线程并发初始化同一个类时会被加锁阻塞）；后者是实例构造器。另外，**接口的 `<clinit>` 不要求父接口先执行**，只有类才要求父类先执行
- 知道「双亲委派不是强制的」并能举例打破方式：Tomcat 的 `WebAppClassLoader` 重写 `loadClass`，对 Web 应用自身的类**先自己加载**、再委派父加载器，从而实现不同 Web 应用之间同名类的隔离；JDBC 这类 SPI 接口在核心类库、实现却在 classpath，靠 `Thread.currentThread().getContextClassLoader()` 反向委派
- 知道判断两个类是否相同要用 `Class#getClassLoader()` 一起看；线上排查 `ClassCastException: X cannot be cast to X` 这种「同名类强转失败」，根因就是被两个类加载器各加载了一份
- 能说出验证阶段的四个子阶段：文件格式验证（魔数、版本号）、元数据验证（是否有父类、是否实现了抽象方法）、字节码验证（操作数栈类型是否匹配、跳转是否合法）、符号引用验证（引用的类/方法是否存在、访问权限是否足够）
- 调试类加载可以用 `-verbose:class`（或 `-XX:+TraceClassLoading`）打印每个类由哪个加载器加载
- 知道 `Class.forName` 还有三参数重载 `forName(name, initialize, loader)`，Spring 的 `ClassUtils.forName`、JDBC 早期注册驱动都用它控制是否初始化，避免静态块被提前执行

## 版本差异

| 版本 | 差异 |
|---|---|
| JDK 8 及以前 | 三层是 Bootstrap / Extension / Application；扩展类加载器实现为 `sun.misc.Launcher$ExtClassLoader`，加载 `<JAVA_HOME>/lib/ext`，目录可由 `-Djava.ext.dirs` 指定 |
| JDK 9 起 | 扩展类加载器更名为**平台类加载器** `PlatformClassLoader`（实现为 `jdk.internal.loader.ClassLoaders$PlatformClassLoader`）；`lib/ext` 目录和 `-Djava.ext.dirs` 被移除，改为模块化（JPMS），核心模块由 Bootstrap 加载 |
| JDK 9 起 | `ClassLoader` 新增 `getPlatformClassLoader()` 和 `getSystemClassLoader()`，应用类加载器实现类名从 `sun.misc.Launcher$AppClassLoader` 变为 `jdk.internal.loader.ClassLoaders$AppClassLoader` |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 类加载的准备阶段，对于 `static int a = 10;` 这个字段，a 的值是什么？
    options:
      A: 10
      B: 0
      C: null
      D: 未定义
    answer: B
    analysis: 准备阶段只分配内存并赋零值，int 的零值就是 0；赋值 10 发生在初始化阶段执行 <clinit>() 时。只有 static final 的编译期常量才在准备阶段直接赋真值。

  - type: JUDGE
    stem: 通过子类名访问父类中声明的普通静态字段时，子类也会被初始化。
    answer: F
    analysis: 只会初始化字段声明所在的父类。javac 会把它编译成对父类的 getstatic 指令，字节码里不出现子类。若该字段是 static final 编译期常量，则连父类都不初始化。

  - type: MULTI
    stem: 以下哪些操作会触发类的初始化？
    options:
      A: new 一个该类的实例
      B: 引用该类的 static final 编译期常量
      C: 反射调用 Class.forName("该类")
      D: 初始化该类的子类
      E: 定义该类型的数组 new Foo[10]
    answer: ACD
    analysis: B 错误，编译期常量会折叠进调用方，被引用类根本不加载；E 错误，定义数组只创建数组对象，不初始化元素类型。A、C、D 都是主动引用，其中 D 会先初始化父类。

  - type: CLOZE
    stem: |
      补全 ClassLoader#loadClass 的双亲委派逻辑：
      ```java
      protected Class<?> loadClass(String name, boolean resolve) {
          Class<?> c = findLoadedClass(name);
          if (c == null) {
              if (parent != null) {
                  c = {{1}}.loadClass(name, false);   // 先委派父加载器
              }
              if (c == null) {
                  c = {{2}}(name);                    // 父加载器都找不到才自己加载
              }
          }
          return c;
      }
      ```
    blanks:
      - ["parent", "parent 字段", "父加载器"]
      - ["findClass"]
    analysis: 双亲委派是「自底向上委派、自顶向下加载」。收到请求先调 parent.loadClass，父加载器抛 ClassNotFoundException 后才调用自己的 findClass。所以自定义类加载器通常只需重写 findClass 来改变字节流来源。
    difficulty: 3
````
