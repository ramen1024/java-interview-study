---
slug: transaction-propagation
title: Spring 的七种事务传播行为分别是什么？
module: framework-spring
tags: [Spring, 事务, 传播行为, REQUIRES_NEW, NESTED]
difficulty: 3
frequency: 3
related:
  - slug: transaction-failure-scenarios
    type: PREREQUISITE
  - slug: aop-proxy
    type: PREREQUISITE
---

## 电梯版回答

Spring 有七种传播行为，默认是 REQUIRED。它们的核心区别在于「当前已经有一个事务时怎么处理」，可以分成三类。第一类会真正管理事务边界：REQUIRED 有事务就加入、没有就新建，内外共享同一个物理事务，所以内层抛异常会把整个事务标记成 rollback-only，外层即使 catch 住，提交时也会抛 UnexpectedRollbackException；REQUIRES_NEW 挂起当前事务并新开一个独立的物理事务，内层回滚不影响外层、锁会立即释放，代价是要多占一条数据库连接，而且读不到外层未提交的数据；NESTED 和它常被混为一谈，其实 NESTED 是同一个物理事务里的 savepoint，回滚只回到保存点、外层还能继续提交。第二类是 SUPPORTS 和 NOT_SUPPORTED，前者有事务就加入、没有就非事务执行，后者挂起当前事务后非事务执行。第三类是 MANDATORY 和 NEVER，MANDATORY 要求必须在事务里否则抛异常，NEVER 恰好相反、有事务就抛异常。这些逻辑都实现在 TransactionInterceptor 调用的 AbstractPlatformTransactionManager#getTransaction 里。

## 展开讲解

### 七种传播行为速查

| 传播行为 | 当前已有事务 | 当前没有事务 |
|---|---|---|
| `REQUIRED`（默认） | 加入当前事务，共享同一个物理事务 | 新建事务 |
| `REQUIRES_NEW` | 挂起当前事务，新建一个独立的物理事务 | 新建事务 |
| `NESTED` | 在当前物理事务内创建 savepoint | 新建事务（此时等价于 REQUIRED） |
| `SUPPORTS` | 加入当前事务 | 非事务执行 |
| `NOT_SUPPORTED` | 挂起当前事务，非事务执行 | 非事务执行 |
| `MANDATORY` | 加入当前事务 | 抛 `IllegalTransactionStateException` |
| `NEVER` | 抛 `IllegalTransactionStateException` | 非事务执行 |

记忆方法：只有 `REQUIRED`、`REQUIRES_NEW`、`NESTED` 会在「没有事务」时新建事务；`SUPPORTS`、`NOT_SUPPORTED`、`NEVER` 都接受无事务执行；`MANDATORY` 是唯一「必须有」的。

### REQUIRED 与 REQUIRES_NEW 的本质差异

**`REQUIRED` 是同一个物理事务。** 内层方法虽然有自己的「逻辑事务作用域」（可以单独标记 rollback-only），但底层用的是同一条数据库连接、同一个物理事务：

```
物理事务 T1
├─ 外层方法（逻辑作用域 1）
└─ 内层方法 REQUIRED（逻辑作用域 2）
```

因此内层做的事外层都看得见（未提交的数据同连接可读），内层回滚也会拖垮外层。

**`REQUIRES_NEW` 是两个物理事务。** 内层开启时，外层事务被挂起（`suspend()`），内层获取新的连接、开启新的物理事务：

```
物理事务 T1（外层，挂起）
物理事务 T2（内层，独立连接）
```

由此推出三个实践结论：

- 内层提交/回滚**完全独立**，内层回滚不影响外层，外层回滚也不影响内层已提交的数据
- 内层**读不到外层未提交的数据**——不同的连接，且 InnoDB 默认的 REPEATABLE READ 下更是看不到
- 内层可以独立声明自己的隔离级别、超时和只读设置，不继承外层
- 代价：需要**额外的数据库连接**。外层事务的连接在挂起期间仍被占用（连接持有者还挂在事务同步器里），内层再要一条，等于同一线程同时占两条连接。高并发下这很容易把连接池打满，表现为「压力一上来就大量超时」，而代码看起来完全正常
- 内层结束时它持有的**行锁立即释放**，不会像 REQUIRED 那样一直持有到最外层提交

### NESTED 与 REQUIRES_NEW 的差异

两者最容易被当成一回事，实际完全不同：

| 维度 | `NESTED` | `REQUIRES_NEW` |
|---|---|---|
| 物理事务个数 | 1 个（复用外层） | 2 个（独立） |
| 实现机制 | JDBC savepoint | 挂起外层 + 新建事务 |
| 数据库连接 | 复用外层同一条连接 | 需要额外一条连接 |
| 内层回滚 | 回滚到 savepoint，外层可继续提交 | 内层独立回滚，外层不受影响 |
| 外层回滚 | **会带上内层**（同一个物理事务） | 不影响内层（内层已提交的部分保留） |
| 内层能否读到外层未提交数据 | 能（同一连接、同一事务） | 不能（不同连接、不同事务） |
| 依赖 | 需要资源支持 savepoint | 无特殊要求 |

一句话概括：**`NESTED` 是「同一个事务里的存档点」，`REQUIRES_NEW` 是「另起炉灶」**。

`NESTED` 的落地载体是 `java.sql.Savepoint`。`DataSourceTransactionManager` 默认允许嵌套事务（内部把 `nestedTransactionAllowed` 设为 true），并在 `doBegin` 之外通过 `createAndHoldSavepoint` 创建保存点。

### 三者回滚时的实际行为对比

假设外层方法先写数据 A，再调用内层方法写数据 B，然后内层抛异常：

| 场景 | `REQUIRED` | `REQUIRES_NEW` | `NESTED` |
|---|---|---|---|
| 内层抛异常，外层不 catch | 整个事务回滚，A、B 都没了 | 内层回滚 B；异常传上去后外层也回滚 A | 回滚到 savepoint，B 没了；异常传上去后外层也回滚 A |
| 内层抛异常，外层 catch 住并继续 | **不能正常提交**：内层已把共享事务标记 rollback-only，外层提交时抛 `UnexpectedRollbackException` | 外层可以正常提交，A 保留、B 被回滚 | 外层可以正常提交，A 保留、B 被回滚 |
| 外层抛异常导致回滚 | A、B 都回滚 | A 回滚；B 若内层已提交则保留 | A、B 都回滚 |
| 内层能否读到外层刚写入的 A | 能（同一事务） | 不能（不同连接） | 能（同一事务） |

第二行是面试最爱考的地方：`REQUIRED` 下「内层抛异常、外层 catch」**不是**「内层回滚、外层继续」，而是整笔事务都提交不了。

### 实现位置

声明式事务的入口是 `TransactionInterceptor`，它把方法调用包装成一个「事务边界」，再委托给 `PlatformTransactionManager#getTransaction(TransactionDefinition)`。真正的传播逻辑在 `AbstractPlatformTransactionManager`：

```java
public final TransactionStatus getTransaction(TransactionDefinition definition) {
    Object transaction = doGetTransaction();
    // 已经有事务？
    if (isExistingTransaction(transaction)) {
        return handleExistingTransaction(definition, transaction, debugEnabled);
    }
    // 没有事务，按传播行为决定
    switch (definition.getPropagationBehavior()) {
        case PROPAGATION_MANDATORY:
            throw new IllegalTransactionStateException(...);
        case PROPAGATION_REQUIRED:
        case PROPAGATION_REQUIRES_NEW:
        case PROPAGATION_NESTED:
            return startTransaction(definition, transaction, debugEnabled, null);
        case PROPAGATION_SUPPORTS:
            return prepareTransactionStatus(definition, null, true, ...);
        case PROPAGATION_NOT_SUPPORTED:
            return prepareTransactionStatus(definition, null, true, ...);
        case PROPAGATION_NEVER:
            return prepareTransactionStatus(definition, null, true, ...);
        default:
            throw new IllegalArgumentException(...);
    }
}
```

已有事务时进入 `handleExistingTransaction`，四种分支：

- `REQUIRED` / `SUPPORTS` → 复用当前事务，返回 `TransactionStatus` 的 `newTransaction = false`
- `REQUIRES_NEW` → `suspend()` 当前事务 → `startTransaction(..., newTransaction=true)`
- `NESTED` → 若 `isNestedTransactionAllowed()` 且 `useSavepointForNestedTransaction()` 为真则创建 savepoint；否则退化为新建事务或抛 `NestedTransactionNotSupportedException`
- `MANDATORY` → 直接抛 `IllegalTransactionStateException`
- `NEVER` → 直接抛 `IllegalTransactionStateException`
- `NOT_SUPPORTED` → `suspend()` 后以非事务方式执行

另外，事务上下文本身是绑在 `TransactionSynchronizationManager` 的 `ThreadLocal` 上的，所以传播行为**只在同一个线程内生效**；跨线程、跨 `@Async` 都拿不到外层事务。

### 常见误用

**误用一：用 `REQUIRES_NEW` 记「失败日志」。** 想法是「业务事务回滚，但日志事务要保住」。方向对，但要清楚它额外占用连接，而且日志方法里如果还想读业务事务刚写的数据是读不到的。

**误用二：以为 `NESTED` 到处都能用。** NESTED 依赖 JDBC savepoint，官方明确说它「typically mapped onto JDBC savepoints, so it works only with JDBC resource transactions」，典型实现是 `DataSourceTransactionManager`。JTA 等不支持 savepoint 的资源下会退化或抛异常。

**误用三：在 `REQUIRED` 内层吞异常。** 这是生产上 `UnexpectedRollbackException` 的头号来源——代码以为 catch 住就没事，结果外层提交时炸。

**误用四：把 `@Transactional` 方法自调用。** 传播行为也是靠 AOP 代理实现的，自调用连代理都没经过，配置的传播行为同样不生效。

## 追问链

### Q1: REQUIRED 和 REQUIRES_NEW 的本质区别是什么？

一句话：`REQUIRED` 复用同一个物理事务，`REQUIRES_NEW` 新开一个独立的物理事务。

`REQUIRED` 下内外层共享同一条数据库连接和同一个物理事务，只是各自有独立的「逻辑事务作用域」，可以单独标记 rollback-only。所以内层写的数据外层能读到，内层的回滚也会牵连外层。

`REQUIRES_NEW` 下内层会先把外层挂起，再获取新连接开启自己的物理事务。两者互不影响：内层回滚不影响外层提交，外层回滚也不会撤销内层已经提交的数据；内层还读不到外层未提交的数据。

#### Q1.1: 挂起一个事务具体做了什么？

挂起由 `AbstractPlatformTransactionManager#suspend` 完成，核心是**把事务资源和当前线程解绑**，并保存到 `SuspendedResourcesHolder`：

```java
// DataSourceTransactionManager
protected Object doSuspend(Object transaction) {
    DataSourceTransactionManager txObject = (DataSourceTransactionManager) transaction;
    txObject.setConnectionHolder(null);
    return TransactionSynchronizationManager.unbindResource(obtainDataSource());
}
```

同时把 `TransactionSynchronizationManager` 上的同步器、当前事务名、隔离级别等上下文一并暂存，并置为「不活跃」状态。新事务在新连接上正常开启，`TransactionStatus` 里 `newTransaction = true`。

恢复时调用 `doResume`，把之前保存的连接持有者和同步器重新绑回线程。**关键点是：外层的连接持有者只是被解绑暂存，并没有被销毁**，所以它占用的连接在挂起期间依然算在本次请求头上——这就是 `REQUIRES_NEW` 会同时占用两条连接的原因。

##### Q1.1.1: 那挂起期间外层连接会归还给连接池吗？

不会主动归还。

`doSuspend` 做的是「从当前线程解绑」（`unbindResource`），把连接持有者交给 `SuspendedResourcesHolder` 保存。它并没有调用 `DataSourceUtils.releaseConnection`，所以连接不会被还给连接池——后续 `doResume` 还要把它重新绑回来继续用同一个事务。

因此 `REQUIRES_NEW` 期间，同一个线程实际上占着两条连接：一条是被挂起的外层连接（闲置但未归还），一条是内层新获取的连接。如果外层事务里又嵌套多个 `REQUIRES_NEW`，连接占用会线性增长。这正是「高并发下 `REQUIRES_NEW` 容易打满连接池」的底层原因，也是它和 `NESTED`（复用同一条连接，只建 savepoint）在资源成本上的根本差别。

### Q2: NESTED 和 REQUIRES_NEW 都能「内层回滚不影响外层」，区别在哪？

区别在于**是一个物理事务还是两个**。

`NESTED` 复用外层同一个物理事务，只是在事务内部创建了一个 JDBC savepoint。内层出错时执行 `ROLLBACK TO SAVEPOINT`，只撤销 savepoint 之后的改动，外层可以在同一个物理事务里继续执行并最终提交。因为共用一条连接，内层能读到外层未提交的数据。

`REQUIRES_NEW` 是两个物理事务，走两条连接。内层回滚是真正的 `ROLLBACK`，与外围事务无关。

还有一个反向差异必须记住：**外层回滚时，`NESTED` 的内层改动会被一起回滚**（本来就是同一个物理事务），而 `REQUIRES_NEW` 的内层如果已经提交，外层回滚也带不走它。这是选择两者时最重要的判断依据：

- 内层是「整体业务的一部分，外层失败它也必须失败」→ `NESTED`
- 内层是「无论外层成败都必须留下」的独立操作，比如审计日志 → `REQUIRES_NEW`

#### Q2.1: NESTED 有什么使用限制？

有两条硬限制。

**第一，依赖 JDBC savepoint。** Spring 官方文档写明 `PROPAGATION_NESTED` 通常映射到 JDBC savepoint，因此只适用于 JDBC 资源事务，典型实现是 `DataSourceTransactionManager`。它要求 `nestedTransactionAllowed` 为真并且底层资源的 `useSavepointForNestedTransaction()` 为真。`DataSourceTransactionManager` 默认开启嵌套支持；换成不支持 savepoint 的 JTA 事务管理器就会出问题。

**第二，需要数据库支持 savepoint。** MySQL 的 InnoDB 支持 savepoint，但并非所有存储引擎/数据库都支持。

还有一个容易忽略的行为：如果当前**没有**外层事务，`NESTED` 会直接新建一个事务，此时它的行为和 `REQUIRED` 完全一样，savepoint 根本没被用到。

##### Q2.1.1: 那 JTA 环境下 NESTED 会怎样？

JTA 的 `JtaTransactionManager` 不支持嵌套事务（它没有 savepoint 的概念），`useSavepointForNestedTransaction()` 返回 false。此时有两种可能的结果，取决于配置：

- 如果 `nestedTransactionAllowed` 为 false（JTA 场景的默认），抛出 `NestedTransactionNotSupportedException`
- 如果显式设置 `setNestedTransactionAllowed(true)`，`AbstractPlatformTransactionManager` 在无法用 savepoint 时会**退化为 `REQUIRES_NEW`**——挂起当前事务、新建一个独立事务

这个「静默退化」很危险：代码写的是 `NESTED`，实际执行的是 `REQUIRES_NEW`，于是「外层回滚会带走内层」这个前提不再成立，语义悄悄变了。所以跨事务管理器（本地 JDBC 换 JTA）迁移时，必须重新审视所有用到 `NESTED` 的地方。

### Q3: 为什么 REQUIRED 内层抛异常被外层 catch 住后，提交会报 UnexpectedRollbackException？

因为内层和外层**共享同一个物理事务**，而内层抛出异常时，`TransactionInterceptor` 会调用 `rollback`，把整个事务标记为 **rollback-only**（`TransactionStatus.setRollbackOnly()`）。

此后异常被外层 catch，外层方法正常返回，代理决定提交。但提交前它会检查全局的 rollback-only 标记，发现事务已被标记为「只能回滚」，于是拒绝提交并抛：

```
org.springframework.transaction.UnexpectedRollbackException:
    Transaction silently rolled back because it has been marked as rollback-only
```

注意此时事务**确实回滚了**，`UnexpectedRollbackException` 只是一个「你以为要提交，其实回滚了」的告警。官方文档特别强调这是有意为之：调用方不能在被静默回滚的情况下误以为提交成功了。

正确做法是让内层异常继续往外抛，而不是吞掉；确实需要记录日志又不中断流程时，用 `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` 显式表达「要回滚」，而不是靠 catch 制造假象。

#### Q3.1: 这个现象恰好印证了 REQUIRED 与 REQUIRES_NEW 的什么差异？

它印证了 `REQUIRED` 的「逻辑事务作用域可以独立标记 rollback-only，但共享的物理事务无法独立提交」。

Spring 的设计是：每个逻辑作用域都能单独决定「我失败了」，但 `REQUIRED` 把它们映射到同一个物理事务上，于是「内层标记失败」直接污染了外层的提交结果。反过来，如果内层用 `REQUIRES_NEW`，它有自己独立的 `TransactionStatus` 和物理事务，内层回滚只影响内层，外层 catch 后可以干净地提交——**不会**出现 `UnexpectedRollbackException`。

所以看到 `UnexpectedRollbackException`，基本可以直接判定：某个内层 `REQUIRED` 方法抛了异常并被外层吞掉了。这也是排查这类线上报错最快的入手点。

### Q4: MANDATORY 和 NEVER 这两个几乎用不到的传播行为，有什么实际用途？

它们本质上是**契约声明**，用来把「调用前提」写进代码，而不是靠注释约定。

`MANDATORY` 表示「我必须在已有事务里运行」。如果被一个没有事务的链路调用，会立刻抛 `IllegalTransactionStateException`。典型场景是底层 DAO 或某个必须与调用方同生共死的方法——它不负责开启事务，但绝不允许在事务外裸跑，否则数据一致性无法保证。

`NEVER` 表示「我绝不能在事务里运行」。如果调用链上有事务存在，直接抛异常。典型场景是**不做数据库操作、又很耗时的操作**，比如调用外部 HTTP 接口、发消息、写文件：把它们放进事务会长时间占用连接和锁。用 `NEVER` 可以强制保证这类方法永远在事务外被调用。

两者和 `SUPPORTS`、`NOT_SUPPORTED` 的差别在于**出错方式**：`SUPPORTS`/`NOT_SUPPORTED` 会默默迁就现状，`MANDATORY`/`NEVER` 会直接失败。这正是「契约」和「容忍」的区别。

#### Q4.1: 怎么在代码上强制保证某个方法一定在事务里执行？

`MANDATORY` 就是一个自检开关。给方法加：

```java
@Transactional(propagation = Propagation.MANDATORY)
public void mustRunInTransaction() {
    // 没有外层事务时直接抛异常
}
```

好处是**失败得很早、很明确**：一旦有人从非事务入口调它，启动后第一次调用就会抛 `IllegalTransactionStateException`，而不是等数据错了才发现。

但要注意它的两个边界：

- 它同样依赖 AOP 代理，自调用不生效——`this.mustRunInTransaction()` 不会触发检查
- 它检查的是**当前线程**的事务上下文（`TransactionSynchronizationManager` 的 `ThreadLocal`），跨线程调用即使外层有事务也检测不到

更严格的做法是在架构层面用 ArchUnit 写下规则（例如「DAO 层方法必须有事务」），把约定变成可以静态校验的约束，而不是仅靠运行期抛异常。

## 常见坑

- **说「REQUIRES_NEW 和 NESTED 是一回事」** —— 完全不是。`REQUIRES_NEW` 是两个物理事务、两条连接；`NESTED` 是一个物理事务内的 savepoint，共用一条连接。外层回滚时 NESTED 的内层会被一起回滚，`REQUIRES_NEW` 已提交的部分则不受影响
- **说「REQUIRES_NEW 里能读到外层还没提交的数据」** —— 读不到。两个独立事务、两条连接，且 InnoDB 默认隔离级别下更看不到
- **说「NESTED 和 REQUIRES_NEW 一样都只回滚内层、不影响外层」** —— 只对「内层失败」这一半成立。外层失败时 NESTED 的内层改动会一起没，这是两者最关键的差异
- **说「REQUIRED 内层抛异常，外层 catch 住就能正常提交」** —— 共享事务已被标记 rollback-only，提交时抛 `UnexpectedRollbackException`，整笔事务其实回滚了
- **说「SUPPORTS 没有事务时会新建事务」** —— 不会，`SUPPORTS` 在没有事务时就以非事务方式执行
- **说「NOT_SUPPORTED 里的写操作会跟着外层回滚」** —— 不会。它挂起外层事务后以非事务方式执行，写操作是自动提交的，外层回滚带不走它
- **说「MANDATORY 没有事务时会新建一个」** —— 不会，直接抛 `IllegalTransactionStateException`，这正是它存在的意义
- **说「NEVER 检测到事务会挂起它然后非事务执行」** —— 错。挂起再非事务执行是 `NOT_SUPPORTED`；`NEVER` 是直接抛异常
- **说「NESTED 可以直接换成 REQUIRES_NEW 兼容所有数据库」** —— 语义不等价（外层回滚行为不同），而且 `REQUIRES_NEW` 多占一条连接，高并发下反而更容易出问题
- **以为传播行为是 Java 语言特性、跨线程也有效** —— 事务上下文绑在 `ThreadLocal` 上，子线程/`@Async` 拿不到外层事务
- **忘了传播行为也依赖 AOP 代理** —— 自调用时连代理都没经过，配了传播行为也不会生效

## 加分点

- 能准确指出传播逻辑的入口是 **`AbstractPlatformTransactionManager#getTransaction`**，已有事务时转入 `handleExistingTransaction`，并说出 `REQUIRES_NEW` 对应 `suspend()` + `startTransaction(newTransaction=true)` 这两步
- 知道 `NESTED` 的底层是 **`java.sql.Savepoint`**，由 `DataSourceTransactionManager` 的 `createAndHoldSavepoint` / `releaseHeldSavepoint` 管理，且 `DataSourceTransactionManager` 默认 `nestedTransactionAllowed = true`
- 能说出 `UnexpectedRollbackException` 的完整成因，并知道此时事务**已经回滚**，异常只是「提交意图与结果不符」的告警
- 提到 `REQUIRES_NEW` 的连接成本机制：挂起只是 `unbindResource` 把连接持有者暂存，**并不归还连接池**，所以同一线程会同时占用两条连接——这比笼统地说「要多一条连接」更有说服力
- 知道 `TransactionSynchronizationManager` 用 `ThreadLocal` 保存事务上下文，因此传播行为只在同线程内成立
- 能指出 `NESTED` 在没有外层事务时**等价于 `REQUIRED`**，savepoint 根本不会被用到
- 知道在支持嵌套但资源不支持 savepoint（如 JTA）时，`setNestedTransactionAllowed(true)` 会让 `NESTED` **静默退化为 `REQUIRES_NEW`**，语义悄悄改变
- 能把「连接占用 / 锁持有时间」和「高并发下先出问题的是连接池」联系起来，说明为什么事务边界和传播行为值得在核心链路上反复推敲
- 知道 `MANDATORY` 在架构层面可以当自检契约用，但受自调用和跨线程两个限制

## 版本差异

| 版本 | 差异 |
|---|---|
| Spring 2.0+ | `@Transactional` 与七种传播行为语义定型，`AbstractPlatformTransactionManager` 的分支逻辑延续至今 |
| Spring 4.x | `DataSourceTransactionManager` 的 savepoint 支持稳定，`NESTED` 依赖 JDBC savepoint 的限制被官方文档明确写出 |
| Spring 5.0 | 引入响应式事务管理（`ReactiveTransactionManager` / `TransactionalOperator`），传播语义改由 Reactor 的 Context 传递，不再依赖 `ThreadLocal` |
| Spring 5.x | 声明式事务默认基于 AOP 代理；Spring Boot 2.x 起 AOP 默认走 CGLIB，接口上的 `@Transactional` 可能因此不生效，进而导致传播行为配置也不生效 |
| Spring 6 / Boot 3 | 命名空间迁移到 `jakarta.*`；AOT 场景下代理改为构建期生成，但七种传播行为的语义与实现位置未变 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: Spring 事务默认的传播行为是哪一个？
    options:
      A: REQUIRES_NEW
      B: REQUIRED
      C: NESTED
      D: SUPPORTS
    answer: B
    analysis: REQUIRED 是默认值，语义是「有事务就加入，没有就新建」。注意 REQUIRES_NEW 也总是有事务，但它会挂起当前事务并新开一个独立的物理事务，和 REQUIRED 的共享物理事务完全不同。

  - type: MULTI
    stem: 关于 REQUIRES_NEW，下列说法正确的有？
    options:
      A: 它会挂起当前事务并新建一个独立的物理事务
      B: 内层事务回滚不会影响外层事务
      C: 它读不到外层尚未提交的数据
      D: 它和外层复用同一条数据库连接
    answer: ABC
    analysis: "D 错误，REQUIRES_NEW 需要额外获取一条连接：外层连接在挂起期间只是被解绑暂存、并未归还连接池，因此同一线程会同时占用两条连接。这也是它在高并发下容易打满连接池的原因。"

  - type: JUDGE
    stem: NESTED 和 REQUIRES_NEW 都会开启一个独立于外层的物理事务。
    answer: F
    analysis: 只有 REQUIRES_NEW 是独立的物理事务。NESTED 复用外层同一个物理事务，只是在其中创建 JDBC savepoint。因此外层回滚会带上 NESTED 的内层改动，而 REQUIRES_NEW 已提交的内层数据不受外层回滚影响。

  - type: CLOZE
    stem: |
      让内层事务在同一个物理事务内回滚到保存点，而不影响外层继续执行：
      ```java
      @Transactional(propagation = Propagation.{{1}})
      public void inner() {
          // 内部基于 JDBC {{2}} 实现部分回滚
      }
      ```
    blanks:
      - ["NESTED"]
      - ["savepoint", "Savepoint", "保存点"]
    analysis: NESTED 在当前物理事务内创建 JDBC savepoint，内层出错时执行 ROLLBACK TO SAVEPOINT，只撤销保存点之后的改动，外层仍可继续提交。它复用同一条连接，且需要资源支持 savepoint（官方典型实现是 DataSourceTransactionManager）。
    difficulty: 3
````
