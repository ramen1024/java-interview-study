---
slug: bean-scope-thread-safety
title: Spring 有哪些 Bean 作用域？单例 Bean 线程安全吗？
module: framework-spring
tags: [Bean 作用域, 线程安全, prototype, 作用域代理]
difficulty: 2
frequency: 2
related:
  - slug: bean-lifecycle
    type: PREREQUISITE
  - slug: circular-dependency
    type: RELATED
  - slug: aop-proxy
    type: RELATED
  - slug: auto-configuration
    type: RELATED
---

## 电梯版回答

常见作用域有五种：singleton 是默认的，一个容器里只有一个实例；prototype 每次获取都创建新实例；request、session、application 依赖 Web 环境，分别对应一次请求、一个会话和一个 ServletContext，Web 环境还有 websocket。单例 Bean 是否线程安全，不取决于 Spring，而取决于你有没有在 Bean 里放可变状态。无状态的 Service、DAO、Controller 是安全的，Spring 只保证「只创建一个实例」，从不保证「多个线程安全地访问这个实例」。真正的坑是在单例 Bean 里写可变的实例字段，比如把 SimpleDateFormat 当成员变量，它内部的 Calendar 是共享可变状态，并发调用会得到错乱结果。另外 prototype Bean 注入到单例里只会注入一次，之后拿到的还是同一个对象，解法是 @Lookup、ObjectProvider 或者把作用域改成带 TARGET_CLASS 代理的 ScopedProxyMode；request、session 作用域注入到单例里同样必须用代理，否则拿到的是启动时解析的早期引用。

## 展开讲解

### 五种 Bean 作用域

| 作用域 | 说明 | 是否依赖 Web 环境 |
|---|---|---|
| `singleton` | 默认。一个容器（ApplicationContext）内只有一个共享实例 | 否 |
| `prototype` | 每次 `getBean()` 都创建新实例 | 否 |
| `request` | 每个 HTTP 请求一个实例，请求结束销毁 | 是 |
| `session` | 每个 HTTP Session 一个实例 | 是 |
| `application` | 每个 `ServletContext` 一个实例 | 是 |
| `websocket` | 每个 WebSocket 会话一个实例 | 是 |

用法：

```java
@Component
@Scope("prototype")
public class TaskRunner { }

// Web 作用域也可以用常量
@Scope(WebApplicationContext.SCOPE_REQUEST)
```

两个容易被忽略的细节：

1. **singleton 的范围是「每个容器」而不是「每个 JVM」**。如果一个 JVM 里有多个 ApplicationContext（比如父子容器，或同时跑多个 Spring 应用），每个容器各有一份单例，所谓的「单例」并不跨容器。
2. **prototype Bean 的销毁 Spring 不管**。容器负责了 prototype 的创建和依赖注入（`@PostConstruct` 会执行），但**不会调用它的销毁回调**（`@PreDestroy` 不执行），也不会保存引用。需要释放资源的话要自己管。

### 单例 Bean 到底线程安全吗

结论一句话：**Spring 不保证单例 Bean 线程安全，安全性取决于 Bean 自身有没有可变状态。**

单例意味着容器里只有一个实例，所有并发请求共享它。于是：

- **无状态（stateless）**：Bean 里只有方法和不可变依赖，不保存请求相关的数据 → 天然线程安全。
- **有可变状态**：Bean 里有能被并发修改的实例字段 → 不安全。

Spring 的做法是「只创建一次」，它不会为单例 Bean 加锁，也不会给每次调用隔离出一个副本。所以线程安全的责任在开发者。

### 反面例子：在单例里放可变成员变量

最典型的错误是把 `SimpleDateFormat` 作为成员变量：

```java
@Service
public class DateService {

    // ❌ 危险：SimpleDateFormat 内部持有可变的 Calendar 等状态
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

    public String format(Date date) {
        return sdf.format(date);   // 多线程并发调用可能得到错乱结果，甚至抛异常
    }
}
```

`SimpleDateFormat` 的 `format` / `parse` 会修改内部共享的 `Calendar` 状态，所以它**不是线程安全的**。单例 Bean 里放它，等于把一个不安全对象暴露给所有并发请求。表现是「偶发的时间格式错乱、日期串位」，甚至 `NumberFormatException`，非常难复现。

正确写法有三种：

```java
// ① 方法内局部变量：最推荐，无共享
public String format(Date date) {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    return sdf.format(date);
}

// ② 换成线程安全的 DateTimeFormatter（Java 8+，不可变）
private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd");
public String format(LocalDate date) {
    return FORMATTER.format(date);
}

// ③ 确实要复用 SimpleDateFormat 时，用 ThreadLocal 隔离
private static final ThreadLocal<SimpleDateFormat> SDF =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));
```

注意第 ③ 种在 Web 应用里要小心：线程池会复用线程，ThreadLocal 里的值不会自动清理，长期不清理会造成内存占用；但 `SimpleDateFormat` 本身很小，问题通常不严重，只是要有意识。

其他同类错误：

- 在 Controller / Service 里放可变的实例字段保存「当前请求的用户」——并发下会串号
- 把 `HashMap` / `ArrayList` 当实例级缓存，多线程 `put` 会丢数据甚至死循环（HashMap 并发扩容）
- 实例字段上的计数器（`int count++` 不是原子操作）
- 把 `@RequestBody` 解析出的对象缓存成成员变量

### prototype 注入到单例的陷阱

这是 Scope 相关最经典的追问点：

```java
@Component
@Scope("prototype")
public class TaskRunner { }          // 每次 getBean 都是新实例

@Service
public class TaskService {

    @Autowired
    private TaskRunner taskRunner;    // ❌ 只注入一次！
}
```

**为什么拿到的永远是同一个对象**：单例 Bean 只在创建时解析一次依赖，`TaskService` 构造完成后 `taskRunner` 字段就固定指向那一个实例了。之后调用 `taskService.getTaskRunner()`（或者直接用字段）拿到的都是它，**不会因为 TaskRunner 是 prototype 就每次重新创建**。prototype 的语义是「向容器 getBean 时每次新建」，而这里只有创建 `TaskService` 时 getBean 过一次。

三种解法：

**① `@Lookup`**

```java
@Service
public abstract class TaskService {

    @Lookup
    public abstract TaskRunner getTaskRunner();   // 交给 Spring 生成子类实现
}
```

Spring 会为这个 Bean 生成一个 CGLIB 子类，覆写 `getTaskRunner()`，方法体就是从容器 `getBean(TaskRunner.class)`。限制是方法不能是 `private` / `final` / `static`，可以是抽象方法。

**② `ObjectProvider<T>`（Spring 4.3+）**

```java
@Service
public class TaskService {

    @Autowired
    private ObjectProvider<TaskRunner> runnerProvider;

    public void run() {
        TaskRunner runner = runnerProvider.getObject();   // 每次调用都重新解析
    }
}
```

`ObjectProvider` 是容器的延迟查询入口，`getObject()` 每次都向容器要，prototype 就会每次新建；单例则返回同一个。它还提供 `getIfAvailable()`、`getIfUnique()`、`stream()` 等无异常版本。

**③ 作用域代理 `ScopedProxyMode.TARGET_CLASS`**

```java
@Component
@Scope(value = "prototype", proxyMode = ScopedProxyMode.TARGET_CLASS)
public class TaskRunner { }
```

这样容器注入到别处的是个 **CGLIB 代理**，每次调用代理的方法时才去容器取真正的目标实例并转发。调用方完全感知不到代理，写法最干净，代价是多一层代理和一次方法调用的间接性。用 `ScopedProxyMode.INTERFACES` 则改为 JDK 动态代理，要求目标有接口。

### request / session 作用域注入单例，为什么必须用代理

`request`、`session` 作用域的对象生命周期短于单例。如果直接注入：

```java
@Service
public class UserService {

    @Autowired
    private UserContext userContext;   // ❌ request/session 作用域，注入到单例
}
```

启动时（或单例创建时）容器就要解析 `userContext`，但那时：
- request 作用域的 Bean 需要有一个当前请求才能创建，启动时根本没有请求，会直接报错；
- 即使能创建，这个引用也会被永久固定，后续请求拿到的都是第一次那个，语义完全错乱。

所以必须用**作用域代理**：

```java
@Component
@Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
public class UserContext { }
```

注入到单例里的是代理，每次真正调用方法时才通过 `RequestContextHolder` 拿到当前请求对应的目标对象。Spring 自己就是这么干的，比如注入 `HttpServletRequest` 时拿到的其实是由 `RequestObjectFactory` 生成的代理。

### 怎么判断一个 Bean 需不需要关注这个

一个简单的检查清单：

- Bean 里有没有非 final 的实例字段？有就重点看
- 这个字段会不会被多个请求同时读写？会就要处理
- 字段保存的是「请求级数据」还是「全局不可变配置」？前者必须换成参数传递或 ThreadLocal（并保证清理）
- 依赖的第三方类本身是不是线程安全的？`SimpleDateFormat`、`Calendar`、`HashMap` 都不是

无状态的 Service/DAO 通常可以放心；真正危险的是「顺手把状态放进字段」的那些写法。

## 追问链

### Q1: 单例 Bean 线程安全吗？

**不天然安全，也不天然不安全**，取决于 Bean 有没有可变状态。

Spring 的 singleton 语义是「容器内只创建一个实例并共享」，它不负责并发访问的互斥。线程安全与否是 Bean 自身的属性：

- 无状态 Bean（只有方法、不可变依赖、常量）→ 安全
- 有可变实例字段且被并发修改 → 不安全

所以正确的回答是「Spring 不保证单例 Bean 线程安全，无状态单例是安全的，有状态单例要自己处理同步或用其他作用域」。

#### Q1.1: 那为什么项目里大量单例 Service、Controller 都没出问题？

因为绝大多数 Service、DAO、Controller 都是**无状态**的。它们只持有：

- 不可变的、本身就是单例的依赖（`@Autowired` 进来的其他 Service、Mapper、DataSource）
- 常量（`static final`）
- 没有实例字段

请求相关的数据都以「方法参数」或「局部变量」的形式存在，天然隔离在各自的线程栈上，不存在共享。所以即使被所有请求共享同一个 Bean 实例，也不会互相干扰。

反过来说，这也解释了一条设计惯例：**Service/DAO/Controller 尽量写成无状态的**，把状态放到参数、局部变量、数据库或缓存里，而不是 Bean 的字段里。

##### Q1.1.1: 那哪些写法一定会出问题？举具体例子。

三个高频的：

**① 成员变量 `SimpleDateFormat`**

```java
private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
// 多线程并发 format/parse 会破坏内部 Calendar 状态，导致结果错乱
```

**② Controller 里用实例字段保存请求数据**

```java
@RestController
public class UserController {
    private User currentUser;      // ❌ 所有请求共享

    @GetMapping("/me")
    public User me(@RequestParam Long id) {
        currentUser = userService.get(id);   // 并发时互相覆盖
        return currentUser;
    }
}
```

A 请求设置完 `currentUser`，还没返回，B 请求就把它改成了别人的数据，A 的响应里就可能是 B 的用户——**越权数据泄漏**，安全级别的事故。

**③ 实例级可变集合当缓存**

```java
private final Map<String, Object> cache = new HashMap<>();   // ❌ 非线程安全
```

并发 `put` 在 HashMap 扩容时可能丢数据，Java 7 甚至可能形成环形链表导致死循环。要缓存就用 `ConcurrentHashMap`，或者直接上 Redis。

不管哪种，单例 Bean 的可变字段都要么改成无状态写法（局部变量/参数），要么用线程安全的数据结构或同步保护。

### Q2: `@Scope("prototype")` 的 Bean 注入到单例里，为什么每次拿到的还是同一个对象？

因为**依赖注入只发生在单例 Bean 创建的那一刻**。

```
创建 TaskService（singleton）
  → 解析 @Autowired private TaskRunner taskRunner
  → 向容器 getBean(TaskRunner.class)
  → 因为 TaskRunner 是 prototype，容器创建一个新实例返回
  → 赋值给字段，注入结束
之后所有请求用的都是 TaskService 这一个实例，它的 taskRunner 字段
永远指向当初那一个 TaskRunner，不会再触发 getBean
```

prototype 的语义是「**每次向容器 getBean 时都新建**」，不是「每次访问字段时都新建」。注入只有一次，所以只新建了一次。要每次拿到新的，就必须让「获取实例」这个动作发生在每次使用的时候，这正是三种解法在做的事。

#### Q2.1: `@Lookup`、`ObjectProvider`、`ScopedProxyMode` 三种解法有什么区别？

| 解法 | 原理 | 适用场景 | 代价 |
|---|---|---|---|
| `@Lookup` | Spring 生成 CGLIB 子类覆写方法，方法体变成 `getBean` | 需要按名字/类型取新实例的方法入口 | 方法不能 `private`/`final`/`static`；类要能被 CGLIB 代理 |
| `ObjectProvider<T>` | 容器提供的延迟查询入口，`getObject()` 每次向容器要 | 需要显式在代码里控制「什么时候取」，还想用 `getIfAvailable()` 之类的无异常 API | 代码里要显式调用，写法上能看出差异 |
| `ScopedProxyMode.TARGET_CLASS` | 注入的是 CGLIB 代理，方法调用时转发到当前作用域的实例 | 希望调用方完全无感、直接按类型注入 | 多一层代理；`TARGET_CLASS` 需 CGLIB，`INTERFACES` 需目标有接口 |

选择建议：**需要在代码里显式决定获取时机就用 `ObjectProvider`**；**只希望「像普通字段一样用，但每次调用都作用在当前实例」就用作用域代理**；`@Lookup` 更适合少量、明确的工厂方法入口。三者都不改变「单例的依赖只在创建时注入一次」这个事实，只是把实例获取推迟到使用时刻。

##### Q2.1.1: `request` 作用域注入到单例，为什么也一定要用代理？

因为单例的创建时机通常远早于任何请求——应用启动时容器就要把单例装配好。这时：

- `request` 作用域的 Bean 需要一个「当前请求」才能创建，启动时不存在当前请求，直接注入会失败；
- 即使容器想办法给了一个对象，它也会被固定成单例字段的永久值，后续请求全都拿到这同一个对象，作用域语义被彻底破坏。

作用域代理把「取实例」推迟到每次方法调用：代理内部通过 `RequestContextHolder` 拿到当前线程绑定的请求，再取出该请求对应的目标对象。**只要调用发生在某次请求的线程里，拿到的就是那次请求的实例。** 这也是为什么注入 `HttpServletRequest` 能正常工作——它本身就是用 `RequestObjectFactory` 生成的代理。

顺带一提，`session`、`application`、`websocket` 作用域注入单例时面临同样的问题，都必须用代理。

### Q3: 那 Spring 官方对这个问题的态度是什么？

官方文档的表述是：**Spring 不保证 Bean 的线程安全**，singleton 只是「一个容器一个实例」的生命周期约定。文档反复强调的开发习惯是：

- Bean 尽量无状态
- 有状态就用合适的作用域（request/session）或把状态外置
- 需要并发访问可变状态时，自己选择合适的并发原语

换句话说，Spring 把并发安全的责任交还给开发者，因为框架无法知道你的 Bean 里放了什么。这也和 Java 并发的通用原则一致：**共享可变状态才需要同步，不共享或不可变就不需要**。

#### Q3.1: 如果确实需要一个有状态的单例，应该怎么做？

按推荐程度：

1. **把状态移到方法参数或局部变量**，让 Bean 重新变回无状态——最彻底，没有同步开销。
2. **把状态外置到线程安全的地方**：Redis、数据库、`ConcurrentHashMap`、带过期的缓存。
3. **改用更短的作用域**：请求相关的状态用 `request` 作用域（配代理）或 ThreadLocal，会话相关的用 `session`。
4. **最后才考虑加锁**：用 `synchronized` 或 `ReentrantLock` 保护临界区。注意这会让并发退化成串行，QPS 直接受影响，而且锁的粒度、死锁风险都要评估。

##### Q3.1.1: 用 ThreadLocal 保存请求级状态安全吗？

在「同一个请求内、同一个线程上」是安全的，但有三个前提必须注意：

1. **必须清理**。Web 应用用线程池，线程会被复用。如果不 `remove()`，下一个请求可能读到上一个请求的残留数据——这是数据串号，更糟的是对象无法回收导致内存泄漏。标准写法是在 Filter 或 Interceptor 的 `afterCompletion` 里 remove。
2. **异步场景会断链**。切换线程（`@Async`、`CompletableFuture`、线程池）后 ThreadLocal 的值不会自动传递；Spring 的 `RequestContextHolder` 有 `inheritable` 模式，但异步下通常还是需要显式传递上下文。
3. **它不解决共享可变状态的问题，只是把作用域缩小到线程**。如果多个线程需要看到同一份状态，ThreadLocal 帮不上忙。

所以 ThreadLocal 的正确用法是「请求级、线程内、用完即清」的上下文传递，不是通用的线程安全方案。

## 常见坑

- **说「单例 Bean 线程不安全」** —— 准确说法是「Spring 不保证线程安全，无状态单例是安全的」。一刀切说单例不安全，说明没理解问题出在可变状态而不是单例本身
- **说「prototype Bean 注入到单例里，每次用都会新建」** —— 注入只发生一次，字段永远指向同一个对象。prototype 的语义是「每次向容器 getBean 都新建」，不是「每次访问字段都新建」
- **把 `SimpleDateFormat` 当单例 Bean 的成员变量** —— 它内部持有可变的 `Calendar` 状态，不是线程安全的；应改为方法内局部变量、`DateTimeFormatter` 或 ThreadLocal
- **说「@Scope("prototype") 能解决线程安全问题」** —— 它只是每次给你一个新实例，如果你把请求状态存到别处（静态字段、单例依赖）照样有并发问题；而且它带来的对象创建开销常常被低估
- **说「request 作用域 Bean 直接注入单例就行」** —— 必须用作用域代理，否则启动时没有当前请求，或者注入一个永久固定的早期引用
- **认为 Spring 会给单例 Bean 的调用自动加锁** —— 完全不会。Spring 只负责创建和注入，不介入并发访问
- **说「prototype Bean 和 singleton 一样会被容器管理销毁」** —— 容器不保存 prototype 的引用，也不会调用它的 `@PreDestroy`，销毁要自己负责
- **说「ThreadLocal 用完不用清理，GC 会处理」** —— 线程池复用线程时，ThreadLocal 的值会残留并被下一个请求读到，同时也可能造成内存泄漏，必须显式 `remove()`

## 加分点

- 能点明 **singleton 的作用范围是「每个容器」而非「每个 JVM」**，父子容器场景下同一个类可能有多份单例
- 知道 **prototype Bean 的销毁不受容器管理**，`@PreDestroy` 不会执行，这是很多资源泄漏问题的根源
- 能说出 **三种 prototype 解法背后的共同点**：都是把「获取实例」从「创建单例时」推迟到「使用时」（`@Lookup` 覆写方法、`ObjectProvider.getObject()`、作用域代理转发）
- 知道 **`ScopedProxyMode.TARGET_CLASS` 用 CGLIB、`INTERFACES` 用 JDK 动态代理**，以及为什么接口代理要求目标实现接口
- 提到 **`HttpServletRequest` 本身就是一个 request 作用域代理**（由 `RequestObjectFactory` 创建），能把这个例子和「作用域代理为什么必要」连起来讲
- 知道 **`RequestContextHolder` 是作用域代理取当前请求的关键**，它底层用 ThreadLocal（可继承版本）绑定当前请求
- 能指出 **`@Async` / 线程池会断开 ThreadLocal 和请求上下文**，异步方法里读 request 作用域对象可能拿到 null 或过期数据
- 提到 **`@Lazy` 注入本质上也是注入一个代理**，和 `ScopedProxyMode` 的目标类似（延迟解析），但 `@Lazy` 是「延迟初始化」而不是「每次取新实例」，两者不能混为一谈
- 知道 **无状态设计不只是为了线程安全**，也让 Bean 更容易测试、更容易被缓存和复用

## 版本差异

| 版本 | 差异 |
|---|---|
| Spring 2.5 | 引入 `@Scope` 注解与 `@Component` 系列，作用域从 XML 的 `scope` 属性迁移到注解 |
| Spring 3.0 | `@Scope` 支持 `proxyMode`，作用域代理成为 request/session 注入单例的标准做法 |
| Spring 4.3 | 引入 `ObjectProvider`（此前用 `ObjectFactory`）以及隐式的单构造器注入；prototype 的延迟获取有了更顺手的 API |
| Spring 5.x | 作用域机制稳定；`@Lookup`、`ObjectProvider`、`ScopedProxyMode` 三种解法并存 |
| Spring 6 / Boot 3 | 命名空间迁移到 `jakarta.*`；作用域与作用域代理的行为不变 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 一个无状态 Service 被注册为默认的 singleton 作用域，多个请求并发访问它，下列说法正确的是？
    options:
      A: 一定会出现线程安全问题，需要加锁
      B: 它是线程安全的，因为没有可变的共享状态
      C: Spring 会自动为它加锁保证安全
      D: 必须改成 prototype 才能并发访问
    answer: B
    analysis: 线程安全取决于有没有可变共享状态。无状态 Bean 只有方法、不可变依赖和常量，请求数据都在方法参数或局部变量里，天然隔离；Spring 只保证一个容器一个实例，不会自动加锁。

  - type: JUDGE
    stem: 把 prototype 作用域的 Bean 注入到 singleton Bean 的字段里，每次通过该字段使用它都会得到新实例。
    answer: F
    analysis: 依赖注入只在单例 Bean 创建时发生一次，字段被永久固定为当时创建的那个 prototype 实例。要做到每次获取新实例，需用 @Lookup、ObjectProvider 或 ScopedProxyMode 作用域代理。

  - type: MULTI
    stem: 关于把 request 作用域的 Bean 注入到单例 Bean，下列说法正确的有？
    options:
      A: 必须使用作用域代理，比如 ScopedProxyMode.TARGET_CLASS
      B: 直接注入时，单例创建阶段通常没有当前请求，无法解析该 Bean
      C: 作用域代理在每次方法调用时通过 RequestContextHolder 取当前请求的实例
      D: 直接注入也可以，Spring 会在每次请求开始时自动刷新这个字段
    answer: ABC
    analysis: D 错误。Spring 不会自动刷新单例字段；直接注入要么启动期解析失败，要么把早期引用永久固定。作用域代理把实例获取推迟到方法调用，通过 RequestContextHolder 绑定当前请求的线程拿到对应实例。

  - type: CLOZE
    stem: |
      补全让 prototype Bean 以作用域代理方式注入的写法：
      ```java
      @Component
      @Scope(
          value = "prototype",
          proxyMode = ScopedProxyMode.{{1}}
      )
      public class TaskRunner { }
      ```
    blanks:
      - ["TARGET_CLASS"]
    analysis: ScopedProxyMode.TARGET_CLASS 让容器注入一个 CGLIB 代理，每次调用代理方法时才去容器取当前作用域的目标实例，从而绕开「单例字段只注入一次」的限制；若目标实现了接口也可以用 INTERFACES 走 JDK 动态代理。
    difficulty: 3
````
