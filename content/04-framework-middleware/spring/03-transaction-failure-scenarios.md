---
slug: transaction-failure-scenarios
title: Spring 事务在哪些情况下会失效？怎么排查和修复？
module: framework-spring
tags: [Spring, 事务, AOP, 自调用, 传播行为]
difficulty: 3
frequency: 3
related:
  - slug: bean-lifecycle
    type: PREREQUISITE
  - slug: circular-dependency
    type: RELATED
---

## 电梯版回答

事务失效的根本原因只有一个：**调用没有经过代理**，或者异常没有按预期传播到代理层。最常见的几种是：类内部方法自调用，走的是 this 而不是代理对象，注解完全不生效；方法不是 public，代理拦截不到；抛的是受检异常而默认只回滚 RuntimeException 和 Error，需要显式配 rollbackFor；异常在方法内被自己 catch 掉了没有继续抛；对象是 new 出来的不受 Spring 管理；数据库表用的是 MyISAM 这类不支持事务的引擎。排查思路是先把断点打在 DataSourceTransactionManager 上确认事务到底有没有开启，再看调用是否经过了代理。

## 展开讲解

### 前提：事务是靠 AOP 代理实现的

```java
@Service
public class OrderService {
    @Transactional
    public void create(Order order) { ... }
}
```

Spring 为 `OrderService` 生成一个代理对象，
外部注入到的 `orderService` 其实是这个代理：

```
调用方 → 代理（开启事务）→ 目标方法 → 提交/回滚
```

**所有失效场景都可以归结为「调用没经过代理」或
「代理没能按预期判定提交还是回滚」**。记住这一点，
面试时就能自己推导出所有场景，而不是死记清单。

### 场景一：自调用（最高频）

```java
@Service
public class OrderService {

    public void outer() {
        this.inner();       // ★ this 是原始对象，不是代理
    }

    @Transactional
    public void inner() {
        // 事务不生效！
    }
}
```

`this` 指向的是原始对象，不是代理。所以 `inner()` 的
`@Transactional` 完全不会被处理——**不报错，只是没事务**。

表现形式：`outer()` 里 `inner()` 抛异常，
但 `inner()` 之前写入的数据**没有回滚**。

**三种修法**：

```java
// ① 注入自身代理（推荐，最清晰）
@Service
public class OrderService {
    @Autowired
    @Lazy                       // 加 @Lazy 避免自身注入引起的循环依赖
    private OrderService self;

    public void outer() {
        self.inner();           // 走代理
    }
}

// ② 拆分到另一个 Bean（最干净，也最符合设计）
@Service
public class OrderService {
    @Autowired
    private OrderInnerService innerService;

    public void outer() {
        innerService.inner();   // 跨 Bean 调用，天然走代理
    }
}

// ③ 用 AopContext.currentProxy()
@Service
public class OrderService {
    public void outer() {
        ((OrderService) AopContext.currentProxy()).inner();
    }
}
// 需要开启 -XX:+ExposeProxy 对应的配置：@EnableAspectJAutoProxy(exposeProxy = true)
```

**首选方案 ②**：自调用往往说明职责划分有问题。
把内层方法提成一个独立 Bean 既解决了事务，又改善了结构。

方案 ③ 依赖 `exposeProxy`，并且硬编码了「我在被代理」这个假设，
耦合更重，一般不推荐。

### 场景二：方法不是 public

Spring 的代理机制（无论 JDK 动态代理还是 CGLIB）
**只拦截 public 方法**。这是 Spring 官方文档明确写下的限制。

```java
protected void doSomething() { ... }    // 加了 @Transactional 也没用
private void doSomething() { ... }      // 同上
```

**最坑的地方是不会报错**：注解加在非 public 方法上，
Spring 不会抛异常也不会警告，只是静默不生效。
所以「代码看起来对，事务就是不生效」时，
第二个该检查的就是方法可见性。

**如果确实需要**：改用 AspectJ 编译期/加载期织入
（`@EnableLoadTimeWeaving`），它不依赖代理，
可以拦截任意可见性的方法。但绝大多数场景下
把方法改成 public 或用方案 ② 更简单。

### 场景三：异常类型不匹配

这是默认行为，不是 bug：

```java
// Spring 默认只在遇到 RuntimeException 或 Error 时回滚
@Transactional      // 等价于 @Transactional(rollbackFor = {RuntimeException.class, Error.class})
public void create() throws IOException {
    // ...
    throw new IOException("IO 失败");   // ★ 受检异常，不回滚！
}
```

必须显式声明：

```java
@Transactional(rollbackFor = Exception.class)
```

**为什么默认不回滚受检异常**：受检异常在设计上表示「可预期的、
调用方应该处理的」情况。Java 的异常体系把它和
「程序缺陷（RuntimeException）」区分开了。
Spring 沿用了这个语义——受检异常意味着业务上可以继续，
所以默认不整体回滚。

实际项目里**建议统一加 `rollbackFor = Exception.class`**，
因为业务代码里「抛异常就是要回滚」几乎是必然的期望。

### 场景四：异常被自己吞掉

```java
@Transactional
public void create() {
    try {
        doInsert();
    } catch (Exception e) {
        log.error("插入失败", e);      // ★ 异常被吃掉了
    }
    // 方法正常返回，事务提交
}
```

代理只能看到「方法正常返回了」，于是提交事务。
数据处于「部分成功」的不一致状态。

**修法**：

```java
} catch (Exception e) {
    log.error("插入失败", e);
    // ① 重新抛出
    throw e;
    // 或者 ② 如果确实要吞掉，必须显式标记回滚
    // TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
}
```

方案 ② 是个容易忘的技巧：**在需要「记录日志但不中断流程，
同时让事务回滚」时，用 `setRollbackOnly()` 是唯一可行的方式**，
因为不再抛异常就无法让代理感知失败。

注意：如果内层方法用了 `REQUIRES_NEW`，
它有自己的独立事务，此时内层已经在自己的事务里回滚了，
外层是否继续是另一个决策——这类嵌套语义很容易出错。

### 场景五：对象不受 Spring 管理

```java
OrderService service = new OrderService();   // ★ 手动 new 的
service.create(order);                        // 没有代理，事务不生效
```

只有从容器里取出来的（`@Autowired` 注入的、`getBean()` 拿到的）
才是代理对象。手动 `new` 出来的永远是原始对象。

同理，如果在 `@PostConstruct` 里获取 `this` 并传给别的组件，
传过去的也是原始对象。

### 场景六：数据库引擎不支持

MySQL 的 **MyISAM 不支持事务**，InnoDB 才支持。

```sql
SHOW TABLE STATUS LIKE 'order_info'\G    -- 看 Engine 列
```

这个场景现在少见了（InnoDB 是 MySQL 5.5 之后的默认引擎），
但如果是从老系统迁移过来的表，或者在 ORM 配置里
指定了引擎，就有可能踩到。表现是「事务代码完全正确，
但回滚就是不动」——连日志都看不出问题。

### 场景七：多线程 / 异步

事务上下文绑定在 **ThreadLocal** 上（`TransactionSynchronizationManager`）。
新起的线程拿不到父线程的事务：

```java
@Transactional
public void create() {
    executor.submit(() -> {
        // 这里是另一个线程，没有事务上下文
        orderDao.insert(...);     // 独立连接、独立事务
    });
    throw new RuntimeException("回滚外层");
    // 外层回滚了，但子线程插入的数据已经提交，无法回滚
}
```

**这是分布式事务问题的雏形**：跨线程、跨进程的操作
本质上不在同一个事务里。要保证一致性得用
「本地消息表 + 补偿」或 TCC/Saga 这类方案，
而不是指望单机事务。

`@Async` 同理：异步方法在另一个线程执行，
既不共享调用方的事务，它自己的 `@Transactional` 也只在
自己被代理调用时才生效。

### 场景八：传播行为导致的误解

| 传播行为 | 行为 | 容易误解的点 |
|---|---|---|
| `REQUIRED`（默认） | 有事务就加入，没有就新建 | 内层回滚会让整个外层回滚 |
| `REQUIRES_NEW` | 总是新建，挂起当前 | 内层回滚不影响外层，但会占用两个连接 |
| `NESTED` | 嵌套事务（savepoint） | 外层回滚会带上内层，内层回滚不影响外层 |
| `NOT_SUPPORTED` | 挂起事务，非事务执行 | 里面的写操作不会回滚 |
| `NEVER` | 有事务就抛异常 | — |
| `MANDATORY` | 必须有事务，否则抛异常 | — |

两个高频误解：

**误解一**：以为 `REQUIRED` 的内层 catch 了异常就没事。
其实内层抛异常会标记整个事务为 rollback-only，
即使外层 catch 了，最后提交时也会抛
`UnexpectedRollbackException`。这是生产环境里很典型的一类报错。

**误解二**：以为 `REQUIRES_NEW` 是纯嵌套。
它其实是**两个独立的物理事务**，且需要**两条数据库连接**。
在高并发下，如果外层已经占了连接、内层再要一条，
可能触发连接池耗尽——这类问题表现为「压力上来后
大量请求超时」，而代码里完全看不出问题。

### 排查方法

**第一步：确认事务有没有开**

把断点打在 `DataSourceTransactionManager#doBegin`（或开 DEBUG 日志）：

```yaml
logging:
  level:
    org.springframework.transaction: DEBUG
    org.springframework.jdbc.support: DEBUG
```

日志里会出现 `Creating new transaction` / `Participating in existing transaction`
或 `No existing transaction found`。看到什么都没打印，就是没进代理。

**第二步：确认调用是否经过代理**

```java
// 在方法里打印 this 的类名
log.info("current class = {}", this.getClass().getName());
```

- 打印出 `OrderService` → 原始对象，**没走代理**（自调用/手动 new）
- 打印出 `OrderService$$EnhancerBySpringCGLIB$$...`
  或 `com.sun.proxy.$Proxy...` → 代理对象，走的正常路径

这个小技巧能一秒定位自调用问题。

**第三步：确认回滚判定**

看日志里是否有 `Initiating transaction rollback`。
如果只有 `Initiating transaction commit`，
说明异常没被代理感知（被吞掉了，或者是受检异常未配 rollbackFor）。

## 追问链

### Q1: 为什么 Spring 默认不回滚受检异常？

这是**设计语义**上的选择，源自 Java 的异常分类：

| 异常类型 | 语义 | 编译期检查 |
|---|---|---|
| `Error` | 系统级严重问题（OOM、栈溢出） | 否 |
| `RuntimeException` | 程序缺陷（空指针、越界、非法参数） | 否 |
| 受检异常 | **可预期的业务情况**（文件不存在、网络超时） | 是 |

受检异常的定位是「调用方应该处理的正常情况」。
比如「余额不足」会抛受检异常，但这是正常业务分支，
事务里其他操作可能仍然需要提交（比如记一笔失败日志）。

所以 Spring 的默认策略是：**只有表示「出错了」的异常才回滚**。
受检异常要么是业务分支，要么调用方应该处理，
不由框架替你做决定。

但现实是**绝大多数团队都期望「抛异常就回滚」**，
所以统一加 `rollbackFor = Exception.class` 是更实用的默认值。
《阿里巴巴 Java 开发手册》也明确要求显式指定 rollbackFor。

#### Q1.1: 那 `rollbackFor` 和 `noRollbackFor` 同时配会怎样？

`noRollbackFor` 优先级更高，且**子类规则会传导**。

```java
@Transactional(
    rollbackFor = Exception.class,
    noRollbackFor = BusinessException.class
)
```

规则如下：

- 抛 `BusinessException` → 命中 `noRollbackFor` → **不回滚**
- 抛 `BusinessException` 的子类 → **也不回滚**（子类继承规则）
- 抛其他 `Exception` → 命中 `rollbackFor` → 回滚
- 抛 `Error` → 回滚（`Error` 默认回滚，且不受 noRollbackFor 影响）

底层实现是靠**异常类型匹配最近原则**：
Spring 会找出「继承层次上离抛出异常最近的那个配置」。
如果一个异常同时匹配两个规则，取继承层次最近的那个。

**实践上的用法**：定义业务异常基类并配 `noRollbackFor`，
用于「需要记录失败但不应回滚」的场景：

```java
public class BusinessException extends RuntimeException { }

// 期望：库存不足时不回滚已经写入的操作日志
@Transactional(rollbackFor = Exception.class, noRollbackFor = BusinessException.class)
```

但要小心：**这会让调用方很难判断数据到底回滚没回滚**。
更清晰的做法是拆成两个事务方法，
而不是靠一套注解组合制造隐式语义。

##### Q1.1.1: `@Transactional` 加在类上和方法上谁生效？

**方法上的注解覆盖类上的**，且遵循「就近优先」：

```
方法上的 @Transactional       ← 最高优先级
实现类上的 @Transactional
接口方法上的 @Transactional    ← 仅 JDK 动态代理时生效
接口上的 @Transactional
```

**接口上的 `@Transactional` 需要特别注意**：
JDK 动态代理会读取接口注解，但 **CGLIB 代理默认不读接口注解**
（因为它基于子类，读的是类层次）。

Spring Boot 2.x 起默认统一使用 CGLIB 代理
（`spring.aop.proxy-target-class=true` 是默认值），
所以**接口上的 `@Transactional` 可能不生效**——
这是从 JDK 代理迁移到 CGLIB 后容易被忽略的兼容性问题。

**结论：注解加在实现类的具体方法上**，
不要依赖接口上的注解，也不要只靠类级别注解
（类级别会让该类所有 public 方法都带事务，
粒度过粗，还可能有意外的传播行为）。

### Q2: 怎么在生产上快速定位事务问题？

三个层次的工具，从快到慢：

**① 开事务 DEBUG 日志（信息量最大）**

```yaml
logging:
  level:
    org.springframework.transaction: DEBUG
```

会打印成套的信息，一眼能看出来：

```
Creating new transaction with name [OrderService.create]:
    PROPAGATION_REQUIRED, ISOLATION_DEFAULT
Acquired Connection [...] for JDBC transaction
Switched JDBC Connection [...] to manual commit
Initiating transaction rollback          ← 期待看到这个
Rolling back JDBC transaction on Connection [...]
```

如果只有 `Creating new transaction` 却没有 `Initiating transaction rollback`，
问题就在异常没传到代理。

**② 在方法里打印代理类名**

前面提过的技巧，一秒区分「原始对象」与「代理对象」：

```java
log.info("proxy check: {}", this.getClass().getName());
```

**③ Arthas 在线诊断（不停机）**

```bash
# 看这个方法实际是被哪个类调用的
stack com.example.OrderService create

# 观察事务相关方法是否被调用
watch org.springframework.transaction.support.TransactionTemplate execute \
      -x 2 -n 5

# 直接反编译看字节码里到底有没有事务增强
jad com.example.OrderService
```

`jad` 特别有用：如果字节码里没有 `TransactionInterceptor` 的调用，
就说明根本没被增强，可以直接排除「异常类型」「传播行为」这些方向。

#### Q2.1: 有没有办法从设计上避免这类问题？

有，而且是更值得投入的方向：

**① 统一事务入口，禁止跨层自调用**

约定「事务方法只在 Service 层的 public 方法上，
且 Controller 只调 Service」。配合静态检查
（ArchUnit 可以写规则断言）能挡住大部分自调用。

**② 用编程式事务替代声明式**

```java
@RequiredArgsConstructor
public class OrderService {

    private final TransactionTemplate transactionTemplate;   // 注入模板

    public void create(Order order) {
        // 事务边界显式可见，不存在"代理没生效"的疑惑
        transactionTemplate.executeWithoutResult(status -> {
            orderDao.insert(order);
            itemDao.insertAll(order.getItems());
        });
    }
}
```

**编程式事务的三个优势**：

- **边界显式**：看代码就知道哪里开、哪里提交，不依赖代理
- **粒度可控**：可以把「不需要事务的耗时操作」挪到事务外
- **没有自调用问题**：`execute` 内部就是直接调用，
  不受 `this` 的影响

**劣势**是代码侵入性强、需要手动处理嵌套语义。
适合「事务边界复杂或对性能敏感」的核心链路，
而不是全量替换声明式。

**③ 事务方法只做数据库操作**

把外部调用（RPC、发消息、写文件）全部挪到事务**之后**：

```java
public void create(Order order) {
    transactionTemplate.executeWithoutResult(s -> orderDao.insert(order));
    // 事务已提交，再做不可回滚的外部操作
    mqProducer.send(new OrderCreatedEvent(order.getId()));
}
```

这既缩短了事务持有时间（减少锁竞争、降低连接池压力），
又避免了「外部调用成功但事务回滚」的不一致
（虽然引入了相反方向的「事务成功但消息发送失败」，
那要靠本地消息表解决）。

## 常见坑

- **把「事务失效」理解成「注解写错了」** —— 绝大多数是
  **代理没生效**（自调用、非 public、手动 new），而不是注解本身有问题
- **注解加在非 public 方法上而不自知** —— 不报错、不警告，
  只是静默失效。这是最隐蔽的一类
- **只配 `@Transactional` 不配 `rollbackFor`** ——
  受检异常不回滚，是默认行为不是 bug
- **在方法内 catch 异常后不重新抛** —— 代理感知不到失败，
  必然提交。需要 `setRollbackOnly()` 或重新抛出
- **以为 `REQUIRED` 内层 catch 异常就没事** ——
  内层抛异常会标记 rollback-only，外层提交时会抛
  `UnexpectedRollbackException`
- **以为 `REQUIRES_NEW` 是纯嵌套** —— 它是两个独立物理事务，
  需要两条连接，高并发下可能耗尽连接池
- **在 `@Transactional` 方法里起线程或调 `@Async`** ——
  事务上下文绑在 ThreadLocal 上，子线程拿不到；
  这已经是分布式一致性问题的范畴
- **依赖接口上的 `@Transactional`** —— Spring Boot 2.x 起
  默认 CGLIB 代理，接口注解可能不生效
- **忽略数据库引擎** —— MyISAM 不支持事务，
  代码再正确也回滚不了

## 加分点

- 能把所有失效场景**归纳成一句话**：
  「调用没经过代理，或异常没传到代理」。
  这说明理解了机制而不是背了清单
- 知道 `this.getClass().getName()` 这个**一秒区分原始对象与代理**
  的排查技巧，比翻源码快得多
- 提到 `TransactionAspectSupport.currentTransactionStatus()
  .setRollbackOnly()` 这个「吞掉异常但仍要回滚」的唯一手段
- 能讲清 `UnexpectedRollbackException` 的成因（内层标记
  rollback-only 后外层仍试图提交），这是生产上很常见但
  很难一眼看懂的报错
- 知道**编程式事务（`TransactionTemplate`）在核心链路是更优选择**，
  并给出三条理由（边界显式、粒度可控、无自调用问题），
  而不是简单地认为「声明式一定更好」
- 提到 **ArchUnit 静态检查**可以从架构层面禁止跨层自调用，
  把问题挡在编码阶段
- 能把「缩短事务持有时间」和连接池/锁竞争联系起来：
  事务越长，占用的数据库连接越久、行锁持有越久，
  高并发下先出问题的是连接池而不是数据库
- 知道 `noRollbackFor` 的**子类继承规则**和
  「继承层次最近优先」的匹配逻辑

## 版本差异

| 版本 | 差异 |
|---|---|
| Spring 3.x | `@Transactional` 定型；基于代理，仅 public 方法生效 |
| Spring 4.3 | `@Transactional` 支持在类和方法上声明；`rollbackFor` 语义稳定 |
| Spring 5.x | `TransactionTemplate` 与 `@Transactional` 均支持响应式的事务管理（`ReactiveTransactionManager`） |
| Spring Boot 2.x | 默认 `spring.aop.proxy-target-class=true`，统一使用 CGLIB 代理；接口上的 `@Transactional` 可能因此不再生效 |
| Spring 6 / Boot 3 | 命名空间迁移到 `jakarta.*`；AOT 与 GraalVM 场景下 CGLIB 代理改为编译期生成，代理相关的排查手段（运行时增强）需要相应调整 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 一个 @Service 类中，非事务方法 A 内部直接调用同类的事务方法 B（this.b()），结果是什么？
    options:
      A: B 的事务正常生效
      B: B 的事务不生效，且不会报错
      C: 启动时抛异常
      D: B 会以 REQUIRES_NEW 方式运行
    answer: B
    analysis: this 指向原始对象而非代理，所以 B 上的 @Transactional 完全不会被处理。既不生效也不报错，这正是自调用问题最危险的地方——数据没回滚却看不出任何异常。

  - type: MULTI
    stem: 下列哪些情况会导致 @Transactional 失效？
    options:
      A: 注解加在 protected 方法上
      B: 方法内抛出自定义受检异常且未配置 rollbackFor
      C: 方法内 catch 了异常且未重新抛出，也未调用 setRollbackOnly
      D: 通过 new 创建对象后调用其事务方法
    answer: ABCD
    analysis: 四项都会失效。A 是代理只拦截 public 方法；B 是默认只回滚 RuntimeException 与 Error；C 是代理感知不到失败；D 是手动创建的对象没有代理。

  - type: JUDGE
    stem: 使用 REQUIRED 传播行为时，内层方法抛出的异常被外层 catch 住后，事务仍可正常提交。
    answer: F
    analysis: 内层抛出异常会把共享的同一个事务标记为 rollback-only，外层即使 catch 住，提交时也会抛 UnexpectedRollbackException。这是生产环境里常见但不易一眼看懂的报错。

  - type: CHOICE
    stem: 想快速确认一个 @Transactional 方法是否走了代理，最直接的做法是？
    options:
      A: 在方法里打印 this.getClass().getName()，看类名是否含 CGLIB 或 $Proxy
      B: 查看 application.yml 的配置
      C: 加日志统计方法耗时
      D: 用 jstack 查看线程栈
    answer: A
    analysis: 打印出原始类名说明没走代理（自调用或手动 new），打印出 EnhancerBySpringCGLIB 或 $Proxy 说明走了代理。这是排查自调用问题最快的办法。
````
