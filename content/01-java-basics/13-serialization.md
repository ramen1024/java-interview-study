---
slug: serialization
title: Java 序列化是怎么实现的？为什么不建议用它？
module: java-basics
tags: [序列化, serialVersionUID, transient, 反序列化安全]
difficulty: 2
frequency: 1
related:
  - slug: reflection-internals
    type: RELATED
  - slug: generic-type-erasure
    type: RELATED
---

## 电梯版回答

Java 序列化是让类实现 `Serializable` 这个空标记接口，然后由 `ObjectOutputStream` 把整个对象图写成一串字节，`ObjectInputStream` 再读回来，真正干活的是 JDK 内建的一套机制。它靠 `serialVersionUID` 做版本校验：不显式声明时，序列化运行时会按类结构算一个默认值，改个字段就可能对不上，抛 `InvalidClassException`。`transient` 字段和所有静态字段都不参与序列化，读回来是类型默认值。可以用 `writeObject`、`readObject` 定制过程，用 `readResolve` 在反序列化后替换对象——这也是修补被破坏的单例的标准做法。不建议用它的首要原因是安全：`readObject` 相当于一个可以执行代码的隐藏构造器，攻击者能拿现成的类拼出 gadget chain 达成远程命令执行；其次它跨语言不通、体积和性能不如 JSON 和 Protobuf、版本兼容脆弱。Java 9 起可以用 `ObjectInputFilter` 做类白名单和资源限制。

## 展开讲解

### Serializable 是个标记接口

```java
public interface Serializable {
}
```

它没有任何方法，唯一作用是「声明这个类参与序列化」。真正的逻辑在 `ObjectOutputStream` 和 JVM 里：只要一个对象实现了它，默认机制就会递归遍历对象图，把每个对象的类和字段写进流。遇到不可序列化的可达对象会抛 `NotSerializableException`。常见误会是「实现了就能序列化一切」，其实 `Thread`、`Socket`、`InputStream`、`ObjectMapper` 这些都没实现 `Serializable`，持有它们的字段必须处理。

`Serializable` 还有几个特殊成员，它们不是接口方法，而是约定名字和签名的钩子：

```java
private void writeObject(ObjectOutputStream out) throws IOException;
private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException;
private void readObjectNoData() throws ObjectStreamException;
ANY-ACCESS-MODIFIER Object writeReplace() throws ObjectStreamException;
ANY-ACCESS-MODIFIER Object readResolve() throws ObjectStreamException;
```

`writeObject` / `readObject` 必须是 `private` 且签名完全一致，否则会被当成普通方法，**静默退回默认机制**（不会编译报错）。`writeReplace` / `readResolve` 可以是任意访问修饰符，常用 `private`。

### serialVersionUID：版本校验靠它

```java
private static final long serialVersionUID = 1L;
```

规则如下：

- 必须是 `static final long`；官方强烈建议所有可序列化类（枚举除外）显式声明。
- 不显式声明时，序列化运行时按类结构计算一个默认值（`ObjectStreamClass.computeDefaultSUID`），参与计算的有：类名、修饰符、排序后的接口列表、非 `private` 非 `static` 非 `transient` 的字段、非 `private` 的方法、静态初始化器。私有字段和方法不参与。
- 反序列化时流里的 `serialVersionUID` 与本地类不一致，就抛：

```
java.io.InvalidClassException: com.example.Foo; local class incompatible:
stream classdesc serialVersionUID = -1234567890123456789,
local class serialVersionUID = 1234567890123456789
```

关键点是：默认值的计算对类结构非常敏感，改字段、加一个 public 方法都可能变，而且不同编译器实现可能算出不同值。显式声明 `serialVersionUID` 的本质是**由你声明哪些版本之间可以互相读**，代价是不兼容的改动不再被自动拦住。

兼容性的一般经验：新增字段兼容（读旧流时新字段拿默认值）；新增方法在显式声明 SUID 后兼容；修改字段类型、调整继承层次、把 `Serializable` 换成 `Externalizable` 不兼容。

### transient 和 static

- `transient` 字段不写入流，反序列化后保持类型默认值（引用 `null`、数值 `0`、`boolean` 为 `false`）。
- `static` 字段属于类不属于对象，一律不参与序列化。
- `transient` 不能和 `final` 配合出预期效果：反序列化不执行构造器，字段停在默认值，而 `final` 在普通代码里又不能再赋值（详见追问链 Q1.1）。
- 更细粒度地控制字段集合可以用 `serialPersistentFields`，实践中很少用。
- 典型用途：字段引用的是不可序列化对象（线程池、连接、`InputStream`），或该字段能从其他字段推导出来、不值得写进流。

### 钩子方法都什么时候被调用

- `writeObject`：写对象时调用，通常先 `out.defaultWriteObject()` 走默认字段写入，再补充自定义数据。
- `readObject`：读对象时调用，此时对象已经由 JVM 分配好但**构造器没有执行**，所以它是个「隐藏构造器」。可以 `in.defaultReadObject()` 后再做校验或修复，也可以在这里拒绝非法状态：

```java
private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
    in.defaultReadObject();
    if (age < 0) {
        throw new InvalidObjectException("age < 0");
    }
}
```

- `readObjectNoData`：流里缺少本类数据时调用（比如旧版本没有这个父类），用于给出合理默认值而不是抛异常。
- `writeReplace`：写之前调用，返回一个替身对象，真正被写入流的是替身。
- `readResolve`：读完之后调用，**返回值替换掉刚反序列化出来的对象**。单例就靠它修复：

```java
public class Singleton implements Serializable {
    public static final Singleton INSTANCE = new Singleton();

    private Singleton() {
    }

    private Object readResolve() {
        return INSTANCE;
    }
}
```

没有 `readResolve` 时，反序列化会绕过私有构造器造出一个新实例，`deserialized == Singleton.INSTANCE` 为 false，单例被破坏。加上之后返回的是规范实例，新造出来的那个对象会被丢弃。注意该对象确实被创建和构造过，只是没被采用。

### Externalizable：完全自己管

```java
public interface Externalizable extends Serializable {
    void writeExternal(ObjectOutput out) throws IOException;
    void readExternal(ObjectInput in) throws IOException, ClassNotFoundException;
}
```

与默认机制的区别：

- 只自动写入类标识，**字段一点都不自动写**，必须手写每个字段，且读写顺序和类型必须一致。
- 反序列化时先调用**public 无参构造器**创建实例，再调 `readExternal`，所以必须提供 public 无参构造器。
- `writeExternal` / `readExternal` 会取代 `writeObject` / `readObject`。
- 换来的好处是输出更紧凑、格式完全可控（可以写版本号、只写必要字段）。代价是易错、兼容性要自己维护。

### 为什么不建议用 JDK 序列化

**一、安全：反序列化是漏洞重灾区。** `ObjectInputStream.readObject` 的语义是「按流里的类名加载类、实例化、调用它的 `readObject`」，而 `readObject` 会执行任意代码。攻击者只要能控制字节流、且目标 classpath 上存在可利用的类，就能拼出 gadget chain。经典链条是 Apache Commons Collections：`AnnotationInvocationHandler.readObject` → `TransformedMap` / `LazyMap` → `ChainedTransformer` → `InvokerTransformer` 反射调用 `Runtime.exec`。链上的类不需要业务真的使用，只要在 classpath 上就够。ysoserial 这类工具把常见链都自动化了，WebLogic、JBoss、Jenkins、Struts2 都出过反序列化 RCE。JDK 自身也清理过一批危险类。

**二、跨语言不通。** 流里写的是 Java 类描述符、字段名和类型，格式与 Java 强绑定，别的语言无法直接消费，RPC/消息场景天然受限。

**三、体积和性能不如现代方案。** 每个类要写类描述符（全限定类名 + 每个字段的名字和类型），同一类只写一次但整体仍很冗长；反射式的读写也慢。JSON 可读、Protobuf/Avro 有 schema 且编码紧凑，体积和速度都明显更好。注意 Kryo、Hessian 这类序列化框架也是二进制方案，但 Hessian 同样有过反序列化问题，且跨语言支持有限。

**四、版本兼容脆弱、把私有实现变成对外契约。** 一旦序列化，字段布局就成了 wire format 的一部分，重构（改字段名、改类型、调整继承）都可能破坏兼容；序列化还绕过构造器，把封装和不变式一并绕过。

### 防护手段

- **Java 9 起（JEP 290）提供 `ObjectInputFilter`**，用于在类被实例化和 `readObject` 执行之前做校验，这是它的关键设计：过滤发生在危险动作之前。配置方式有进程级和实例级：

```text
进程启动参数：-Djdk.serialFilter=com.example.**;!*
```

```java
ObjectInputFilter filter = ObjectInputFilter.Config.createFilter("com.example.**;maxdepth=10");
ObjectInputStream in = new ObjectInputStream(input);
in.setObjectInputFilter(filter);
```

规则用分号分隔，`!` 前缀表示拒绝，`maxdepth` / `maxrefs` / `maxarray` / `maxbytes` 用来限制流图规模、防拒绝服务。RMI、JMX 等也接入了这套过滤器。Java 17 的 JEP 415 进一步支持配置上下文相关的过滤器工厂（`jdk.serialFilterFactory`）。

- **根本不反序列化不可信数据**是最稳的策略。跨进程传数据用显式 schema 的格式，并关闭 JSON 库的多态反序列化（Jackson 的 `enableDefaultTyping` / `activateDefaultTyping` 同样能构造 gadget chain）。
- **序列化代理模式**（Effective Java 第 90 条）能防止伪造流造出不变式被破坏的对象：类只暴露代理作为序列化形式，`readObject` 直接抛异常，代理的 `readResolve` 通过正规构造器重建对象。

## 追问链

### Q1: 如果一个可序列化对象里有字段引用了不可序列化的对象，会发生什么？

序列化时抛 `NotSerializableException`，异常信息里会带上是哪个类不可序列化。因为默认机制要递归遍历整个对象图，任何可达对象都必须能写进流。处理方式有三种：把该字段声明为 `transient`；让该字段的类型也实现 `Serializable`；或者自定义 `writeObject`/`readObject`，只把它转换成可序列化的形式写入（例如把 `InputStream` 的内容读成 `byte[]`，读回来时再重建）。注意 `String`、`ArrayList`、`HashMap`、大多数 JDK 值类型都实现了 `Serializable`，真正的麻烦通常是线程池、连接、流、以及第三方框架对象。

#### Q1.1: 那如果这个字段既是 transient 又是 final，会有什么问题？

反序列化不执行构造器，`transient` 字段保持类型默认值；如果它同时是 `final`，普通代码里根本不能再赋值，`readObject` 里写 `this.field = ...` 也过不了编译，于是这个字段永远是 `null` 或 `0`。三种解法：去掉 `final`；改用非 final 的内部字段加 getter；或者用序列化代理，让 `readResolve` 通过正规构造器把对象重建出来（这样 `final` 字段在构造器里被正确赋值）。

##### Q1.1.1: 为什么强调「通过构造器重建」很重要？

因为 `final` 字段有一条内存语义保证（JLS 17.5）：只要构造期间 `this` 没有逃逸，构造器完成后其他线程读到的 `final` 字段一定是最新且正确的值，不需要额外同步就能安全发布。反序列化绕过了构造器，这条保证就不成立，等于让一个不可变对象带着未初始化状态在堆上流转。序列化代理模式之所以是推荐做法，就是它把对象重新交回构造器，让 `final` 和不变式都恢复有效。

### Q2: 除了性能和体积，不用 JDK 序列化的核心理由是什么？

安全。默认机制无条件信任流里的类名，`readObject` 等价于「让字节流决定执行什么代码」。只要应用 classpath 上存在任何一个 `readObject` 或其调用链中能触发反射执行、JNDI 查找、文件写入的类，攻击者就能把这次反序列化变成任意代码执行。这类漏洞不需要业务代码主动使用那个类，也不需要你的类实现 `Serializable`。

#### Q2.1: 反序列化漏洞具体是怎么被利用的？

`ObjectInputStream` 读到流里的类描述符后，会加载并实例化对应类，再调用它的 `readObject`。攻击者把多个「坏类」串成一个链条：前一个类的 `readObject` 触发后一个类的方法，最后一步落到能执行命令的反射调用上。以 Commons Collections 为例，`AnnotationInvocationHandler.readObject` 会遍历一个 Map 并对每个值调用代理方法，配合 `TransformedMap`/`LazyMap` 触发 `ChainedTransformer`，链尾的 `InvokerTransformer` 通过反射调用 `Runtime.getRuntime().exec(...)`。整条链在反序列化时自动执行，不需要调用方做任何事。

##### Q2.1.1: 那加了 `ObjectInputFilter` 白名单就安全了吗？

是显著改善，但不是银弹，有三个前提。第一，过滤必须发生在反序列化之前（JEP 290 正是这么设计的），但你必须给**所有**入口都装上：直接用 `ObjectInputStream` 的地方、RMI、JMX、以及框架内部自己建流的地方，漏一个就等于没装。第二，白名单要贴合业务，只允许确实需要反序列化的类型；如果为了跑通而把包范围放得过宽，或者被迫放进了可利用的类，链依然可能成立。第三，过滤器还能顺手限制 `maxdepth`、`maxrefs`、`maxarray`、`maxbytes` 来防拒绝服务。最稳妥的策略仍然是：不反序列化不可信数据；换成只认显式 schema 的格式；无法避免时用最小白名单加资源上限。

### Q3: `serialVersionUID` 不显式声明，后果到底是什么？

默认值由序列化运行时按类结构算出来，对改动极其敏感。加一个 public 方法、改一个字段的类型、甚至换编译器，都可能算出不同的值；发布的版本一旦用了某个默认值，下一次改动就让旧数据读不进来，报 `InvalidClassException: local class incompatible`，而报错信息里的两个数字对排查毫无帮助。所以官方建议除枚举外都显式声明，把版本的兼容决策拿到自己手里。

#### Q3.1: 显式声明之后，哪些改动可以兼容？

新增字段可以：旧流里没有这个字段，本地类读的时候保持默认值。新增方法可以：显式声明后 SUID 不再随方法变化。字段改名等价于删旧字段加新字段，旧值会丢失、新字段拿默认值。改字段类型、调整类在继承层次中的位置、把 `Serializable` 改成 `Externalizable`，都不兼容，可能抛 `InvalidClassException` 或读到错位的数据。所以显式声明的真正含义是：你承诺这些版本之间可以互相读；如果确实要主动拒绝旧数据，就把 SUID 改掉。

##### Q3.1.1: 那 `serialVersionUID` 写成多少、能不能随便改？

写多少不重要，`1L` 是社区惯例，重要的是**不要随手改**。它是通信契约的一部分：只要有一个版本的数据落到文件、数据库、消息队列或缓存里，改 SUID 就让这些历史数据读不回来。正确做法是初始写 `1L`，只在明确要拒绝旧数据时递增；不要为了「让它能读」而把本地 SUID 抄成流里的值，那等于关掉版本校验。另外枚举的 `serialVersionUID` 规范规定为 `0L`，数组类不能声明显式值且校验被豁免。

## 常见坑

- **「实现 Serializable 后所有字段都会自动序列化」** —— `static` 字段和 `transient` 字段都不写；字段引用了不可序列化对象时抛 `NotSerializableException`，而不是自动跳过。
- **「serialVersionUID 是编译器生成的」** —— 不显式声明时是序列化运行时（`ObjectStreamClass`）按类结构计算的，对编译器实现也敏感；正因如此才要求显式声明。
- **「transient 字段反序列化后是构造器里赋的值」** —— 反序列化不执行构造器，`transient` 字段是类型默认值。
- **「readResolve 能阻止新对象被创建」** —— 对象已经被创建并调用过构造器，`readResolve` 只是用自己的返回值替换它；那个新对象会被丢弃。
- **「单例实现了 Serializable 也没关系，读回来还是同一个实例」** —— 默认会得到新实例，必须写 `readResolve`，或者干脆用枚举实现单例（规范规定枚举按名字反序列化，天然免疫）。
- **「反序列化会调用类的无参构造器」** —— 可序列化类自己的构造器不执行，对象由 JVM 直接分配。只有「不可序列化的父类」部分需要调用父类的可访问无参构造器来初始化。
- **「Externalizable 会先走默认字段序列化，再补充自定义数据」** —— 它完全不自动写字段，所有字段必须手写，顺序和类型错了就读到错误数据。
- **「JDK 序列化只是慢一点、丑一点，功能上没问题」** —— 它是公认的安全重灾区，而且把 private 字段变成对外 wire format，长期维护成本很高。
- **「用了 JSON 就天然安全」** —— Jackson 的默认类型信息（多态反序列化）同样能构造 gadget chain；安全性来自只反序列化显式 schema 的、不可信数据里不含类型信息。

## 加分点

- **反序列化是「隐藏的构造器」**：对象由 JVM 分配，构造器不执行，所以构造器里的校验、不变式、`final` 字段的正常赋值全都跳过。这是序列化代理模式（Effective Java 第 90 条）想解决的核心问题：让流里只能出现代理，代理的 `readResolve` 必须经过正规构造器。
- **不可序列化父类的细节**：如果父类没有实现 `Serializable`，反序列化子类时会调用父类的**可访问无参构造器**来初始化父类部分；父类没有这样的构造器就会抛 `InvalidClassException: no valid constructor`。所以可序列化子类要求其非序列化父类提供可访问的无参构造器。
- **枚举是序列化的最优解**：规范规定枚举的 `serialVersionUID` 为 `0L`，反序列化按名字返回已有常量，天然防单例破坏，也天然保证「实例集合固定」。
- **`@Serial` 注解**：Java 14 起提供 `java.io.Serial`，标注在 `writeObject`/`readObject`/`readResolve` 等方法上可以让编译器校验签名和可见性，把「签名写错就静默失效」变成显式编译错误，是排查序列化钩子不生效的利器。
- **record 的序列化是另一套**：Java 16 起 record 按记录组件序列化，`writeObject`/`readObject` 会被忽略，反序列化调用规范构造器，因此可以在构造器里做参数校验；规范还规定 record 的 `serialVersionUID` 默认为 `0L` 且校验被豁免。相比之下，record 是更安全的 DTO。
- **过滤器语法和入口**：`jdk.serialFilter` 规则用 `;` 分隔，`!` 表示拒绝，支持 `maxdepth`、`maxrefs`、`maxarray`、`maxbytes`；Java 17 的 JEP 415 让过滤器可以按上下文动态创建。真正落地时最常被漏掉的是框架自己 new 的 `ObjectInputStream`。

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 8 及以前 | 只有序列化机制本身，没有内置的反序列化过滤器，防护只能靠业务层不反序列化不可信数据 |
| Java 9 | JEP 290 引入 `ObjectInputFilter`、`ObjectInputStream.setObjectInputFilter`、`ObjectInputFilter.Config.setSerialFilter`，以及 `jdk.serialFilter` 系统属性与 `java.security` 中的全局配置；RMI、JMX 接入过滤 |
| Java 14 | 新增 `java.io.Serial` 注解，用于编译期校验序列化特殊方法的签名与可见性 |
| Java 16 | record 正式提供：按记录组件序列化，`writeObject`/`readObject` 被忽略，反序列化走规范构造器，`serialVersionUID` 默认 `0L` |
| Java 17 | JEP 415 支持上下文相关的反序列化过滤器工厂（`jdk.serialFilterFactory`），可按调用场景动态选择过滤器 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 没有显式声明 serialVersionUID 时，它的默认值是怎么来的？
    options:
      A: 编译器在编译时按类结构生成并写进 class 文件
      B: 序列化运行时按类结构计算，对类结构变化很敏感
      C: JVM 启动时随机生成
      D: 固定为 0L
    answer: B
    analysis: 默认值由序列化运行时（ObjectStreamClass）按类名、修饰符、接口、非 private 非 static 非 transient 的字段和方法等计算，因此加字段、加 public 方法都可能改变它。枚举的 SUID 才固定为 0L，数组类不能声明显式值但校验被豁免。

  - type: JUDGE
    stem: 反序列化时会调用可序列化类自己的无参构造器来创建对象。
    answer: F
    analysis: 反序列化时对象由 JVM 直接分配，可序列化类自己的构造器不执行，这也是不变式校验会被跳过的原因。只有「不可序列化的父类」部分才需要调用父类的可访问无参构造器。

  - type: MULTI
    stem: 关于 transient 和 static，下列说法正确的有？
    options:
      A: transient 字段不会被写入序列化流
      B: static 字段不会被写入序列化流
      C: 反序列化后 transient 字段是类型默认值
      D: transient 字段如果同时是 final，反序列化时会自动恢复原值
    answer: ABC
    analysis: D 错误。反序列化不执行构造器，transient 字段保持默认值；若还是 final，readObject 里也无法重新赋值，必须去掉 final 或改用序列化代理通过构造器重建。

  - type: CLOZE
    stem: |
      下面用 readResolve 修复被反序列化破坏的单例，补全方法名和返回值：
      ```java
      public class Singleton implements Serializable {
          public static final Singleton INSTANCE = new Singleton();

          private Singleton() {
          }

          private Object {{1}}() {
              return {{2}};
          }
      }
      ```
    blanks:
      - ["readResolve"]
      - ["INSTANCE"]
    analysis: 序列化会绕过私有构造器再造一个实例，破坏单例。readResolve 在对象读完之后被调用，它的返回值会替换掉刚反序列化出来的对象，返回规范实例 INSTANCE 即可恢复单例语义。
    difficulty: 2
````
