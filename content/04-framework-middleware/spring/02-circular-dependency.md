---
slug: circular-dependency
title: Spring 的三级缓存是怎么解决循环依赖的？为什么必须要三级？
module: framework-spring
tags: [Spring, 循环依赖, 三级缓存, AOP]
difficulty: 3
frequency: 3
related:
  - slug: bean-lifecycle
    type: PREREQUISITE
---

## 电梯版回答

三级缓存是三个不同层次的 Map。一级 singletonObjects 存完全初始化好的单例，二级 earlySingletonObjects 存提前暴露的半成品，三级 singletonFactories 存能生产半成品的工厂。解决循环依赖的过程是：A 实例化后先把「能返回 A 的工厂」放进三级缓存，然后填充属性时发现依赖 B，去创建 B；B 填充属性时又依赖 A，这时能从三级缓存拿到那个工厂，调用它得到 A 的早期引用，放进二级缓存并清掉三级；B 初始化完成后，A 再继续填充属性拿到 B，最终完成。必须三级而不能两级，是因为三级缓存里的工厂不只是返回原对象，还要在需要时生成 AOP 代理——这保证了循环依赖场景下注入到 B 里的是代理而不是原始对象。

## 展开讲解

### 三级缓存的定义

```java
// 一级：完全初始化好的单例，对外提供
private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

// 二级：提前暴露的「半成品」，已完成实例化和属性填充，但还没走完初始化
private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);

// 三级：能生产早期引用的工厂（ObjectFactory）
private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);
```

注意三级用的是普通 `HashMap` 而不是 `ConcurrentHashMap`——
因为它的读写都在 `synchronized (this.singletonObjects)` 的临界区内，
靠一级缓存的对象当锁，不需要 Map 自身保证并发安全。

### getSingleton 的三级查找

```java
protected Object getSingleton(String beanName, boolean allowEarlyReference) {
    Object singletonObject = this.singletonObjects.get(beanName);
    if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
        singletonObject = this.earlySingletonObjects.get(beanName);
        if (singletonObject == null && allowEarlyReference) {
            synchronized (this.singletonObjects) {
                // 双重检查
                singletonObject = this.singletonObjects.get(beanName);
                if (singletonObject == null) {
                    singletonObject = this.earlySingletonObjects.get(beanName);
                    if (singletonObject == null) {
                        ObjectFactory<?> factory = this.singletonFactories.get(beanName);
                        if (factory != null) {
                            // 调用工厂拿到早期引用，升级到二级缓存
                            singletonObject = factory.getObject();
                            this.earlySingletonObjects.put(beanName, singletonObject);
                            this.singletonFactories.remove(beanName);
                        }
                    }
                }
            }
        }
    }
    return singletonObject;
}
```

关键点：**拿到工厂后就地调用它，把结果放进二级缓存并移除三级**。
所以一个 Bean 的早期引用只会被创建一次。

### 完整时序（A ↔ B 互相依赖）

```
① 创建 A
   ├─ A 实例化完成（属性还是 null）
   ├─ addSingletonFactory("a", () -> getEarlyBeanReference("a", A))
   │    → 三级缓存：{a: 工厂}
   └─ 开始 populateBean(A)，发现依赖 B

② 创建 B（递归进入 getBean("b")）
   ├─ B 实例化完成
   ├─ addSingletonFactory("b", ...) → 三级缓存：{a: 工厂, b: 工厂}
   └─ 开始 populateBean(B)，发现依赖 A

③ 解析 A 的引用 → getSingleton("a", true)
   ├─ 一级没有（A 还没初始化完）
   ├─ 二级没有
   └─ 三级有工厂 → 调用 factory.getObject()
        → 得到 A 的早期引用（可能是代理）
        → 放入二级缓存：{a: A的早期引用}
        → 移除三级里的 a
   → B 拿到了 A 的早期引用，B 属性填充完成

④ B 继续 initializeBean（Aware、初始化方法、AOP 代理）
   → B 完全就绪，放入一级缓存

⑤ 回到 A，A 拿到了 B，属性填充完成
   → A 继续 initializeBean
   → A 完全就绪，放入一级缓存，清掉二级缓存里的 A
```

注意第 ③ 步：**B 拿到的是 A 的早期引用，此时 A 还没执行初始化方法
（`@PostConstruct` 等）**。这是一个重要的事实——
如果 B 在自己的初始化逻辑里用到了 A 的初始化结果，会拿到未初始化的值。
这类问题表现为「偶发的 NPE 或空值」，很难排查。

### 为什么必须三级，两级不行吗

这是最核心的追问。答案在于**二级缓存存不下「可能改变的对象类型」**。

关键方法是 `getEarlyBeanReference`：

```java
protected Object getEarlyBeanReference(String beanName, RootBeanDefinition mbd, Object bean) {
    Object exposedObject = bean;
    if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
        for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
            // ★ AbstractAutoProxyCreator 在这里生成 AOP 代理
            exposedObject = bp.getEarlyBeanReference(exposedObject, beanName);
        }
    }
    return exposedObject;
}
```

`AbstractAutoProxyCreator#getEarlyBeanReference` 的实现要点：

```java
public Object getEarlyBeanReference(Object bean, String beanName) {
    Object cacheKey = getCacheKey(bean.getClass(), beanName);
    // 记下这个 Bean 已经提前生成过代理
    this.earlyProxyReferences.put(cacheKey, bean);
    // 需要代理就现在生成
    return wrapIfNecessary(bean, beanName, cacheKey);
}
```

所以三级缓存的工厂**不是简单地返回原对象**，而是一个决策点：

- 不需要 AOP 代理 → 返回原始对象
- 需要 AOP 代理 → **现在就生成代理并返回代理**

**如果用两级缓存会出什么问题**：
如果只把「原始对象」放进二级缓存，那么 B 注入的就是原始对象。
等 A 走完 `postProcessAfterInitialization` 生成代理后，
**一级缓存里是代理，而 B 里持有的还是原始对象**——
AOP 失效，事务、日志、权限切面全都不生效。

要修这个，就得在实例化后立刻生成代理。但那时 A 的属性还没填充，
代理内部持有一个「半成品」，且**有些 Bean 可能根本不需要代理**，
提前生成是浪费。更糟的是，如果 A 最终不需要代理，
提前生成的那个代理就成了多余对象。

三级缓存用「延迟到真正被依赖时才决定」解决了这个矛盾：
**只有发生循环依赖时，工厂才会被调用**。
没有循环依赖的 Bean，工厂永远不会执行，也就不会提前生成代理。

### 为什么构造器注入的循环依赖解决不了

因为三级缓存的前提是「**先实例化，再暴露**」：

```java
// AbstractAutowireCapableBeanFactory#doCreateBean
if (instanceWrapper == null) {
    instanceWrapper = createBeanInstance(beanName, mbd, args);   // ① 实例化
}
// ...
addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean));  // ② 暴露
```

构造器注入时，**要调用构造器就必须先解析参数**，
而参数就是它依赖的另一个 Bean。于是：

```
创建 A → 调 A 的构造器 → 需要 B → 创建 B → 调 B 的构造器 → 需要 A
                     ↑                                              │
                     └──────────── A 还没有实例，无法暴露 ──────────┘
```

第 ① 步永远走不到，二级三级缓存都没机会被写入，
只能抛 `BeanCurrentlyInCreationException`。

**例外情况**：如果 A 的构造器注入的是 B，而 B 用 setter/字段注入 A，
这不构成构造器循环，是能解决的。因为 A 的构造器只需要 B，
而创建 B 时它不需要在构造器里拿到 A。

**这也是 Spring 官方推荐构造器注入的原因之一**——
它在编译期就把循环依赖暴露出来，逼你重构设计，
而不是让它在运行期被缓存机制悄悄兜住。

### Spring Boot 2.6 起的默认行为变化

```yaml
spring:
  main:
    allow-circular-references: true   # 默认 false
```

Spring Boot 2.6 起**默认禁止循环依赖**，遇到会直接启动失败并给出
清晰的错误提示。这是一个态度明确的变更：

- 循环依赖几乎总是**设计问题**，应该通过提取第三个类、
  改用事件、或引入接口来打破
- 靠三级缓存兜住会让问题被掩盖，直到某天 AOP 失效、
  或出现「注入了半成品」的诡异 bug

要临时兼容可以打开这个开关，但更好的做法是重构。

## 追问链

### Q1: 为什么三级缓存不能合并成二级？

因为**三级缓存存的是「能生产对象的工厂」，二级存的是「对象本身」**，
两者语义不同，不能合并。

合并成二级的唯一办法是「提高一级的语义」——让二级直接存工厂。
但那等于把三级提到二级，还是两级缓存，只是换了名字，
数量的需求没有变。

真正的答案是：**两级缓存在功能上不够，因为需要「延迟决策」**。

```
一级：最终对象（可能是代理）
二级：早期引用（可能是代理）      ← 需要在这里存"可能被代理的对象"
三级：生产早期引用的工厂          ← 延迟到被依赖时才决定要不要代理
```

如果只有两级，就必须在实例化后立即决定是否代理，
而那时无法判断（代理需要看最终是否匹配切点，
有些切点信息依赖 Bean 的注解，实例化后虽然已经能读到，
但 Spring 的设计是「没有循环依赖就不提前代理」以保持性能）。

#### Q1.1: 那三级缓存里的工厂只会被调用一次吗？

是的，只会一次。因为 `getSingleton` 在调用工厂后
**立刻把结果放入二级缓存并移除三级缓存的条目**：

```java
singletonObject = factory.getObject();
this.earlySingletonObjects.put(beanName, singletonObject);
this.singletonFactories.remove(beanName);
```

所以即使有 C、D 两个 Bean 都依赖 A，第二个来的时候
直接从二级缓存拿到同一个早期引用，不会再调工厂。

这一点很重要：**它保证了「同一个 Bean 的早期引用是同一个对象」**。
如果每次都重新生成代理，C 和 D 会拿到两个不同的代理实例，
`==` 比较和基于对象身份的缓存都会出错。

##### Q1.1.1: 那 `earlyProxyReferences` 有什么用？

它解决的是**「避免生成两次代理」**的问题。

```java
// AbstractAutoProxyCreator
private final Map<Object, Object> earlyProxyReferences = new ConcurrentHashMap<>(16);

@Override
public Object getEarlyBeanReference(Object bean, String beanName) {
    Object cacheKey = getCacheKey(bean.getClass(), beanName);
    this.earlyProxyReferences.put(cacheKey, bean);   // 记一笔
    return wrapIfNecessary(bean, beanName, cacheKey);
}

@Override
public Object postProcessAfterInitialization(Object bean, String beanName) {
    Object cacheKey = getCacheKey(bean.getClass(), beanName);
    // 如果这个 Bean 已经提前生成过代理，就不再处理
    if (this.earlyProxyReferences.remove(cacheKey) != bean) {
        return wrapIfNecessary(bean, beanName, cacheKey);
    }
    return bean;
}
```

没有这个 Map 的话：循环依赖场景下第 ③ 步生成了一个代理，
后面 A 正常走 `postProcessAfterInitialization` 时**又会生成一个**。
于是：

- 内存里有两个代理对象，浪费
- 一级缓存里存的是第二个代理，而 B 里持有的是第一个
  → **同一个 Bean 在容器里有两个实例**，单例约束被破坏

`earlyProxyReferences.remove(cacheKey) != bean` 这个判断是
「如果之前提前代理过，就跳过」。注意是从 Map 里 remove 后比较，
既能判断又能清理，一次操作完成两件事。

### Q2: 循环依赖不解决会有什么实际后果？

**如果只是普通的两个单例互相持有，其实没什么后果**——
对象能构造出来，方法能调用。所以很多团队会打开
`allow-circular-references` 就当它不存在。

但真实风险有三个：

**风险一：注入的是「半成品」**

```
B 在 @PostConstruct 里用 A.getSomething()
→ 此时 A 还没走完 initializeBean
→ A 的 @PostConstruct 还没执行、字段可能还是 null
→ 表现为偶发 NPE
```

**风险二：AOP 失效（这是最严重的）**

在「两级缓存」的错误实现下，或某些代理时机不匹配的场景下：

```java
@Service
public class AServiceImpl implements AService {
    @Autowired
    private BService bService;      // B 里持有的是未代理的 A

    @Transactional
    public void doSomething() { ... }
}
```

如果 B 拿到的 A 是原始对象而不是代理，
那么 **B 调用 `aService.doSomething()` 时事务不生效**——
不会报错，只是数据没回滚。这是最危险的类型：静默的错误行为。

**风险三：Bean 不再是单例**

如上文所述，重复生成代理会让同一个 Bean 出现两个实例。

#### Q2.1: 那正确的做法是什么？

按优先级：

**① 重构打破环（首选）**

```
A → B → A

方案 a：提取第三个 Bean C，把 A、B 都依赖的部分挪进去
方案 b：A 依赖 B，B 通过 ApplicationEventPublisher 发事件，
        由监听器回调 A —— 把直接依赖变成间接通知
方案 c：如果只是需要对方的一个方法，抽成接口，
        用 @Lazy 延迟注入
```

**② 用 `@Lazy` 打破**

```java
@Service
public class A {
    @Lazy
    @Autowired
    private B b;      // 注入的是一个代理，真正调用时才去容器取 B
}
```

`@Lazy` 注入的是一个延迟解析的代理，
在第一次调用 B 的方法时才真正从容器里拿 B。
这样就打断了「创建 A 时必须先创建 B」这条链。

代价是**多了一层代理**，且错误从启动期推迟到运行期
（B 不存在的话启动不会报错，第一次调用才报）。
所以它更适合作为过渡手段，而不是最终方案。

**③ 打开 `allow-circular-references`**

最后的选择。至少要在代码里写明为什么，并确保
没有依赖「注入半成品」的逻辑，以及 AOP 仍然有效。

## 常见坑

- **说「三级缓存是为了性能，用两级也行」** —— 两级会导致
  循环依赖场景下注入原始对象而非代理，AOP 静默失效
- **说「三级缓存解决所有循环依赖」** —— 构造器注入的循环依赖
  解决不了，因为无法在不调构造器的情况下暴露实例
- **混淆「实例化」与「初始化」** —— 三级缓存暴露的是
  **实例化后、初始化前**的对象，此时 `@PostConstruct` 还没执行
- **认为 B 拿到的是完整的 A** —— B 拿到的是 A 的早期引用，
  A 的初始化方法尚未执行
- **不知道 `earlyProxyReferences` 的作用** —— 说不清它如何
  避免重复生成代理、如何保证单例
- **说 Spring Boot 2.6 后循环依赖依然默认可用** ——
  默认已关闭，遇到会启动失败
- **把 `@Lazy` 当首选方案** —— 它把启动期错误推到了运行期，
  更适合过渡；首选是重构打破环
- **忽略「注入半成品」的隐蔽性** —— 它表现为偶发 NPE，
  而不是启动失败，很容易被当成其他问题

## 加分点

- 能完整背出 `getSingleton` 里「调用工厂后立即升级到二级缓存并移除三级」
  这段逻辑，并指出它保证了「早期引用全局唯一」
- 知道 **`getEarlyBeanReference` 才是三级缓存存在的真正原因**：
  它是一个「延迟决策点」，把「是否要生成代理」推迟到真正被依赖时
- 能讲清 `earlyProxyReferences` 的 `remove(cacheKey) != bean` 这个判断
  同时完成了「判断是否提前代理过」和「清理记录」两件事
- 提到**三级缓存用普通 `HashMap` 而非 `ConcurrentHashMap`**，
  因为它被一级缓存对象作为锁保护，这个细节能体现读过源码
- 知道 `defaultSingletonBeanRegistry` 里还有第四、第五个 Map
  （`singletonsCurrentlyInCreation`、`alreadyCreated`），
  它们分别用于「判定是否正在创建」和「防止重复创建」，
  和三级缓存不是一回事
- 能把「循环依赖」与「AOP 代理时机」这两个话题连起来讲，
  说明为什么 Spring 宁可多一层缓存也不接受两级
- 提到 `@Async` 导致的循环依赖问题：`@Async` 代理在
  更晚的阶段生成，循环依赖时可能拿到未代理对象，
  导致异步方法同步执行

## 版本差异

| 版本 | 差异 |
|---|---|
| Spring 3.x | 三级缓存机制定型；构造器循环依赖即报错 |
| Spring 4.3 | 单构造器场景可省略 `@Autowired`，构造器循环依赖的报错依旧 |
| Spring 5.x | `getEarlyBeanReference` 与 `earlyProxyReferences` 逻辑稳定 |
| Spring Boot 2.6 | **默认禁止循环依赖**（`spring.main.allow-circular-references` 默认 false），遇到直接启动失败并给出清晰提示 |
| Spring 6 / Boot 3 | 命名空间迁移到 `jakarta.*`；AOT 场景下对代理时机的处理更严格，循环依赖更应在设计阶段消除 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: Spring 解决循环依赖的三级缓存中，第三级 singletonFactories 存放的是什么？
    options:
      A: 完全初始化好的单例对象
      B: 提前暴露的半成品对象
      C: 能够生产早期引用的 ObjectFactory
      D: BeanDefinition
    answer: C
    analysis: 一级 singletonObjects 存最终对象，二级 earlySingletonObjects 存早期引用，三级 singletonFactories 存生产早期引用的工厂。工厂的意义在于把「是否生成 AOP 代理」这个决策延迟到真正被依赖时。

  - type: JUDGE
    stem: Spring 的三级缓存也能解决构造器注入造成的循环依赖。
    answer: F
    analysis: 三级缓存的前提是「先实例化、再暴露」，而构造器注入必须先解析构造参数才能实例化，实例永远无法被暴露，只能抛 BeanCurrentlyInCreationException。这也是 Spring 推荐构造器注入的原因之一：让循环依赖在启动期就暴露。

  - type: MULTI
    stem: 如果只保留二级缓存（移除 singletonFactories），可能出现哪些问题？
    options:
      A: 循环依赖场景下注入到其他 Bean 里的是未被 AOP 代理的原始对象
      B: 事务、日志等切面在使用方静默失效
      C: 若改为实例化后立即生成代理，会对不需要代理的 Bean 造成浪费
      D: 完全无法创建任何 Bean
    answer: ABC
    analysis: D 错误，绝大多数无循环依赖的 Bean 不受影响。核心问题在于失去了「延迟决策」的能力：要么注入未代理对象导致 AOP 失效，要么一律提前代理造成浪费。

  - type: CHOICE
    stem: AbstractAutoProxyCreator 中 earlyProxyReferences 这个 Map 的主要作用是什么？
    options:
      A: 缓存所有已创建的代理对象以提升性能
      B: 记录已被提前代理过的 Bean，避免在 postProcessAfterInitialization 中重复生成代理
      C: 存储 BeanDefinition 的依赖关系
      D: 记录循环依赖的调用链
    answer: B
    analysis: 它在 getEarlyBeanReference 时记下 Bean，在 postProcessAfterInitialization 中用 remove(cacheKey) != bean 判断：若已提前代理过就直接返回原对象。否则同一个 Bean 会生成两个代理实例，一级缓存里一个、被注入方持有另一个，单例约束被破坏。
    difficulty: 3
````
