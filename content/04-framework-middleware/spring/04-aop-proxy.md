---
slug: aop-proxy
title: Spring AOP 的底层原理是什么？为什么自调用会失效？
module: framework-spring
tags: [Spring, AOP, 动态代理, CGLIB, 自调用]
difficulty: 3
frequency: 3
related:
  - slug: transaction-failure-scenarios
    type: DEEPEN
  - slug: transaction-propagation
    type: RELATED
  - slug: bean-lifecycle
    type: PREREQUISITE
---

## 电梯版回答

Spring AOP 走的是运行期代理，不是 AspectJ 那种编译期或加载期织入。它靠一个 BeanPostProcessor——`AnnotationAwareAspectJAutoProxyCreator`——在 Bean 初始化完成后为目标对象生成代理并放回容器，之后所有调用都先经过代理的拦截器链，通知才有机会执行。代理有两种实现：目标类实现了接口时可以用 JDK 动态代理，底层是 `Proxy.newProxyInstance` 加一个 `InvocationHandler`；没有接口时用 CGLIB 在运行期生成子类。Spring Boot 2.x 起 `spring.aop.proxy-target-class` 默认是 true，也就是默认强制走 CGLIB。正因为 CGLIB 基于继承，final 类、final 方法和 private 方法都代理不了。自调用失效的根因也在这里：类内部用 `this` 调用自己的另一个方法，走的是原始对象而不是代理对象，拦截链根本没被触发，所以 `@Transactional` 这类通知会静默失效。

## 展开讲解

### AOP 的五个核心概念

| 概念 | 英文 | 含义 |
|---|---|---|
| 切面 | Aspect | 横切关注点的模块化，比如一个日志切面、事务切面 |
| 连接点 | JoinPoint | 程序执行中可以插入通知的点，Spring AOP 只支持方法执行这一种 |
| 切点 | Pointcut | 匹配连接点的表达式，决定「在哪些方法上织入」 |
| 通知 | Advice | 在连接点执行的动作，分 `@Before`、`@After`、`@AfterReturning`、`@AfterThrowing`、`@Around` |
| 织入 | Weaving | 把切面应用到目标对象、生成代理的过程 |

在 Spring 里，一个 `@Aspect` 类里的 `@Pointcut` 决定匹配范围，`@Around` 等方法就是通知，而「织入」这一步发生在运行期，由代理完成。

### Spring AOP 是运行期代理，不是 AspectJ

两者的差别不只是实现方式，而是**能力边界**：

| 维度 | Spring AOP | AspectJ |
|---|---|---|
| 织入时机 | 运行期，生成代理对象 | 编译期（ajc）、编译后、或加载期（`javaagent`） |
| 连接点 | 仅方法执行，且仅限 Spring Bean | 方法、字段、构造器、静态初始化块等 |
| 依赖 | 无需特殊编译器，纯运行期 | 需要 AspectJ 编译器或 LTW 的 `-javaagent` |
| 代理范围 | 只能增强容器管理的 Bean | 任意类，包括非 Spring 管理的对象 |

Spring 之所以选运行期代理，是因为它不需要改动构建流程——不引入 `ajc`、不用挂 `-javaagent`，靠 IoC 容器在创建 Bean 时顺手包一层就能生效，代价是能力受限。

### 两种代理机制

**JDK 动态代理**：基于接口。运行期生成一个实现了目标类所有接口的类，方法调用全部转发到 `InvocationHandler#invoke`。

```java
Object proxy = Proxy.newProxyInstance(
        target.getClass().getClassLoader(),
        target.getClass().getInterfaces(),
        (p, method, args) -> {
            // 前置通知
            Object result = method.invoke(target, args);
            // 后置通知
            return result;
        }
);
```

Spring 里的对应实现是 `JdkDynamicAopProxy`，它自己就实现了 `InvocationHandler`。注意**代理对象只能被赋给接口类型**，因为它是接口的实现类，和目标类没有继承关系。

**CGLIB**：基于继承。运行期用 `Enhancer` 生成目标类的子类，覆写非 final 方法，方法调用转发到 `MethodInterceptor`。

```java
Enhancer enhancer = new Enhancer();
enhancer.setSuperclass(target.getClass());
enhancer.setCallback((MethodInterceptor) (obj, method, args, methodProxy) -> {
    Object result = methodProxy.invokeSuper(obj, args);
    return result;
});
Object proxy = enhancer.create();
```

Spring 里的对应实现是 `ObjenesisCglibAopProxy`。CGLIB 已经被 repackage 进 `spring-core`（包名 `org.springframework.cglib`），不需要额外加依赖。

因为走的是继承，CGLIB 的限制很明确：

- `final` 类不能被代理，因为无法被继承
- `final` 方法不能被增强，因为无法被覆写
- `private` 方法不能被增强，同样因为无法被覆写
- 跨包继承时不可见的方法（比如父类里的包级私有方法）也无效，等价于 private

### 选择规则

选择发生在 `DefaultAopProxyFactory#createAopProxy`，逻辑可以简化为：

```java
if (config.isOptimize() || config.isProxyTargetClass()
        || hasNoUserSuppliedProxyInterfaces(config)) {
    Class<?> targetClass = config.getTargetClass();
    if (targetClass.isInterface() || Proxy.isProxyClass(targetClass)) {
        return new JdkDynamicAopProxy(config);     // 目标本身就是接口
    }
    return new ObjenesisCglibAopProxy(config);     // 走 CGLIB
}
return new JdkDynamicAopProxy(config);             // 否则按接口走 JDK 代理
```

编排成判断顺序就是：

1. 显式要求 `proxyTargetClass = true`，或 `optimize = true`，或目标类没实现任何接口 → CGLIB
2. 目标类本身是接口（或已是 JDK 代理类）→ 仍然用 JDK 动态代理
3. 其余情况 → JDK 动态代理

Spring Boot 2.x 起 `spring.aop.proxy-target-class` 默认为 `true`，所以实际上**默认总是走 CGLIB**，只有手动设成 `false` 才回到「有接口用 JDK 代理」的行为。

### 代理对象在 Bean 生命周期中的位置

`AnnotationAwareAspectJAutoProxyCreator` 是一个 `BeanPostProcessor`（更准确地说实现了 `SmartInstantiationAwareBeanPostProcessor`），由 `@EnableAspectJAutoProxy` 通过 `AspectJAutoProxyRegistrar` 注册进容器。

它的关键方法是 `postProcessAfterInitialization`：

```java
// AbstractAutoProxyCreator
@Override
public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean != null) {
        Object cacheKey = getCacheKey(bean.getClass(), beanName);
        if (this.earlyProxyReferences.remove(cacheKey) != bean) {
            return wrapIfNecessary(bean, beanName, cacheKey);
        }
    }
    return bean;
}
```

`wrapIfNecessary` 内部会先用 `getAdvicesAndAdvisorsForBean` 找到能匹配上这个 Bean 的通知器，匹配不到就直接返回原对象（所以无切面匹配的 Bean 不会平白多一层代理），匹配到才创建代理。

放回容器的时机就是**初始化完成之后**：

```
实例化 → 属性填充 → Aware 回调 → 初始化（@PostConstruct / afterPropertiesSet）
    → BeanPostProcessor.postProcessAfterInitialization ★ 生成代理
    → 放入一级缓存，注入给其他 Bean 的就是代理
```

这也解释了为什么「把 `this` 传出去」有问题：`this` 在代理生成之前就已经是原始对象了。

### 自调用失效的根因

```java
@Service
public class OrderService {

    public void outer() {
        this.inner();      // ★ this 是原始对象，不是代理
    }

    @Transactional      // 或任何切面通知
    public void inner() {
        // 通知完全不执行
    }
}
```

外部调用 `orderService.outer()` 时：

```
调用方 → 代理对象.outer() → 目标对象.outer() → this.inner()
                                                  ↑ 绕过了代理
```

`outer()` 本身被代理拦到了，但方法体里的 `this.inner()` 是一次普通的 Java 方法调用，直接在原始对象上执行，**代理这一层从未参与**。切面逻辑（开关事务、写日志、鉴权）全部没有执行，而且**不报错、不警告**。

要理解的是：代理不是「修改了目标类的字节码」，而是「换了一个对象给调用方」。目标对象内部对自己的引用始终是原始对象，所以类内互调永远绕过代理。

### 三种解法

**① 注入自身代理（最常用）**

```java
@Service
public class OrderService {

    @Autowired
    @Lazy                       // 不加可能因为自己注入自己而触发循环依赖报错
    private OrderService self;

    public void outer() {
        self.inner();           // 走代理
    }
}
```

**② 拆分到另一个 Bean（最干净）**

```java
@Service
public class OrderService {

    @Autowired
    private OrderInnerService innerService;

    public void outer() {
        innerService.inner();   // 跨 Bean 调用，注入进来的天然是代理
    }
}
```

自调用往往说明职责划分不清。把它拆成独立 Bean 既解决了问题，也改善了设计，通常是首选。

**③ `AopContext.currentProxy()`**

```java
@EnableAspectJAutoProxy(exposeProxy = true)   // 必须显式打开
public class OrderService {

    public void outer() {
        ((OrderService) AopContext.currentProxy()).inner();
    }
}
```

`exposeProxy = true` 的本质是给代理链最前面加一个 `ExposeInvocationInterceptor`，它把当前的 `MethodInvocation` 放进 `ThreadLocal`，`AopContext.currentProxy()` 再从这个 `ThreadLocal` 里取出代理对象。不开启这个开关就直接抛 `IllegalStateException: Cannot find current proxy`。

它的缺点是把「我在被代理」这个假设硬编码进了业务代码，而且跨线程会失效（`ThreadLocal` 不共享），一般不作为首选。

## 追问链

### Q1: Spring AOP 和 AspectJ 到底有什么区别？

它们是**两套独立实现**，不是「Spring AOP 是 AspectJ 的封装」。

Spring AOP 是运行期代理：容器创建 Bean 时生成一个代理对象，只有走代理的方法调用才会触发通知。它的连接点模型只有「方法执行」一种，而且只对 Spring 容器管理的 Bean 生效。

AspectJ 是完整的 AOP 语言与织入器：可以织入字段访问、构造器调用、静态初始化块等，能力远强于 Spring AOP，但需要 `ajc` 编译期织入或 `-javaagent` 加载期织入。织入发生在字节码层面，直接修改目标类的字节码，因此**没有自调用失效问题**——目标对象自己的方法调用也被织入了通知。

Spring 也支持使用 AspectJ 的注解（`@Aspect`、`@Pointcut`）和切点表达式语法，但这只是「借用语法和注解解析」，织入仍然由 Spring 的运行期代理完成。这是最容易混淆的一点：**用了 `@Aspect` 注解不等于用了 AspectJ 的织入**。

#### Q1.1: 那 Spring 为什么不像 AspectJ 那样做编译期织入？

因为织入时机决定了框架的侵入性。

编译期织入要求项目构建流程必须经过 `ajc`（要么换编译器，要么配置额外的插件/agent）。这对一个定位是「轻量、无侵入」的 IoC 容器来说代价太高。运行期代理则完全不需要改动构建：只要把类交给 Spring 管，容器在 `postProcessAfterInitialization` 里顺手包一层就完成了织入，使用者零感知。

代价是能力受限，而 Spring 认为这个取舍是划算的：企业应用里绝大多数横切需求（事务、日志、缓存、权限）都是「方法级别」的，代理方式已经够用。AspectJ 那些字段、构造器级别的连接点，实际使用频率很低。

Spring 也留了后门：真需要 AspectJ 的完整能力时，可以启用 `@EnableLoadTimeWeaving` 走加载期织入。

##### Q1.1.1: 如果我确实要拦截 private 方法怎么办？

Spring AOP 做不到。无论是 JDK 动态代理还是 CGLIB，**都无法拦截 private 方法**：JDK 代理只能实现接口方法，CGLIB 只能覆写可继承的方法，而 private 方法既不在接口里也不能被继承覆写。Spring 官方文档把 `private` 和跨包不可见的方法都列为「不能被通知」。

可选的三条路：

- 改成 public 或 protected 并让调用走代理（最省事，但要小心 protected 跨包继承时仍不可见）
- 用 AspectJ 编译期/加载期织入（真正能拦截任意可见性）
- 重新审视设计：需要拦截 private 方法，往往说明这段逻辑应该被提取成一个独立的 Bean

有一点必须点破：**注解加在 private 方法上不会报错，只是静默不生效**。这和事务失效里「注解加在 protected 方法上」是同一类坑。

### Q2: JDK 动态代理和 CGLIB 的选择规则是什么？

核心判断在 `DefaultAopProxyFactory#createAopProxy`：

- 显式指定 `proxyTargetClass = true`、设了 `optimize = true`、或目标类没有实现任何接口 → 用 CGLIB
- 目标类本身是接口，或已经是 JDK 代理类 → 仍用 JDK 动态代理
- 其余情况（有接口且没强制 CGLIB）→ JDK 动态代理

JDK 动态代理生成的是接口的实现类，所以性能上少一次方法转发、但只能通过接口调用；CGLIB 生成子类，可以代理接口之外的方法，但受继承限制。官方明确说明**两者的性能差异很小，不构成选型依据**。

#### Q2.1: 那为什么 Spring Boot 2.x 要把默认改成 CGLIB？

主要是为了避免**注入类型不一致**这个太容易踩的问题。

原先的规则是「有接口用 JDK 代理，没接口用 CGLIB」。这带来一个反直觉的后果：如果某个类实现了接口，那你注入时**必须用接口类型**，用具体类类型注入会直接启动失败，因为 JDK 代理对象不是目标类的子类。很多开发者被这个 `BeanNotOfRequiredTypeException` 坑过。

统一成 CGLIB 之后，代理对象是目标类的子类，所以**按接口类型或按具体类类型注入都能成功**，行为变得一致。Spring Boot 2.0 把 `spring.aop.proxy-target-class` 的默认值改成 `true`，就是为了消除这个差异。

##### Q2.1.1: 强制用 CGLIB 之后有什么副作用？

有几个必须注意的点：

- **final 类和 final 方法彻底无法代理**。默认 JDK 代理时，如果目标类实现了接口，即使类或某个方法是 final 也不影响代理（JDK 代理只关心接口）；切到 CGLIB 后，final 本身会直接导致 CGLIB 生成子类失败或该 final 方法不被增强。注意：final 方法不会被增强是**静默的**；final 类则会抛 `IllegalArgumentException`（无法继承）。
- **接口上的注解可能不再被读取**。JDK 动态代理会读取接口上的注解，CGLIB 基于子类、读的是类层次。最典型的是 `@Transactional` 加在接口方法上：默认 CGLIB 后可能不生效。所以注解要写在**实现类的方法**上。
- **构造器不会被调用两次**。Spring 4.0 起 CGLIB 代理改用 Objenesis 实例化，绕过目标类构造器，所以「CGLIB 会调两次构造函数」在 Spring 里不成立；只有当 JVM 不允许绕过构造器时才会出现双次调用和对应日志。
- **模块系统限制**：在 module path 上部署时，无法为 `java.lang` 等包里的类生成 CGLIB 代理，除非加 `--add-opens`。

### Q3: 代理对象是在 Bean 生命周期的哪一步生成的？

在**初始化完成之后**，由 `AbstractAutoProxyCreator#postProcessAfterInitialization` 生成。

```java
// AbstractAutoProxyCreator
public Object postProcessAfterInitialization(Object bean, String beanName) {
    Object cacheKey = getCacheKey(bean.getClass(), beanName);
    if (this.earlyProxyReferences.remove(cacheKey) != bean) {
        return wrapIfNecessary(bean, beanName, cacheKey);
    }
    return bean;
}
```

完整链路是：实例化 → 属性填充 → `Aware` 回调 → 初始化方法（`@PostConstruct`、`InitializingBean#afterPropertiesSet`、`init-method`）→ **`postProcessAfterInitialization` 生成代理** → 放入一级缓存。

所以注入到别的 Bean 里的 `orderService` 是代理，而 Bean 内部的 `this` 是原始对象。这个时间差正是自调用失效和「`@PostConstruct` 里把 `this` 传出去会失效」的共同原因。

#### Q3.1: 循环依赖的场景下，代理是什么时候生成的？

会**提前**生成。当 A 和 B 互相依赖时，A 实例化后先把「能生产 A 早期引用的工厂」放进三级缓存，B 填充属性时触发这个工厂，工厂调用的是 `AbstractAutoProxyCreator#getEarlyBeanReference`：

```java
// AbstractAutoProxyCreator
public Object getEarlyBeanReference(Object bean, String beanName) {
    Object cacheKey = getCacheKey(bean.getClass(), beanName);
    this.earlyProxyReferences.put(cacheKey, bean);   // 记一笔，防止重复代理
    return wrapIfNecessary(bean, beanName, cacheKey); // 现在就生成代理
}
```

也就是说，循环依赖场景下代理在 A 的**初始化之前**就生成了，为的是让 B 拿到的是代理而不是原始对象——否则 B 持有的 A 没有切面能力，`@Transactional` 会静默失效。

`earlyProxyReferences` 用来保证代理只生成一次：`postProcessAfterInitialization` 里 `remove(cacheKey) != bean` 成立说明提前代理过，直接返回原 Bean 不再包一层，避免同一个 Bean 出现两个代理实例、破坏单例。

##### Q3.1.1: 为什么不在实例化后就无条件生成代理？

因为那会付出不必要的代价，而且时机也不对。

一是**浪费**：绝大多数 Bean 根本不匹配任何切点，为它们提前生成代理纯属开销，还会让每个 Bean 都多一层对象。二是**时机**：Spring 的设计是「有循环依赖才提前代理，没有就等到初始化后再做」，把「是否要生成代理」这个决策延迟到真正被需要的时候。

三级缓存的存在意义正在于此：三级缓存里放的是「生产早期引用的工厂」，只有发生循环依赖时它才会被调用，没有循环依赖的 Bean 那个工厂永远不执行。这就是为什么 `circular-dependency` 里说「两级缓存不够、必须三级」——两级缓存存不了这个「延迟决策点」。

### Q4: 自调用为什么会导致通知失效？

因为代理不是「改字节码」，而是「换了一个对象交给调用方」。

目标对象内部对自己的引用（`this`）始终指向原始对象，不指向代理。所以：

```
外部调用 → 代理.outer() → 原始对象.outer() → this.inner()   ← 这里没有代理
```

`inner()` 上的 `@Transactional` 也好，`@Cacheable`、`@Async` 也好，都需要代理的拦截链才能生效。`this.inner()` 是普通的 Java 调用，直接在原始对象上执行，**通知一次都不会触发，且不会抛任何异常**。

#### Q4.1: 为什么「注入自身」或 AopContext 就能解决？

因为它们让调用**经过代理对象**，而不是经过 `this`。

注入自身时，容器注入进来的是它为该 Bean 生成的代理（自己依赖自己会让 Spring 走三级缓存拿到早期引用，所以通常加 `@Lazy` 更稳妥），`self.inner()` 就是一次经过代理的调用。

`AopContext.currentProxy()` 则是直接从当前调用上下文里把代理取出来。它的实现依赖 `ExposeInvocationInterceptor`：开启 `exposeProxy = true` 后，这个拦截器会被加在通知链的最前端，把当前的 `MethodInvocation` 存入 `ThreadLocal`，`AopContext.currentProxy()` 再从中取出 `getThis()`（即代理对象）。

##### Q4.1.1: `exposeProxy` 这个开关到底做了什么？

它做两件事：

1. `@EnableAspectJAutoProxy(exposeProxy = true)` 通过 `AspectJAutoProxyRegistrar` 调用 `AopConfigUtils.forceAutoProxyCreatorToExposeProxy`，把自动代理创建者的 `exposeProxy` 属性置为 true。
2. 创建代理时，`AbstractAutoProxyCreator` 会把 `ExposeInvocationInterceptor.ADVISOR` 加到通知链的**第一位**。这个拦截器的核心逻辑是把 `MethodInvocation` 塞进 `ThreadLocal`：

```java
// ExposeInvocationInterceptor
private static final ThreadLocal<MethodInvocation> invocation = new ThreadLocal<>();

public Object invoke(MethodInvocation mi) throws Throwable {
    MethodInvocation oldInvocation = invocation.get();
    invocation.set(mi);
    try {
        return mi.proceed();
    } finally {
        invocation.set(oldInvocation);
    }
}
```

`AopContext.currentProxy()` 只是 `ExposeInvocationInterceptor.currentInvocation().getThis()` 的包装。

两个后果：不开启时调用 `AopContext.currentProxy()` 直接抛 `IllegalStateException`；开启后因为用了 `ThreadLocal`，**跨线程调用会拿不到代理**（异步场景下失效）。这也是它不如「注入自身 / 拆分 Bean」的原因。

## 常见坑

- **说「Spring AOP 底层就是 AspectJ」** —— 两者是独立实现。Spring 只是借用了 AspectJ 的注解和切点表达式语法，织入仍由运行期代理完成
- **说「Spring AOP 默认用 CGLIB，所以能代理任何类」** —— CGLIB 基于继承，`final` 类无法被继承（直接失败），`final` 方法、`private` 方法、跨包不可见方法都无法被增强，而且是静默的
- **说「JDK 动态代理性能差所以默认换成了 CGLIB」** —— 官方明确说两者性能差异很小，不是选型依据。改用 CGLIB 主要是为了让「按接口注入」和「按具体类注入」行为一致
- **说「自调用失效是因为注解写错了或版本不对」** —— 根因是 `this` 指向原始对象，调用根本没经过代理，注解本身没问题
- **说「`@Transactional` 加在 private/protected 方法上会启动报错」** —— 不会报错，是静默失效，这才是它危险的地方
- **说「CGLIB 代理会调用两次目标类的构造器」** —— Spring 4.0 起用 Objenesis 绕过构造器，正常不会双次调用
- **说「`AopContext.currentProxy()` 到处都能用」** —— 必须显式开 `exposeProxy = true`，且跨线程取不到（`ThreadLocal`）
- **把 `@Aspect` 注解当成 AspectJ 织入** —— 只加 `@Aspect` 而没有 `@EnableAspectJAutoProxy`（或用 Spring Boot 的自动配置），切面根本不会被注册
- **认为代理对象和目标对象是同一个东西** —— 代理是另一个对象；按具体类做强制类型转换、`instanceof` 判断、序列化都可能出现意外行为

## 加分点

- 能说出选择逻辑的**真实代码位置** `DefaultAopProxyFactory#createAopProxy`，并指出「目标类本身是接口时依然走 JDK 代理」这个容易被忽略的分支
- 知道 **`AnnotationAwareAspectJAutoProxyCreator` 注册后的 Bean 名是 `org.springframework.aop.config.internalAutoProxyCreator`**，这是排查「代理没生效」时可以在容器里直接查的对象
- 知道 `AbstractAutoProxyCreator#wrapIfNecessary` 会先判断有没有匹配的 Advisor，**没有匹配的 Bean 不会生成代理**，所以「Spring 会给每个 Bean 都加代理」是错的
- 能把自调用失效和循环依赖的 `earlyProxyReferences`、`getEarlyBeanReference` 串起来讲：两者都围绕「代理何时生成、谁拿到了代理」这同一个问题
- 提到 **AspectJ 织入不存在自调用问题**（因为直接改写字节码），可以作为对比反证「代理才是自调用的根因」
- 知道 Spring 也支持 **`@EnableLoadTimeWeaving`** 走 AspectJ 加载期织入来突破代理的限制
- 提到 CGLIB 在 Spring 3.2 起被 repackage 进 `spring-core`，以及 JDK 9+ 模块系统下 CGLIB 需要 `--add-opens` 的坑
- 能把「接口上的 `@Transactional` 在 CGLIB 下可能失效」这条副作用主动讲出来，体现对 `proxyTargetClass` 变更影响面的理解

## 版本差异

| 版本 | 差异 |
|---|---|
| Spring 3.2 | CGLIB 被 repackage 进 `spring-core`（`org.springframework.cglib`），不再需要单独声明依赖 |
| Spring 4.0 | CGLIB 代理改用 Objenesis 创建，绕过目标类构造器，不再出现构造器被调用两次 |
| Spring Boot 1.x | `spring.aop.proxy-target-class` 默认 `false`：目标类有接口时用 JDK 动态代理 |
| Spring Boot 2.0 | 该属性默认改为 `true`，Spring AOP 默认统一使用 CGLIB 代理；接口上的注解（如接口方法上的 `@Transactional`）可能因此不再被读取 |
| Spring 5.x | 代理创建与 `exposeProxy` 机制稳定；`AopContext.currentProxy()` 仍是自调用的一种解法 |
| Spring 6 / Boot 3 | 命名空间迁移到 `jakarta.*`；AOT 场景下代理可在构建期预生成、运行期反射减少，但 CGLIB 基于继承带来的 final/private 限制不变 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: Spring Boot 2.x 起，Spring AOP 默认使用哪种代理方式？
    options:
      A: JDK 动态代理，因为它在 JDK 里内置
      B: CGLIB，因为 spring.aop.proxy-target-class 默认是 true
      C: AspectJ 编译期织入
      D: 取决于目标类是否实现接口，默认不强制
    answer: B
    analysis: Spring Boot 2.0 起 spring.aop.proxy-target-class 默认为 true，因此默认统一使用 CGLIB 代理。这样按接口类型或按具体类类型注入都能成功，消除了原先 JDK 代理下 BeanNotOfRequiredTypeException 的问题。设成 false 才回到「有接口用 JDK 代理」。

  - type: JUDGE
    stem: 一个 Bean 内部用 this 调用同类中带 @Transactional 的另一个方法，该事务会正常生效。
    answer: F
    analysis: this 指向原始对象而不是代理对象，调用没有经过代理的拦截链，@Transactional 不会被执行，且不会报错。要修复可以注入自身代理、用 AopContext.currentProxy()（需 exposeProxy=true），或拆分到另一个 Bean。

  - type: MULTI
    stem: 以下哪些情况会导致 Spring AOP 的通知无法生效？
    options:
      A: 目标类是 final 类
      B: 目标方法是 final 方法
      C: 目标方法是 private 方法
      D: 目标方法是通过 this 调用的同类方法
    answer: ABCD
    analysis: A 导致 CGLIB 无法生成子类；B、C 因为不能被覆写而无法增强（C 也进不了 JDK 代理的接口）；D 是调用绕过了代理。注意 B、C、D 往往都是静默失效，排查时要先确认调用是否经过代理。

  - type: CLOZE
    stem: |
      启用代理暴露后，让自调用也能经过代理：
      ```java
      @EnableAspectJAutoProxy(exposeProxy = {{1}})

      public void outer() {
          ((OrderService) AopContext.{{2}}()).inner();
      }
      ```
    blanks:
      - ["true"]
      - ["currentProxy"]
    analysis: exposeProxy=true 会把 ExposeInvocationInterceptor 加到通知链最前面，它把当前 MethodInvocation 存入 ThreadLocal；AopContext.currentProxy() 再从 ThreadLocal 取出代理对象。不开启该开关会抛 IllegalStateException，且跨线程调用会失效。
    difficulty: 3
````
