# 知识点清单

**当前已完成 61 张卡片 / 509 条追问 / 250 道自测题**（原规划约 65 张，已基本达成）。

本文件现在记录**已覆盖的知识面**与**尚未纳入的模块**。
新增卡片前请先读 [content-spec.md](content-spec.md)（格式契约），
写完跑一次 `mvn test -Dtest=ContentCardValidationTest` 比启动应用看导入日志快得多。

---

## 01 Java 基础与集合（16 张）

| 卡片 | slug | 备注 |
|---|---|---|
| HashMap 底层结构与 put 流程 | `hashmap-internals` | |
| HashMap 扩容机制与负载因子 | `hashmap-resize` | |
| HashMap 为什么线程不安全 | `hashmap-thread-unsafe` | |
| ConcurrentHashMap 实现原理 | `concurrenthashmap` | |
| ArrayList 扩容与 LinkedList 对比 | `arraylist-vs-linkedlist` | |
| String 不可变、常量池与 intern | `string-immutable-pool` | |
| equals / hashCode 契约 | `equals-hashcode-contract` | |
| 泛型与类型擦除 | `generic-type-erasure` | |
| 反射原理与应用 | `reflection-internals` | |
| 异常体系与 finally 陷阱 | `exception-and-finally` | |
| BIO / NIO / AIO 与零拷贝 | `io-nio-zero-copy` | |
| Java 17 / 21 新特性 | `java-17-21-features` | |
| 序列化与 serialVersionUID | `serialization` | |
| Lambda / Stream / Optional | `stream-and-lambda` | |
| 自动装箱与 Integer 缓存池 | `autoboxing-cache` | |
| static / final / 初始化顺序 | `initialization-order` | |

---

## 02 并发编程（15 张）

| 卡片 | slug | 备注 |
|---|---|---|
| 线程池七个参数与执行流程 | `thread-pool-parameters` | |
| 为什么禁止用 Executors | `why-not-executors` | |
| JMM 与 happens-before | `jmm-happens-before` | |
| volatile 原理与三大特性 | `volatile-visibility` | |
| synchronized 原理与锁升级 | `synchronized-lock-upgrade` | 含偏向锁在 JDK 15 禁用、JDK 18 移除的版本差异 |
| AQS 原理与 state 设计 | `aqs-principle` | |
| ReentrantLock 与公平/非公平 | `reentrantlock-fairness` | |
| CAS 与 ABA 问题 | `cas-and-aba` | |
| ThreadLocal 原理与内存泄漏 | `threadlocal-memory-leak` | |
| 并发容器与阻塞队列 | `concurrent-collections` | |
| 同步工具类 | `synchronizers` | |
| CompletableFuture 异步编排 | `completablefuture` | |
| 虚拟线程（Java 21） | `virtual-threads` | 含 JEP 491（JDK 24）解除 synchronized pinning |
| 死锁的产生、检测与避免 | `deadlock` | |
| 线程状态与中断机制 | `thread-state-interrupt` | |

---

## 03 JVM 与调优（11 张）

| 卡片 | slug | 备注 |
|---|---|---|
| JVM 运行时内存结构 | `runtime-memory-layout` | |
| 垃圾回收算法与分代收集 | `garbage-collection-algorithms` | 已合并原计划的「分代晋升」「三色标记与读写屏障」 |
| 线上 OOM 与 CPU 100% 排查 | `oom-troubleshooting` | 已合并原计划的「CPU 飙高排查」「内存泄漏排查」 |
| 类加载过程与双亲委派 | `class-loading-parent-delegation` | |
| 打破双亲委派的场景 | `break-parent-delegation` | |
| 垃圾判定与四种引用 | `gc-roots-and-references` | |
| G1 收集器原理 | `g1-collector` | |
| ZGC 与低延迟收集器 | `zgc` | |
| GC 参数与调优思路 | `gc-tuning` | |
| JIT、逃逸分析与锁消除 | `jit-escape-analysis` | |
| 对象创建过程与内存布局 | `object-layout` | |

---

## 04 框架与中间件（19 张）

### Spring（8 张）

| 卡片 | slug |
|---|---|
| Bean 生命周期与 AOP 代理时机 | `bean-lifecycle` |
| 循环依赖与三级缓存 | `circular-dependency` |
| 事务失效场景与排查 | `transaction-failure-scenarios` |
| AOP 原理与代理选择 | `aop-proxy` |
| 事务传播行为 | `transaction-propagation` |
| Spring Boot 自动配置原理 | `auto-configuration` |
| Spring MVC 请求处理流程 | `mvc-request-flow` |
| Bean 作用域与单例线程安全 | `bean-scope-thread-safety` |

### MySQL（4 张）

| 卡片 | slug | 备注 |
|---|---|---|
| 索引与 B+ 树、聚簇索引、回表、覆盖索引、最左前缀、ICP | `index-b-plus-tree` | 已合并原计划的「最左前缀/覆盖索引/索引下推」「explain 与慢查询优化」 |
| 隔离级别与 MVCC | `mvcc-and-isolation-levels` | |
| 行锁、间隙锁与临键锁 | `innodb-locks` | |
| 分库分表与主从复制 | `sharding-replication` | |

### Redis（7 张）

| 卡片 | slug |
|---|---|
| 缓存穿透、击穿、雪崩 | `cache-penetration-breakdown-avalanche` |
| 缓存与数据库的一致性 | `cache-consistency` |
| 分布式锁的实现与坑 | `distributed-lock` |
| Redis 数据结构与底层编码 | `redis-data-structures` |
| 持久化 RDB 与 AOF | `redis-persistence` |
| 过期删除与内存淘汰 | `redis-expiration-eviction` |
| 大 key 与热 key | `redis-big-hot-key` |

---

## 尚未纳入的模块（二期）

这些方向面试也常问，但不属于本项目当前的四块内容主线：

| 模块 | 说明 |
|---|---|
| 消息队列 | Kafka / RocketMQ 的顺序、幂等、事务消息、堆积处理 |
| 分布式与微服务 | CAP、分布式事务（2PC/TCC/Saga）、注册中心、RPC、限流熔断 |
| 系统设计 | 秒杀、短链、IM、订单、Feed 流 |
| 网络与操作系统 | TCP 三次握手/四次挥手、HTTP/2/3、IO 多路复用、零拷贝 |
| 设计模式 | 单例、工厂、策略、责任链、观察者（及在 Spring 中的体现） |
| 工程化 | Maven、Git、Docker/K8s、CI/CD、Linux 排查 |
| 算法与数据结构 | 热题 100/150、手写代码题 |
| 项目与软技能 | STAR 模板、项目难点包装、HR 常见问题 |

---

## 编写建议

内容量不是目标，**每张卡片的追问链才是**。写新卡片时优先保证：

1. **追问链至少 3 层**，且下一问必须由上一问的答案自然引出，
   而不是并列的另一道题
2. **常见坑要写出「错误说法」本身**，而不是泛泛的「注意细节」。
   面试官听得出的差别在于你说的是「1.8 用头插法」
   还是「1.7 才是头插法，1.8 改成尾插修复了并发扩容成环」
3. **禁止编造参数和数字**。不确定的宁可写「大约」，
   也不要给一个精确的错值
4. **版本差异必须查证**，写明来源版本

格式与校验规则见 [content-spec.md](content-spec.md)。
导入器会把错误带文件名报出来，写错不会静默进库；
`ContentCardValidationTest` 还会额外守住上面第 1 条（追问链层级）与
「至少 2 道自测题且不全为单选」这两条质量底线。
