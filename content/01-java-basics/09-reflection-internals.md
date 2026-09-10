---
slug: reflection-internals
title: 反射的原理是什么？为什么说它慢？
module: java-basics
tags: [反射, Class, 方法句柄, 动态代理]
difficulty: 2
frequency: 2
related:
  - slug: generic-type-erasure
    type: RELATED
  - slug: class-loading-parent-delegation
    type: PREREQUISITE
  - slug: aop-proxy
    type: RELATED
---

## 电梯版回答

反射是运行时通过 `Class` 对象反查类结构、动态创建对象和调用成员的能力。获取 `Class` 对象有三种方式：`X.class`、`obj.getClass()`、`Class.forName(全限定名)`，其中 `Class` 对象由 JVM 在类加载的「加载」阶段创建，同一个类加载器下全局唯一。反射慢不是因为「解释执行」，而是几个可枚举的开销：按名字查找方法、每次调用做访问权限检查、参数要装箱成 `Object[]` 再拆箱、返回值要强转，而且调用目标运行时才确定，JIT 难以内联。`Method.invoke` 背后是 `MethodAccessor`，最初用 native 实现，同一个方法反射调用超过阈值后会「膨胀」，动态生成字节码版本的访问器，速度大幅提升但依然不如直接调用。Spring 大量使用反射是可行的，因为启动期开销被摊薄，且它缓存了 Method/Field 元数据、并在热路径上用 CGLIB/ASM 生成代理类绕过反射。

## 展开讲解

### Class 对象从哪来，怎么拿

`Class` 对象是在类加载过程的**加载阶段**由 JVM 创建的，代表方法区中的类元数据（HotSpot 里对应镜像类）。它不是 `new` 出来的，也没有公开构造器。

```java
Class<?> c1 = String.class;                        // ① 类字面量
Class<?> c2 = "abc".getClass();                    // ② 实例方法，运行时真实类型
Class<?> c3 = Class.forName("java.lang.String");   // ③ 按全限定名加载，默认会初始化
Class<?> c4 = "abc".getClass().getClassLoader().loadClass("java.lang.String"); // 只加载不初始化
```

| 方式 | 特点 |
|---|---|
| `X.class` | 编译期写入常量池，不触发类初始化；多用于已知类型，最省事也最安全 |
| `obj.getClass()` | `Object` 上的 final native 方法，返回运行时实际类型，多态（父类引用指向子类会返回子类 Class） |
| `Class.forName(String)` | 由类加载器按名字加载，默认 `initialize=true`，会执行静态初始化（`<clinit>`） |
| `ClassLoader.loadClass(String)` | 只完成加载/连接，不初始化 |

`Class.forName` 触发初始化这一点很实用，JDBC 驱动的老写法 `Class.forName("com.mysql.cj.jdbc.Driver")` 正是靠这一步执行驱动类的静态代码块，把 Driver 注册进 `DriverManager`。同一个类、同一个类加载器只会有一个 `Class` 对象，所以可以用 `==` 比较；但不同类加载器加载同名类会得到不同的 `Class` 对象，这是类隔离和热部署的基础。

### getMethods 与 getDeclaredMethods

这是反射最常写错的一组 API：

| 方法 | 返回范围 | 含继承 | 含非 public |
|---|---|---|---|
| `getMethods()` | public 方法 | 含父类和接口的 public | 否，只有 public |
| `getDeclaredMethods()` | 本类声明的方法 | 否 | 是，含 private/protected/包私有 |

对应的还有 `getFields()/getDeclaredFields()`、`getConstructors()/getDeclaredConstructors()`（构造器不被继承，两组差别只在于是否只返回 public）。按名字查找的 `getMethod(name, params)` 会沿父类向上找 public 方法，`getDeclaredMethod(name, params)` 只看本类，找不到时抛 `NoSuchMethodException`。此外 `getDeclaredMethods()` 可能返回编译器合成的桥接方法（`isBridge()`）和合成方法，做框架匹配时通常要过滤。

### setAccessible 与模块化限制

反射默认要尊重 Java 的访问控制：`private` 成员直接 `invoke` 会抛 `IllegalAccessException`。`setAccessible(true)` 作用于某个 `AccessibleObject`（`Field`/`Method`/`Constructor`），作用是**关闭这次反射访问的权限检查**，相当于把该成员当成对调用者可见。

Java 9 引入模块系统后，这件事被加了限制：如果目标成员所在的包没有通过 `opens` 对调用方模块开放，`setAccessible(true)` 会失败并抛出 **`InaccessibleObjectException`**（属于 `java.lang.reflect`）。解决办法是启动时加 `--add-opens 模块/包=调用方模块`（例如 `--add-opens java.base/java.lang=ALL-UNNAMED`），或者在 `module-info.java` 里写 `opens 包 to 模块`。JDK 17 上框架常见的 `Unable to make field ... accessible` 就是模块强封装造成的。注意 `setAccessible(true)` 只影响被调用的那个反射对象，不是全局开关。

### 性能开销的来源

把 `Method.invoke` 的一次调用拆开，慢在这几处：

1. **成员查找**：`getMethod`/`getDeclaredMethod` 要按名字和参数类型在类的元数据里查找。如果每次调用前都查一遍，这一步就非常贵——正确做法是缓存 `Method` 对象。
2. **访问权限检查**：默认每次 `invoke` 都要检查调用者是否有权访问该成员；`setAccessible(true)` 可以跳过这个检查（但仍可能有模块层面的检查）。
3. **参数装箱与返回值转换**：`Method.invoke(Object obj, Object... args)` 的形参是 `Object[]`，基本类型要装箱成包装类，返回值要拆箱或强转。这会创建临时对象，带来分配和 GC 压力。
4. **难以被 JIT 优化**：被调用者在运行时才确定，JIT 无法像直接调用那样做内联（inline）和去虚拟化，热点代码的优化收益拿不到。
5. **异常包装**：目标方法抛出的异常会被包进 `InvocationTargetException`，调用方必须 `getCause()` 解包再处理。

### MethodAccessor 与「膨胀」

`Method.invoke` 自己不做调用，它委托给一个 `MethodAccessor` 实例：

- 初始状态下是 `NativeMethodAccessorImpl`，直接调用 native 方法完成调用，每次约比直接调用慢若干倍。
- 每个 `Method` 会统计调用次数（`NativeMethodAccessorImpl` 里的计数器）。当同一个方法的反射调用超过 `sun.reflect.inflationThreshold`（默认 15）后，触发**膨胀（inflation）**：JVM 用 `MethodAccessorGenerator` 动态生成一个 Java 字节码版本的访问器（`GeneratedMethodAccessor$n`），之后的调用走生成的字节码，省掉 native 边界开销，性能明显变好。
- 可以用 `-Dsun.reflect.inflationThreshold=<n>` 调整阈值，或用 `-Dsun.reflect.noInflation=true` 让它一开始就生成字节码版本。

所以「反射一定慢」并不准确：短生命周期、只调几次的反射确实慢；反复调用的方法会被膨胀机制优化到接近直接调用，但仍然要付出 `Object[]` 装箱和返回值转换的成本，而且生成的访问器本身也有生成成本。这些内部实现属于 HotSpot 的优化，规范不保证。

### 为什么 Spring 用大量反射却可以接受

- Spring 的反射集中在**启动期**：扫描 Bean 定义、解析注解、注入依赖、绑定配置。这些是一次性成本，不在请求热路径上，启动慢一点可以接受。
- Spring 会**缓存元数据**：`ReflectionUtils`、`BeanWrapper`、`CachedIntrospectionResults`、注解元数据缓存等，避免每次调用都重新查找。
- 真正高频的调用路径用**生成的代理类**替代反射：CGLIB/ASM 生成目标类的子类或 JDK 动态代理实现接口，调用走生成的字节码，能享受正常的虚方法分派和 JIT 内联；`@Configuration` 的 CGLIB 增强、AOP 代理都是这个思路。
- Spring 6 / Boot 3 的 AOT 处理会在构建期采集反射需求、生成代理与反射提示，进一步减少运行时的反射开销。

## 追问链

### Q1: 三种获取 Class 对象的方式有什么本质区别？

`X.class` 编译期就写进常量池，不触发初始化；`obj.getClass()` 返回运行时真实类型，支持多态；`Class.forName(名)` 由类加载器按名字加载且默认触发初始化。区别集中在两点：**是否需要在编译期知道类型**，以及**是否触发类的静态初始化**。

#### Q1.1: 为什么 Class.forName 会执行静态代码块，而 ClassLoader.loadClass 不会？

因为类加载分「加载、验证、准备、解析、初始化」几个阶段，`Class.forName(String)` 默认 `initialize=true`，会推进到初始化阶段，执行 `<clinit>` 也就是静态变量赋真值和静态代码块；`ClassLoader.loadClass` 只完成加载与连接，不走到初始化。JDBC 驱动的老式注册正是利用了 `Class.forName` 这一步副作用。

##### Q1.1.1: 那 Class 对象到底是在什么时候、由谁创建的？

在类加载的「加载」阶段由 JVM 创建。同一个类在同一个类加载器下只会有一个 `Class` 对象，所以能用 `==` 判断类型是否相同；换一个类加载器去加载同名类，会得到两个不同的 `Class` 对象，彼此 `==` 不成立，`instanceof` 也会失败。类被卸载后，对应的 `Class` 对象才可以被回收。

### Q2: getMethods 和 getDeclaredMethods 的行为差别是什么？

`getMethods()` 返回所有 public 方法，包含从父类和接口继承来的；`getDeclaredMethods()` 只返回本类自己声明的方法，包含 private/protected/包私有，但不含继承来的。字段和构造器有对应的两组方法：`getFields/getDeclaredFields`、`getConstructors/getDeclaredConstructors`。

#### Q2.1: 为什么 getMethod 有时能拿到父类方法，而 getDeclaredMethod 拿不到？

因为 `getMethod(name, params)` 的搜索范围是本类再沿父类和接口向上找 public 方法，而 `getDeclaredMethod` 只在本类的声明里找，找到就返回、找不到直接抛 `NoSuchMethodException`。所以反射查找父类的私有方法时，必须先 `getSuperclass()` 逐级上去再 `getDeclaredMethod`。

##### Q2.1.1: setAccessible(true) 到底做了什么？Java 9 之后有什么坑？

它关闭的是某个 `Field`/`Method`/`Constructor` 对象的访问检查，让 private 成员也能被反射调用。Java 9 引入模块系统后，目标包若没有 `opens` 给调用方模块，`setAccessible(true)` 会抛 `InaccessibleObjectException`。放行方式是启动参数 `--add-opens 模块/包=调用方`，或在 module-info 里声明 `opens`。它只影响当前这个反射对象，不是全局开关，也不能绕过安全管理器。

### Q3: 反射为什么慢？慢在哪几个环节？

慢在五个环节：按名字查找成员、每次调用的访问权限检查、参数装箱进 `Object[]` 与返回值强转、被调方法运行时才确定导致 JIT 难以内联去虚拟化、以及异常被包装成 `InvocationTargetException`。它并不是「解释执行」——反射最终执行的也是编译后的代码，只是多了一层动态分派和装箱的成本。

#### Q3.1: 「反射调用超过 15 次会变快」这个说法对吗？

方向对，但不能当规范。`Method.invoke` 委托给 `MethodAccessor`，初始是 native 实现，同一方法调用次数超过 `sun.reflect.inflationThreshold`（HotSpot 默认 15）后触发膨胀，动态生成字节码版访问器，后续调用更快。这个阈值和机制是 HotSpot 的实现细节，可用系统属性调整，其他 JVM 不一定一样；即便膨胀后也仍有 `Object[]` 装箱和返回值转换的开销，不会完全等于直接调用。

##### Q3.1.1: 既然会膨胀变快，为什么 Spring 还要用 CGLIB 生成子类？

因为膨胀只优化了「一次反射调用」的内部开销，绕不开 `Object[]` 参数打包、返回值转换、以及调用点位于 `Method.invoke` 这一层导致的 JIT 内联受限。生成子类/代理后，调用变成普通的虚方法调用，JIT 能正常内联和去虚拟化，性能更好也更稳定。另外 `@Configuration` 用 CGLIB 增强还有语义目的——拦截 `@Bean` 方法之间的调用，保证单例语义。CGLIB 自己生成代理时也需要反射拿方法元数据，但那是生成期的一次性成本。

## 常见坑

- **「反射慢是因为它是解释执行」** —— 反射最终执行的仍是编译后的字节码/JIT 代码，慢在查找、访问检查、装箱和无法内联，不是解释执行。
- **「Class.forName 只加载类，不执行静态代码块」** —— 默认 `initialize=true` 会执行静态初始化；要只加载用 `ClassLoader.loadClass`。
- **「getDeclaredMethods 包含父类的 private 方法」** —— 它只返回本类声明的方法，不含继承，父类方法要顺着 `getSuperclass()` 自己找。
- **「getMethods 返回所有方法，包括 private」** —— 只返回 public 方法。
- **「setAccessible(true) 在任何 JDK 上都能访问任意私有成员」** —— JDK 9 起模块强封装会抛 `InaccessibleObjectException`，需要 `--add-opens` 或模块 `opens` 声明。
- **「反射调用次数多了就等同于直接调用」** —— 膨胀后接近但仍不如直接调用，`Object[]` 装箱、返回值转换和内联限制始终存在。
- **「同一个类的 Class 对象全局唯一」** —— 前提是同一个类加载器；不同 ClassLoader 加载同名类会得到不同 Class 对象。
- **「invoke 抛出的就是目标方法原来的异常」** —— 目标异常被包进 `InvocationTargetException`，要 `getCause()` 取出，漏了这步会误判异常类型。

## 加分点

- **能说出 MethodAccessor 的膨胀机制**：native `NativeMethodAccessorImpl` 在超过 `sun.reflect.inflationThreshold`（默认 15）后生成 `GeneratedMethodAccessor`，还能说出 `-Dsun.reflect.noInflation=true` 让它在启动阶段就生成。这是区分「背过八股」和「看过源码/做过调优」的细节。
- **对比 MethodHandle**：`MethodHandles.Lookup` 查找到的 `MethodHandle` 是更贴近 JIT 的动态调用方式，可被内联，`invokedynamic`（lambda、字符串拼接、record 的 ObjectMethods）就走这条路，性能通常优于 `Method.invoke`。
- **缓存是反射可用的关键**：框架普遍缓存 `Method`/`Field`/`Constructor` 与注解元数据（`ReflectionUtils`、`CachedIntrospectionResults`），能顺手指出「反射慢但可以靠缓存规避查找开销」。
- **动态代理的两条路线**：JDK 动态代理基于接口、生成实现 `InvocationHandler` 的代理类；CGLIB 基于继承、用 ASM 生成子类。生成期用反射取元数据，调用期走生成代码。
- **反射读取泛型签名**：`getGenericParameterTypes()` 能拿到 `List<String>` 里的 String（依赖 class 文件的 Signature 属性），这是框架做类型推断和反序列化的基础，能顺带引出泛型擦除的边界。

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 8 | `setAccessible` 基本不受限制地访问私有成员；`Method` 膨胀阈值 `sun.reflect.inflationThreshold` 默认 15 |
| Java 9 | 模块系统落地，反射访问未 `opens` 的包会抛 `InaccessibleObjectException`，可用 `--add-opens` / `--add-exports` 放行 |
| Java 16 | JDK 内部 API 默认强封装（JEP 396），大量依赖反射访问 JDK 内部的库集中爆出 `InaccessibleObjectException` |
| Java 17 | 强封装继续收紧（JEP 403），`--illegal-access` 放宽选项不再生效，只能显式 `--add-opens` |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 关于 Class.forName 和 ClassLoader.loadClass 的区别，下列说法正确的是？
    options:
      A: 两者都会执行类的静态代码块
      B: Class.forName 默认会触发类初始化，loadClass 不会
      C: loadClass 只能加载 JDK 自带的类
      D: Class.forName 不会把类加载进 JVM
    answer: B
    analysis: Class.forName 默认 initialize=true，会推进到初始化阶段执行 <clinit>；ClassLoader.loadClass 只完成加载和连接。JDBC 老式驱动注册正是依赖 forName 的这个副作用。

  - type: JUDGE
    stem: getDeclaredMethods() 会返回从父类继承来的 private 方法。
    answer: F
    analysis: getDeclaredMethods 只返回本类声明的方法，包含 private/protected/包私有，但不含任何继承来的方法。要拿父类方法需顺着 getSuperclass() 逐级查找。

  - type: MULTI
    stem: 反射调用比直接调用慢，主要原因包括哪些？
    options:
      A: 需要按名字查找成员，若不缓存则每次都查
      B: 默认每次调用要做访问权限检查
      C: 参数要装箱进 Object[]，返回值要强转或拆箱
      D: 反射的字节码由解释器逐条执行，永远不会被 JIT 编译
    answer: ABC
    analysis: D 是编造的。反射最终执行的也是 JVM 编译后的代码；慢在查找、访问检查、装箱与强转，以及调用目标运行时才确定导致难以内联。

  - type: CLOZE
    stem: |
      补全下面用反射调用私有方法的代码，使其能够绕过访问权限检查：
      ```java
      Method m = obj.getClass().getDeclaredMethod("secretMethod");
      m.setAccessible({{1}});
      Object result = m.invoke(obj);
      ```
    blanks:
      - ["true"]
    analysis: setAccessible(true) 关闭这个 Method 对象的访问权限检查，private 方法才能被 invoke。JDK 9 起若目标包未对调用方 opens，这一步会抛 InaccessibleObjectException，需要 --add-opens。
    difficulty: 2
````
