---
slug: break-parent-delegation
title: 有哪些打破双亲委派的场景？
module: jvm
tags: [类加载, 双亲委派, ClassLoader, SPI, Tomcat]
difficulty: 3
frequency: 2
related:
  - slug: class-loading-parent-delegation
    type: PREREQUISITE
  - slug: reflection-internals
    type: RELATED
---

## 电梯版回答

双亲委派只是 `ClassLoader#loadClass` 的默认实现，不是虚拟机强制规则，所以可以打破。典型场景有四类：Tomcat 的 `WebAppClassLoader` 对 Web 应用自身的类先查 `WEB-INF/classes` 和 `WEB-INF/lib`，查不到才委派父加载器，这样不同 webapp 可以各带一份同名类实现隔离，重新部署时整只丢弃加载器就完成了热部署；SPI 场景下接口在核心库、实现却在应用 classpath，比如 `java.sql.DriverManager` 由 Bootstrap 加载却要加载厂商驱动，只能靠线程上下文类加载器 TCCL 反向委托子加载器；OSGi 把树状委派改成按包导入导出的网状委派，同一个包可以由不同 bundle 提供不同版本；自定义类加载器每次用新加载器重新加载类、丢弃旧加载器，用来做热替换。改写点要分清：只想改变字节流来源、保留委派语义就重写 `findClass`；想改变委派顺序（先自己再父）才重写 `loadClass`。

## 展开讲解

### 打破的入口：loadClass 的三个步骤

双亲委派的全部逻辑都在 `ClassLoader#loadClass(String, boolean)`，就三步：

1. `findLoadedClass(name)` 先看自己是否已经加载过
2. 有父加载器就 `parent.loadClass(name, false)`，没有就交给 Bootstrap
3. 父加载器抛 `ClassNotFoundException` 后，才调用自己的 `findClass(name)`

想打破它，有两个位置可下手：

| 改写点 | 影响 | 适用 |
|---|---|---|
| 重写 `findClass` | 只换「字节流从哪来」，委派顺序仍由父类 `loadClass` 保证 | 从网络、数据库、加密文件加载类；最稳的写法 |
| 重写 `loadClass` | 完全接管委派顺序，可以做成「先自己再父」 | Tomcat、OSGi 这类需要改变顺序的隔离容器 |

关键结论：**重写 `loadClass` 才叫打破双亲委派；重写 `findClass` 不算。**

### Tomcat 的 WebAppClassLoader

Tomcat 为每个 webapp 创建一只独立的 `WebAppClassLoader`（Tomcat 7 起公共逻辑在 `WebappClassLoaderBase`），并**重写 `loadClass`**，把默认的「父优先」改成「本地优先」。默认顺序（`delegate` 属性为 `false`）大致是：

```
① 自己已加载的类（findLoadedClass + resourceEntries 缓存）
② JVM 的核心类（java.* 等，仍然最先由 Bootstrap 处理）
③ delegate=true 时先委派父加载器；默认 false 则跳过
④ 查本地仓库：/WEB-INF/classes、/WEB-INF/lib/*.jar
⑤ 还没找到，才委派父加载器（Common / System 等）
⑥ 都没有 → ClassNotFoundException
```

这套顺序带来两个能力：

- **应用间隔离**：两个 webapp 各自打包了 `spring-core` 的不同版本，因为各自先从自己的 `WEB-INF/lib` 加载，互不干扰。整棵树状结构事实上被切成了多棵子树。
- **热部署**：Tomcat 的 `delegate` 属性（默认 `false`）与「本地优先 + 加载器缓存」配合，重部署时把整只 `WebAppClassLoader` 丢弃、新建一只重新加载，旧类只要没有外部引用就能被回收。注意**单个 `Class` 对象无法原地替换**，热部署靠的是换加载器而不是改类。

Tomcat 里还有一只更细粒度的加载器：Jasper 编译 JSP 时为每个 JSP 生成一只 `JasperLoader`，JSP 改动只丢这一只，影响面比整只 webapp 小得多。

### SPI 与线程上下文类加载器

SPI（Service Provider Interface）的困境是**接口和实现分属两个加载器**：

```
java.sql.DriverManager   → 在核心模块 java.sql 里，由 Bootstrap / Platform 加载
MySQL 驱动 com.mysql.cj.jdbc.Driver → 在应用 classpath，由 Application 加载
```

`DriverManager` 的静态块要加载厂商驱动，但它自己的加载器（Bootstrap）根本看不到应用 classpath。这是典型的**「父加载器要访问子加载器的类」**，正向委派无解。

HotSpot 给的出口是**线程上下文类加载器（Thread Context ClassLoader）**：

```java
Thread.currentThread().getContextClassLoader()
```

`DriverManager` 的 `loadInitialDrivers()` 通过 `ServiceLoader.load(Driver.class)` 加载实现，而 `ServiceLoader.load(Class)` 内部正是用 TCCL 去查 `META-INF/services/java.sql.Driver`。于是「父」拿着「子」的加载器完成了反向委派。

TCCL 的默认值：主线程的 TCCL 就是系统类加载器（Application），新线程默认**继承创建它的线程**的 TCCL，可用 `Thread#setContextClassLoader` 替换。框架（Spring、Dubbo）常靠改 TCCL 来做类隔离或线程池内的类可见性控制。

### OSGi 的网状委派

OSGi 把「树」改成了「网」。每个 bundle（模块）有自己的类加载器，安装时由容器根据 Manifest 里的声明做一次**连线（wiring）**：

- `Export-Package`：本 bundle 对外暴露哪些包
- `Import-Package`：本 bundle 需要哪些包，委派给**导出它的那个 bundle** 的加载器
- `Bundle-ClassPath`：本 bundle 自己的内部 class 路径

于是委派目标不再固定是「父加载器」，而是由包级依赖决定的多个加载器，形成一张有向图。好处是同一进程里可以同时存在同一个包（甚至同一个类名）的多个版本，各自被不同 bundle 使用。

### 自定义类加载器与热替换

热替换的标准套路：

```java
// 每次重新加载都 new 一只加载器，而不是复用旧的
ClassLoader loader = new HotSwapClassLoader(parent);
Class<?> clazz = loader.loadClass("com.example.Handler");
Object handler = clazz.getDeclaredConstructor().newInstance();
// 旧 loader 与旧 Class 在引用断开后成为可回收对象
```

**类的唯一性**由「全限定名 + 定义它的类加载器」共同决定。所以同一个 class 文件被两只加载器加载出来是两个不同的类：`equals` 为 `false`，互相强转抛 `ClassCastException: X cannot be cast to X`，`static` 字段也不共享。用这种方式实现插件隔离、规则引擎热更新都靠这条性质。

### JDK 9 之后的变化

- 扩展类加载器 `ExtClassLoader` 被 **`PlatformClassLoader`** 取代，`<JAVA_HOME>/lib/ext` 目录和 `-Djava.ext.dirs` 被移除，改为 JPMS 模块化。内置加载器分工变成：Bootstrap 加载 `java.base` 等核心模块，Platform 加载平台模块，Application 加载应用模块。
- `ClassLoader` 新增 `getPlatformClassLoader()`；`getSystemClassLoader()` 仍在，但实现类名从 `sun.misc.Launcher$AppClassLoader` 变成 `jdk.internal.loader.ClassLoaders$AppClassLoader`。
- TCCL 的用法完全不变，仍是 SPI 反向委派的唯一通用手段。模块化后 `ServiceLoader` 还支持在 `module-info.java` 里声明 `uses` / `provides`，但 `META-INF/services` 那条路依然由 TCCL 驱动。

### 并行加载能力

`ClassLoader` 默认是非并行（non-parallel capable）的，`loadClass` 会锁住**加载器实例本身**，一个加载器同一时刻只能加载一个类。Java 7 起提供：

```java
protected static boolean registerAsParallelCapable()
```

子类在静态初始化块里调用它即可声明并行能力，此后 `getClassLoadingLock(name)` 会按**类名**返回细粒度锁，多个类可以并行加载。`URLClassLoader`、内置的 App/Platform 加载器都已注册。自己写加载器时如果漏了这一步，高并发下类加载会互相阻塞——这是自定义加载器最容易被忽略的性能坑。

## 追问链

### Q1: 双亲委派为什么可以被打破？它到底是不是强制规则？

不是强制规则。虚拟机规范约束的是**类的唯一性**（全限定名 + 定义类加载器）、访问权限、字节码格式这些，**并不规定委派顺序**。双亲委派只是 `java.lang.ClassLoader#loadClass` 这个方法体的默认实现。

HotSpot 里甚至有一道跟委派无关的硬防线：`defineClass` 的 `preDefineClass` 会拒绝以 `java.` 开头的类名，抛 `SecurityException: Prohibited package name: java.lang`。这说明「核心类库不可顶替」是靠底层校验保证的，而不是靠委派顺序。

所以打破委派是正当用法，只要不违反类唯一性与访问权限，虚拟机不管你怎么加载。

#### Q1.1: 那 Tomcat 具体是怎么改的？

核心在重写 `loadClass`，把默认顺序改成**本地优先**：

```
收到加载请求 →
  ① 自己已加载的类
  ② 核心类仍交给 Bootstrap
  ③ 查 webapp 本地：/WEB-INF/classes、/WEB-INF/lib
  ④ 还找不到，才委派父加载器（Common / System）
  ⑤ 全都没有 → 抛异常
```

这与默认实现的①②③顺序正好相反：默认是「父没找到才找自己」，Tomcat 是「自己没找到才找父」。`delegate` 属性默认为 `false` 即表示本地优先，设为 `true` 会退回父优先。

动机很直接：webapp 希望优先用自己的依赖版本，而不是被容器或别的应用里先加载的同名类抢占。

##### Q1.1.1: 先自己加载，如果父加载器里也有同名类，会不会冲突？

会，而且这正是设计和代价所在——**同名不同版本会共存成两个不同的类**。

因为类的唯一性是「全限定名 + 定义加载器」，同一个 `com.example.Foo` 被 webapp 加载器加载一份、又被共享加载器加载一份，就是两个 `Class`。如果 webapp 里有代码同时触及两份（比如把 webapp 的对象传给容器 API），就可能撞上 `ClassCastException: Foo cannot be cast to Foo`。

Tomcat 因此对某些包名有强制委派规则，避免分裂。真正出问题时排查手段是 `-verbose:class`（JDK 9+ 也可用 `-Xlog:class+load`）打印每个类由哪只加载器加载，找到那个被加载了两遍的类。

#### Q1.2: SPI 为什么也必须打破双亲委派？

因为接口和实现分属父子两个加载器：`java.sql.DriverManager` 在核心模块里（Bootstrap/Platform 加载），厂商驱动在应用 classpath（Application 加载）。父加载器**按定义看不到子加载器的类**，正向委派无路可走。

但「父要调用子」这件事在 SPI 里是刚需，于是引入 TCCL 作为旁路：父加载器不按自己的加载器去查，而是问当前线程「你的上下文加载器是谁」，拿到 Application 加载器去加载实现。

`ServiceLoader.load(Class)` 默认就用 TCCL；需要指定加载器时用 `ServiceLoader.load(Class, ClassLoader)` 重载。

##### Q1.2.1: TCCL 是谁设置的？没设置会怎样？

主线程的 TCCL 由虚拟机启动时设为系统类加载器（Application），普通新线程**默认继承创建它的线程**的 TCCL，是一个 `InheritableThreadLocal` 语义的隐式传递。

如果某个执行环境没有正确的 TCCL（比如某些线程池把线程复用但没传 TCCL，或中间件的自定义线程），`ServiceLoader` 就会用错加载器，表现为 `ClassNotFoundException` / `ServiceConfigurationError` 而 classpath 里明明有这个类。排查时先打印 `Thread.currentThread().getContextClassLoader()` 确认。

### Q2: 重写 findClass 和重写 loadClass 到底有什么区别？

区别在**改的是哪一段职责**：

| | 重写 `findClass` | 重写 `loadClass` |
|---|---|---|
| 改什么 | 只改「字节流从哪读」 | 改整个委派顺序 |
| 双亲委派 | **保留** | 由你自己实现，可能被打破 |
| 典型用途 | 自定义来源的加载器 | 隔离容器（Tomcat/OSGi） |

`findClass` 的默认实现只抛 `ClassNotFoundException`，子类重写它、在里面调用 `defineClass(name, bytes, 0, bytes.length)` 就完成了一次加载，前面「先查已加载、再委派父、最后自己」的编排完全由父类 `loadClass` 负责。

只有当你明确要走「先自己再父」时，才重写 `loadClass`。

#### Q2.1: 重写了 loadClass 但忘了调 findLoadedClass 会怎样？

最直接的后果是**同一个类被重复定义**。`loadClass` 的第一步 `findLoadedClass` 是「本加载器是否已定义过这个类」的唯一检查点，跳过后每次请求都会走到 `defineClass`，第二次开始抛 `LinkageError: attempted duplicate class definition for name: xxx`。

更隐蔽的情况是并发：两个线程同时请求同一个类，都走过检查点后各自 `defineClass`，一个成功一个抛 `LinkageError`。所以重写 `loadClass` 时，正确做法是仍然先调 `findLoadedClass`，再走自己的顺序，最后兜底调用 `super.loadClass`。

##### Q2.1.1: 那怎么保证并发下同一个类只被加载一次？

两条措施缺一不可：

1. **复用 `loadClass` 的类名锁**：调 `getClassLoadingLock(name)` 拿锁，让「查已加载 + 定义」成为临界区。默认实现已经在 `loadClass` 里加了这把锁，自己重写时不要绕过。
2. **声明并行能力**：在子类静态块里调用 `registerAsParallelCapable()`（Java 7 引入）。没注册时锁是加载器实例本身，粒度粗但至少正确；注册后锁按类名细化，不同类可并行加载，避免类加载成为串行瓶颈。

正确性靠第 1 条，性能靠第 2 条。自定义加载器的标准范式就是：静态块注册并行能力 + 保留 `findLoadedClass` + 只重写「从哪读」或「先问谁」。

### Q3: OSGi 的委派和双亲委派有什么本质不同？

树变成了网。双亲委派里每个加载器只有一个固定的委派目标（`parent` 字段），委派路径是一条链；OSGi 里每个 bundle 的委派目标由**包级依赖**在安装时解析出来，可以有多个，甚至指向兄弟 bundle 的加载器。

因此 OSGi 能支持双亲委派做不到的事：同一进程内同一包名的多个版本共存，谁用哪版由 `Import-Package` 决定。代价是类可见性不再是简单的一条链，而是需要显式声明导入导出。

#### Q3.1: 那 OSGi 和 Java 9 的 JPMS 是一回事吗？

目标相似（模块化、强封装、依赖显式化），机制不同：

- OSGi 是**运行时**框架，bundle 可动态安装、启动、停止、卸载，连线在运行时解析，委派是按包映射的网状图。
- JPMS 是**语言与虚拟机层**的模块系统，模块图在启动时基本确定，`requires` 是模块级依赖，加载器分工是「Bootstrap/Platform/Application」三层，委派仍以双亲委派为基底。

所以 JPMS 并没有把类加载改成网状，它主要解决了编译期与启动期的模块边界问题。

## 常见坑

- **「打破双亲委派是违反规范的 hack」** —— 它只是 `loadClass` 的默认实现，虚拟机规范并不规定委派顺序。Tomcat、OSGi、SPI 都是正当用法
- **「Tomcat 对任何类都先自己加载」** —— `java.*` 等核心类仍强制走 Bootstrap，`defineClass` 还有 `Prohibited package name: java.lang` 的硬校验；本地优先只针对 webapp 自己的类和依赖
- **「重写 findClass 就改变了委派顺序」** —— 重写 `findClass` 只换了字节流来源，顺序仍由父类 `loadClass` 决定；要改顺序必须重写 `loadClass`
- **「SPI 用 Class.forName(全限定名) 就能加载到实现」** —— 单参数 `Class.forName` 用的是**调用者的类加载器**，核心库的调用者看不到应用 classpath，必须显式传 TCCL
- **「热部署就是重新读一遍 class 文件替换掉旧的类」** —— 单个 `Class` 对象无法原地替换；热部署靠丢弃整只旧加载器、用新加载器重新加载
- **「同一个类被两只加载器加载后 equals 和强转都正常」** —— 类的唯一性 = 全限定名 + 定义类加载器；同名类会被当成两个类，强转抛 `ClassCastException: X cannot be cast to X`
- **「OSGi 就是 Java 9 的 JPMS」** —— OSGi 是运行时可动态装卸的网状委派，JPMS 是启动期确定的模块图，两者机制不同
- **「自定义加载器只要重写 findClass 就够了，不用管并行能力」** —— 没调 `registerAsParallelCapable()` 时锁粒度是加载器实例，高并发下所有类加载串行化

## 加分点

- 能说清 `ServiceLoader` 的加载链路：`DriverManager.loadInitialDrivers()` → `ServiceLoader.load(Driver.class)` → 内部取 `Thread.currentThread().getContextClassLoader()` → 读 `META-INF/services/java.sql.Driver`。这条链路把「父加载器反向访问子加载器的类」讲得完整可验证
- 知道 Tomcat 的 `JasperLoader`：每个 JSP 一只加载器，改动只丢一只，比整只 webapp 重载影响面小
- 知道 Spring Boot 的 `LaunchedURLClassLoader` 也是自定义加载器：它让 fat jar 里嵌套的 `BOOT-INF/classes`、`BOOT-INF/lib/*.jar` 能被加载，本质上还是重写「字节流从哪来」
- 排查类加载问题用 `-verbose:class` 或 JDK 9+ 的 `-Xlog:class+load`，能直接看到 `[class,load] com.example.Foo source: file:/...` 由哪只加载器加载
- 知道 `ClassLoader#getResources` 会沿委派链把所有父加载器的同名资源都返回（资源不是「唯一」的），只有类才有唯一性；`getResource` 则返回第一个
- 提到自定义加载器并行能力的标准写法：在子类里加静态块 `static { registerAsParallelCapable(); }`，且这是 Java 7 引入的 API

## 版本差异

| 版本 | 差异 |
|---|---|
| JDK 7 | 引入 `ClassLoader.registerAsParallelCapable()`，子类可声明并行加载能力，`getClassLoadingLock` 从「加载器一把锁」变为「每个类名一把锁」 |
| JDK 8 及以前 | 三层加载器为 Bootstrap / Extension / Application；扩展类加载器从 `<JAVA_HOME>/lib/ext` 加载，可用 `-Djava.ext.dirs` 指定 |
| JDK 9 起 | Extension ClassLoader 被 `PlatformClassLoader` 取代，`lib/ext` 与 `-Djava.ext.dirs` 移除；新增 `getPlatformClassLoader()`；应用类加载器实现类变为 `jdk.internal.loader.ClassLoaders$AppClassLoader` |
| JDK 9 起 | 模块化后内置加载器分工为 Bootstrap 加载 `java.base`、Platform 加载平台模块、Application 加载应用模块；TCCL 语义不变，仍是 SPI 反向委派的主要手段 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: Tomcat 的 WebAppClassLoader 默认（delegate=false）下，对 webapp 自身类的加载顺序是？
    options:
      A: 先委派父加载器，父找不到再查 WEB-INF/classes 和 WEB-INF/lib
      B: 先查 WEB-INF/classes 和 WEB-INF/lib，找不到才委派父加载器
      C: 严格遵循双亲委派，不做任何改动
      D: 只从 WEB-INF 加载，从不委派父加载器
    answer: B
    analysis: WebAppClassLoader 重写 loadClass，把默认的父优先改成本地优先，从而实现应用间同名类的隔离。核心类仍交给 Bootstrap；delegate=true 时会退回父优先。D 错误，因为找不到时仍会委派父加载器兜底。

  - type: MULTI
    stem: 以下哪些属于打破双亲委派的场景？
    options:
      A: Tomcat 为每个 webapp 创建加载器，本地优先加载 WEB-INF 下的类
      B: JDBC 驱动通过线程上下文类加载器被 DriverManager 加载
      C: 自定义类加载器重写 findClass，把类的字节流来源改成从网络读取
      D: OSGi 根据 Import-Package / Export-Package 在 bundle 之间做网状委派
    answer: ABD
    analysis: C 不属于打破双亲委派——重写 findClass 只改变字节流来源，委派顺序仍由父类 loadClass 保证。A、B、D 都改变了委派关系或顺序，是典型场景。

  - type: JUDGE
    stem: 重写 ClassLoader 的 findClass 方法会改变类的委派顺序。
    answer: F
    analysis: 重写 findClass 只改变「字节流从哪里来」，双亲委派的委派顺序仍由父类 loadClass 编排。只有重写 loadClass 才会改变委派顺序，Tomcat 和 OSGi 都是这么做的。

  - type: CLOZE
    stem: |
      补全 SPI 场景下「父加载器反向委托子加载器」的关键调用（DriverManager 位于核心库，看不到应用 classpath）：
      ```java
      // ServiceLoader 内部默认取线程上下文类加载器
      ClassLoader cl = Thread.currentThread().{{1}}();
      Class<?> driver = Class.forName(driverName, true, {{2}});
      ```
    blanks:
      - ["getContextClassLoader", "getContextClassLoader()"]
      - ["cl", "TCCL", "线程上下文类加载器"]
    analysis: 接口在核心库、实现在应用 classpath，正向委派无解，只能由父加载器反向借用子加载器的上下文类加载器 TCCL。主线程的 TCCL 默认是系统类加载器，新线程默认继承创建者的 TCCL。
    difficulty: 3
````
