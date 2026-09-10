---
slug: bean-lifecycle
title: Spring Bean 的生命周期是怎样的？AOP 代理在哪一步生成？
module: framework-spring
tags: [Spring, Bean生命周期, AOP, BeanPostProcessor]
difficulty: 2
frequency: 3
related:
  - slug: circular-dependency
    type: DEEPEN
  - slug: transaction-failure-scenarios
    type: RELATED
---

## 电梯版回答

Bean 的生命周期可以概括成五步：实例化、属性填充、初始化、使用、销毁。细化一点是：先实例化（推断构造器创建对象），再做依赖注入填充属性，然后回调各种 Aware 接口，接着执行 BeanPostProcessor 的 postProcessBeforeInitialization，再执行初始化方法（@PostConstruct、InitializingBean 的 afterPropertiesSet、init-method 依次），然后执行 postProcessAfterInitialization，这一步里 AOP 会生成代理对象，最后是使用阶段，容器关闭时执行销毁方法。要特别注意 BeanPostProcessor 的两个回调把初始化方法夹在中间，AOP 代理就是在后一个回调里生成的。

## 展开讲解

### 完整流程

```
① 实例化前    InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation
              —— 返回非 null 可短路后续所有流程（AOP 之外的高级用法）
② 实例化      推断构造器 → newInstance（此时对象已创建，但属性都是默认值）
③ 属性填充    InstantiationAwareBeanPostProcessor#postProcessProperties
              —— @Autowired / @Value 在这一步完成注入
④ Aware 回调  BeanNameAware / BeanClassLoaderAware / BeanFactoryAware
              （ApplicationContextAware、EnvironmentAware 等在更早的处理器里）
⑤ 初始化前    BeanPostProcessor#postProcessBeforeInitialization
⑥ 初始化      @PostConstruct
              → InitializingBean#afterPropertiesSet
              → 自定义 init-method
⑦ 初始化后    BeanPostProcessor#postProcessAfterInitialization
              —— ★ AOP 代理在这一步生成
⑧ 使用
⑨ 销毁        @PreDestroy
              → DisposableBean#destroy
              → 自定义 destroy-method
```

④ 到 ⑦ 都发生在同一个方法 `initializeBean` 里，这是最容易被追问的一段：

```java
protected Object initializeBean(String beanName, Object bean, RootBeanDefinition mbd) {
    // ④ Aware 回调
    invokeAwareMethods(beanName, bean);

    Object wrappedBean = bean;
    // ⑤ 初始化前
    if (mbd == null || !mbd.isSynthetic()) {
        wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);
    }
    // ⑥ 初始化方法
    invokeInitMethods(beanName, wrappedBean, mbd);
    // ⑦ 初始化后 —— AOP 在这里把 bean 换成代理
    if (mbd == null || !mbd.isSynthetic()) {
        wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
    }
    return wrappedBean;
}
```

**注意返回值**：⑦ 之后返回的 `wrappedBean` 可能已经不是原来那个对象了。
这也是为什么「注入到别的 Bean 里的其实是代理对象」，而不是原始对象。

### 三种初始化方法的顺序

同一个 Bean 里如果三种都写了，执行顺序是固定的：

```java
@Component
public class OrderService implements InitializingBean {

    @PostConstruct
    public void postConstruct() {
        // ① 最先
    }

    @Override
    public void afterPropertiesSet() {
        // ② 其次
    }

    @Bean(initMethod = "customInit")
    public OrderService orderService() { ... }

    public void customInit() {
        // ③ 最后
    }
}
```

顺序来自 `invokeInitMethods` 的实现：先判断是否 `InitializingBean`
就调 `afterPropertiesSet`，再反射调用指定的 `initMethod`。
而 `@PostConstruct` 是由 `CommonAnnotationBeanPostProcessor` 在
**初始化前**那个阶段（⑤）调用的，所以它排在最早。

这个顺序在面试里问得很多，因为很多人会答成
`afterPropertiesSet` 在 `@PostConstruct` 之前。

### 销毁方法的顺序

```
@PreDestroy → DisposableBean#destroy → destroy-method
```

前提是 Bean 是**单例**。原型（prototype）Bean 的销毁**容器不管**，
调用者自己负责释放——这一点经常被漏掉。

### 两个最容易混淆的扩展点

| | `BeanFactoryPostProcessor` | `BeanPostProcessor` |
|---|---|---|
| 操作对象 | `BeanDefinition`（元数据） | Bean **实例** |
| 执行时机 | 所有 Bean 实例化**之前** | 每个 Bean 初始化前后各一次 |
| 典型实现 | `ConfigurationClassPostProcessor`（处理 `@Configuration`、`@ComponentScan`、`@Bean`）、`PropertySourcesPlaceholderConfigurer` | `AutowiredAnnotationBeanPostProcessor`、`AbstractAutoProxyCreator`（AOP）、`CommonAnnotationBeanPostProcessor` |
| 能否拿到 Bean | **不能** | 能 |

`ConfigurationClassPostProcessor` 是 Spring Boot 自动配置的关键一环：
`@SpringBootApplication` 上的 `@ComponentScan`、`@EnableAutoConfiguration`
最终都靠它解析成 `BeanDefinition`。这也解释了为什么
`BeanFactoryPostProcessor` 里拿不到 Bean——那时候 Bean 还没造出来。

## 追问链

### Q1: BeanPostProcessor 的两个回调分别是什么时机？有什么实际用途？

`postProcessBeforeInitialization` 在初始化方法**之前**执行，
`postProcessAfterInitialization` 在初始化方法**之后**执行。
两者都会拿到 Bean 实例和 Bean 名字，且返回的对象会替换原对象。

实际用途分工很清晰：

- **前置**：适合做「让 Bean 在执行初始化逻辑前具备某些能力」。
  比如 `ApplicationContextAwareProcessor` 就是在这里回调
  `EnvironmentAware`、`ApplicationContextAware` 等接口的 setter。
  `CommonAnnotationBeanPostProcessor` 也在这里触发 `@PostConstruct`。
- **后置**：适合做「包装/代理」。因为此时 Bean 已经完全初始化好了，
  做代理不会破坏初始化逻辑。AOP 的 `AbstractAutoProxyCreator`
  就注册在后置回调。

#### Q1.1: 为什么 @PostConstruct 由 BeanPostProcessor 执行，而不是 Spring 直接调用？

因为 Spring 的核心容器**不依赖注解**。`AnnotationConfigApplicationContext`
才引入注解驱动，而 `ClassPathXmlApplicationContext` 时代只有 XML 配置。

Spring 的做法是把注解处理放在独立的 `BeanPostProcessor` 里
（`CommonAnnotationBeanPostProcessor`），这样：

- 纯 XML 使用场景不引入注解相关的依赖和开销
- 注解处理器可以按需注册或替换

这体现了 Spring 一贯的分层：**容器核心只认 `InitializingBean` 和
`init-method` 这类编程/配置层面的事实**，注解是上层能力。

##### Q1.1.1: 那自定义 BeanPostProcessor 有什么坑？

三个常见的：

1. **它自己不能被自动代理**。`BeanPostProcessor` 会被 Spring 提前实例化
   （在 `registerBeanPostProcessors` 阶段，早于普通 Bean），
   此时 AOP 的基础设施还没就绪。所以在 `BeanPostProcessor` 里
   用 `@Autowired` 注入别的 Bean 可能拿到未初始化完成的实例。
   正确做法是实现 `BeanFactoryAware` 或 `ApplicationContextAware`
   延迟获取。
2. **返回值不能随便丢**。如果把 `postProcessAfterInitialization`
   写成返回 null，或者返回原对象而忘了包装，会静默破坏功能。
3. **顺序敏感**。多个 `BeanPostProcessor` 之间的顺序由
   `Ordered` / `@Order` 或 `PriorityOrdered` 决定。
   如果自定义的排在 AOP 之后，那你拿到的是代理对象；
   排在之前则是原始对象。这个差异经常导致「为什么我的注解没生效」。

### Q2: AOP 代理为什么一定要在 postProcessAfterInitialization 里生成？

两个层面的原因：

**技术层面**：代理对象需要「拦截原对象的方法调用」。如果代理生成得太早
（比如实例化前或属性填充前），那么：

- 属性填充时注入到别的 Bean 里的是原始对象，不是代理，AOP 失效
- 初始化方法（`@PostConstruct` 等）会被代理拦截一次，
  导致切面逻辑在 Bean 还没准备好时就执行，可能 NPE

放在初始化**之后**，Bean 已经是一个完整可用的对象，
这时候把它包一层代理，属性填充早就完成了（注入的是代理还是原对象
取决于注入时机，Spring 通过 `getEarlyBeanReference` 处理循环依赖场景），
初始化逻辑也已经在原对象上跑完。

**语义层面**：`postProcessAfterInitialization` 的语义就是
「Bean 已就绪，允许你替换它」。AOP 正是"替换"，语义完全吻合。

#### Q2.1: 那循环依赖的时候代理是怎么处理的？

这是 `postProcessAfterInitialization` 之外的**例外路径**，也是三级缓存
存在的核心原因。

`AbstractAutoProxyCreator` 实现了 `getEarlyBeanReference` 方法。
当发生循环依赖、需要提前暴露一个「半成品」Bean 时，
`getEarlyBeanReference` 会被调用，在这里**提前**为该 Bean 生成代理，
并把代理存进二级缓存。这样后续注入到其他 Bean 里的是代理对象，
AOP 不会失效。

同时它会把这个 Bean 记进 `earlyProxyReferences`，
等正常的 ⑦ 阶段再走到 `postProcessAfterInitialization` 时，
发现已经处理过就直接返回原对象，避免**生成两次代理**。

所以严格说：**AOP 代理主要在 ⑦ 生成，但在循环依赖场景下会提前到
「提前暴露引用」这一步生成**。这个细节能把「背了生命周期」和
「真懂生命周期」区分开。

## 常见坑

- **漏掉 BeanPostProcessor 的两个回调** —— 只背「实例化、注入、初始化」
  会显得很浅，AOP 代理的时机也答不出来
- **把 `@PostConstruct` 说成在 `afterPropertiesSet` 之后** —— 实际
  `@PostConstruct` 由 `CommonAnnotationBeanPostProcessor` 在
  初始化前阶段调用，排在最前
- **混淆两个 PostProcessor** —— `BeanFactoryPostProcessor` 操作
  `BeanDefinition` 且早于所有 Bean；`BeanPostProcessor` 操作实例
- **说 AOP 代理在实例化时或属性填充时就生成了** —— 属性填充注入的是
  原始对象，会导致 AOP 失效，Spring 不会这么做
- **说原型 Bean 的销毁方法也会被容器调用** —— 原型 Bean 的整个
  生命周期后期（销毁）容器不管理
- **认为 `initializeBean` 返回的还是原对象** —— 返回的可能是代理，
  这是「注入的是代理」的直接原因
- **在自定义 BeanPostProcessor 里直接用 `@Autowired`** —— 它被提前
  实例化，依赖可能还没准备好

## 加分点

- 能说出 `initializeBean` 返回 `wrappedBean` 这个细节，并据此解释
  「为什么 `@Autowired` 注入的是代理对象」
- 知道 `getEarlyBeanReference` 这个例外路径，能讲清循环依赖下
  AOP 如何不失效、以及如何避免重复生成代理
- 提到 `SmartInstantiationAwareBeanPostProcessor` 的
  `determineCandidateConstructors` —— 这就是 Spring 如何决定用哪个
  构造器（`@Autowired` 构造器 / 唯一构造器 / 默认构造器）的扩展点
- 知道 `@Configuration` 的 `proxyBeanMethods` 语义：CGLIB 增强
  `@Configuration` 类是为了让内部 `@Bean` 方法互调时返回同一个单例。
  `proxyBeanMethods = false` 能避免这个代理开销（Spring Boot 2.2+
  的 `@SpringBootConfiguration` 默认就是 false 的用法开始流行）
- 能主动区分「Spring 容器的 Bean 生命周期」和「Spring MVC 的请求生命周期」
  ——面试官有时会故意混着问
- 提到 `ApplicationContext` 关闭时的销毁顺序与依赖关系相反
  （被依赖的先销毁，依赖方后销毁），避免销毁时引用了已销毁的 Bean

## 版本差异

| 版本 | 差异 |
|---|---|
| Spring 4.x | `@PostConstruct` / `@PreDestroy` 来自 JSR-250，需 `CommonAnnotationBeanPostProcessor` |
| Spring 4.3 | 单构造器场景可省略 `@Autowired`；`@Configuration` 默认 `proxyBeanMethods = true` |
| Spring 5.0 | 要求 Java 8；`@Nullable` 支持更完整 |
| Spring 5.2 | `@Configuration(proxyBeanMethods = false)` 成为推荐写法，减少 CGLIB 增强开销 |
| Spring 5.3 | 引入 `@Bean(initMethod)` 与 `SmartInitializingSingleton` 的配合更明确；`@PostConstruct` 等注解在 JDK 9+ 需额外依赖 `jakarta.annotation-api`（Spring 6 起为 `jakarta.annotation`） |
| Spring 6 / Boot 3 | 命名空间由 `javax.*` 迁移到 `jakarta.*`；`@PostConstruct` 来自 `jakarta.annotation.PostConstruct`；最低要求 Java 17 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 同一个 Bean 中若同时存在 @PostConstruct、afterPropertiesSet 和 init-method，执行顺序是？
    options:
      A: "afterPropertiesSet → @PostConstruct → init-method"
      B: "@PostConstruct → afterPropertiesSet → init-method"
      C: "init-method → @PostConstruct → afterPropertiesSet"
      D: "@PostConstruct → init-method → afterPropertiesSet"
    answer: B
    analysis: "@PostConstruct 由 CommonAnnotationBeanPostProcessor 在「初始化前」阶段调用，最早；afterPropertiesSet 与 init-method 都在 invokeInitMethods 中，先判断 InitializingBean 接口再反射调用 initMethod。"

  - type: JUDGE
    stem: AOP 代理对象是在 postProcessBeforeInitialization 阶段生成的。
    answer: F
    analysis: 在 postProcessAfterInitialization 阶段生成。放在初始化之后，Bean 已是完整可用对象，且属性填充早已完成；若过早生成代理会导致注入到其他 Bean 的是原始对象、AOP 失效，并且初始化方法会被切面拦截。

  - type: MULTI
    stem: 关于 BeanFactoryPostProcessor 和 BeanPostProcessor，下列说法正确的有？
    options:
      A: BeanFactoryPostProcessor 操作的是 BeanDefinition，早于所有 Bean 实例化
      B: BeanPostProcessor 操作的是 Bean 实例，每个 Bean 执行两次回调
      C: ConfigurationClassPostProcessor 属于 BeanFactoryPostProcessor
      D: BeanFactoryPostProcessor 中可以安全地用 @Autowired 注入并调用其他 Bean
    answer: ABC
    analysis: D 错误。BeanFactoryPostProcessor 执行时所有普通 Bean 还没被创建，此时拿到的是 BeanDefinition 而非实例。它适合修改 BeanDefinition，例如处理占位符、注册新的定义。

  - type: CHOICE
    stem: 循环依赖场景下，Spring 如何保证注入到其他 Bean 里的是 AOP 代理而不是原始对象？
    options:
      A: 在 postProcessAfterInitialization 里统一处理
      B: 通过 AbstractAutoProxyCreator#getEarlyBeanReference 提前生成代理并放入二级缓存
      C: 等所有 Bean 初始化完成后统一替换引用
      D: 循环依赖场景下不支持 AOP
    answer: B
    analysis: 需要提前暴露「半成品」Bean 时，getEarlyBeanReference 会被调用，在此提前生成代理并存入二级缓存，后续注入拿到的就是代理。同时该 Bean 会记入 earlyProxyReferences，避免在 postProcessAfterInitialization 再次生成代理。
    difficulty: 3
````
