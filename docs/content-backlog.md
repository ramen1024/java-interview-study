# 待编写知识点清单

规划总量约 65 张卡片，当前已完成 **17 张**（✅）。
下面按模块列出剩余项，标出优先级，供后续继续编写。

优先级说明：**P0** 面试必问、**P1** 高频加分、**P2** 有深度但非必问。

---

## 01 Java 基础与集合

已完成 ✅

| 卡片 | slug |
|---|---|
| HashMap 底层结构与 put 流程 | `hashmap-internals` |
| HashMap 扩容机制与负载因子 | `hashmap-resize` |
| HashMap 为什么线程不安全 | `hashmap-thread-unsafe` |
| ConcurrentHashMap 实现原理 | `concurrenthashmap` |

待编写

| 优先级 | 计划知识点 | 建议 slug | 重点内容 |
|---|---|---|---|
| P0 | ArrayList 扩容与 LinkedList 对比 | `arraylist-vs-linkedlist` | 1.5 倍扩容、`Arrays.copyOf`、随机访问 vs 增删的取舍、为什么实际几乎总选 ArrayList |
| P0 | String 不可变、常量池与 intern | `string-immutable-pool` | 不可变的三个理由（缓存 hash、线程安全、安全）、`intern` 的行为与版本差异 |
| P1 | equals/hashCode 契约 | `equals-hashcode-contract` | 契约四条、为什么重写 equals 必须重写 hashCode、在集合里的后果 |
| P1 | 泛型与类型擦除 | `generic-type-erasure` | 擦除的动机（兼容性）、`<? extends>` / `<? super>` 的 PECS 原则、桥接方法 |
| P1 | 反射原理与应用 | `reflection-internals` | `Class` 对象来源、`setAccessible` 与模块化限制、性能开销与为什么 Spring 还用它 |
| P1 | 异常体系与 finally 陷阱 | `exception-and-finally` | 受检 vs 非受检的设计取舍、`finally` 里 return 吞异常、try-with-resources 的抑制异常 |
| P1 | BIO / NIO / AIO 与零拷贝 | `io-nio-zero-copy` | 缓冲区与通道、`Selector` 多路复用、`sendfile` 与 `transferTo` 的零拷贝路径 |
| P2 | 序列化与 serialVersionUID | `serialization` | 兼容性规则、为什么不建议用 JDK 序列化、`transient` |
| P2 | Lambda / Stream / Optional | `stream-and-lambda` | 惰性求值、并行流的坑（`commonPool`、`forEach` 顺序）、为什么 `Optional` 不该做字段 |
| P1 | Java 17 / 21 新特性 | `java-17-21-features` | Record、密封类、模式匹配、文本块、虚拟线程概览 |
| P2 | 自动装箱与 Integer 缓存池 | `autoboxing-cache` | `-128~127` 缓存、`==` 比较的陷阱、装箱的性能开销 |
| P2 | static / final / 初始化顺序 | `initialization-order` | 类初始化时机、静态块与实例块顺序、final 的内存语义 |

---

## 02 并发编程

已完成 ✅

| 卡片 | slug |
|---|---|
| 线程池七个参数与执行流程 | `thread-pool-parameters` |
| 为什么禁止用 Executors | `why-not-executors` |

待编写

| 优先级 | 计划知识点 | 建议 slug | 重点内容 |
|---|---|---|---|
| P0 | JMM 与 happens-before | `jmm-happens-before` | 主内存与工作内存、重排序、happens-before 八条规则 |
| P0 | volatile 原理与三大特性 | `volatile-visibility` | 可见性与有序性的实现（内存屏障）、为什么不保证原子性、双重检查锁为什么需要它 |
| P0 | synchronized 原理与锁升级 | `synchronized-lock-upgrade` | 对象头 Mark Word、偏向锁→轻量级锁→重量级锁、锁粗化与锁消除 |
| P0 | AQS 原理与 state 设计 | `aqs-principle` | `state` + CLH 变体队列、独占与共享模式、`tryAcquire` 模板方法 |
| P1 | ReentrantLock 与公平 / 非公平 | `reentrantlock-fairness` | 公平锁的代价、`tryLock` 与超时、与 synchronized 的对比 |
| P1 | CAS 与 ABA 问题 | `cas-and-aba` | 无锁编程、自旋开销、`AtomicStampedReference` 解决 ABA |
| P1 | ThreadLocal 原理与内存泄漏 | `threadlocal-memory-leak` | `ThreadLocalMap` 的弱引用 key 与强引用 value、为什么线程池下必须 `remove` |
| P1 | 并发容器与阻塞队列 | `concurrent-collections` | `CopyOnWriteArrayList` 的适用场景、`ArrayBlockingQueue` vs `LinkedBlockingQueue` |
| P1 | 同步工具类 | `synchronizers` | `CountDownLatch`（一次性）、`CyclicBarrier`（可重用）、`Semaphore`（限流）的差别 |
| P1 | CompletableFuture 异步编排 | `completablefuture` | 任务编排与异常处理、默认线程池的坑、`thenApply` vs `thenCompose` |
| P2 | 死锁的产生、检测与避免 | `deadlock` | 四个必要条件、`jstack` 自动检测、固定加锁顺序与超时 |
| P1 | 虚拟线程（Java 21） | `virtual-threads` | 载体线程与挂载、适合 IO 密集、为什么不能池化、要用信号量限制下游 |
| P2 | 线程状态与中断机制 | `thread-state-interrupt` | 六种状态、`interrupt` 只是设标志位、如何正确响应中断 |

---

## 03 JVM 与调优

已完成 ✅

| 卡片 | slug | 备注 |
|---|---|---|
| JVM 运行时内存结构 | `runtime-memory-layout` | |
| 垃圾回收算法与分代收集 | `garbage-collection-algorithms` | 已合并原计划的「分代晋升」「三色标记与读写屏障」 |
| 线上 OOM 与 CPU 100% 排查 | `oom-troubleshooting` | 已合并原计划的「CPU 飙高排查」「内存泄漏排查」 |

待编写

| 优先级 | 计划知识点 | 建议 slug | 重点内容 |
|---|---|---|---|
| P0 | 类加载过程与双亲委派 | `class-loading-parent-delegation` | 加载/验证/准备/解析/初始化、双亲委派的作用（安全 + 避免重复加载） |
| P1 | 打破双亲委派的场景 | `break-parent-delegation` | Tomcat 的 WebAppClassLoader、SPI 与线程上下文类加载器、热部署 |
| P0 | 垃圾判定与四种引用 | `gc-roots-and-references` | 可达性分析、GC Roots 有哪些、强/软/弱/虚引用的回收时机与用途 |
| P1 | G1 收集器原理 | `g1-collector` | Region 布局、SATB、`MaxGCPauseMillis` 的预测模型、Humongous 对象 |
| P1 | ZGC 与低延迟收集器 | `zgc` | 染色指针 + 读屏障、并发移动对象、停顿与堆大小无关 |
| P1 | GC 参数与调优思路 | `gc-tuning` | 常见参数、如何看 GC 日志、调优顺序（先排除泄漏，再调容量与目标） |
| P2 | JIT、逃逸分析与锁消除 | `jit-escape-analysis` | 分层编译、方法内联、标量替换、为什么"对象都在堆上"不严谨 |
| P2 | 对象创建过程与内存布局 | `object-layout` | 对象头三部分、指针压缩、TLAB 分配、栈上分配 |

---

## 04 框架与中间件

### Spring

已完成 ✅

| 卡片 | slug |
|---|---|
| Bean 生命周期与 AOP 代理时机 | `bean-lifecycle` |
| 循环依赖与三级缓存 | `circular-dependency` |
| 事务失效场景与排查 | `transaction-failure-scenarios` |

待编写

| 优先级 | 计划知识点 | 建议 slug | 重点内容 |
|---|---|---|---|
| P0 | AOP 原理与代理选择 | `aop-proxy` | JDK 动态代理 vs CGLIB、切点匹配、`@Aspect` 的代理过程、为什么自调用失效 |
| P0 | 事务传播行为 | `transaction-propagation` | 七种传播行为、`REQUIRED` 与 `REQUIRES_NEW` 的物理事务差异、`NESTED` 的 savepoint |
| P1 | Spring Boot 自动配置原理 | `auto-configuration` | `@EnableAutoConfiguration`、`spring.factories` → `AutoConfiguration.imports`、条件注解 |
| P1 | Spring MVC 请求处理流程 | `mvc-request-flow` | `DispatcherServlet` 九大组件、参数解析与返回值处理、拦截器与过滤器的区别 |
| P2 | Bean 作用域与单例线程安全 | `bean-scope-thread-safety` | 五种作用域、单例 Bean 里的可变字段是线程安全问题、`@Scope("prototype")` 的注入陷阱 |

### MySQL

已完成 ✅

| 卡片 | slug | 备注 |
|---|---|---|
| 索引与 B+ 树、聚簇索引、回表、覆盖索引、最左前缀、ICP | `index-b-plus-tree` | 已合并原计划的「最左前缀/覆盖索引/索引下推」「explain 与慢查询优化」（含 EXPLAIN 关键列解读） |
| 隔离级别与 MVCC | `mvcc-and-isolation-levels` | |

待编写

| 优先级 | 计划知识点 | 建议 slug | 重点内容 |
|---|---|---|---|
| P0 | 行锁、间隙锁与临键锁 | `innodb-locks` | 锁的类型与加锁规则、RR 下间隙锁如何防幻读、死锁分析与排查 |
| P1 | 分库分表与主从复制 | `sharding-replication` | 分片键选择、跨片查询与分布式事务、主从延迟与读写分离 |

### Redis

已完成 ✅

| 卡片 | slug |
|---|---|
| 缓存穿透、击穿、雪崩 | `cache-penetration-breakdown-avalanche` |
| 缓存与数据库的一致性 | `cache-consistency` |
| 分布式锁的实现与坑 | `distributed-lock` |

待编写

| 优先级 | 计划知识点 | 建议 slug | 重点内容 |
|---|---|---|---|
| P0 | Redis 数据结构与底层编码 | `redis-data-structures` | SDS、ziplist / listpack、skiplist、渐进式 rehash、各类型的编码转换阈值 |
| P1 | 持久化 RDB 与 AOF | `redis-persistence` | RDB fork 与 COW、AOF 三种刷盘策略、混合持久化、重启恢复顺序 |
| P1 | 过期删除与内存淘汰 | `redis-expiration-eviction` | 惰性删除 + 定期删除、八种淘汰策略、为什么不用 LRU 而用近似 LRU |
| P1 | 大 key 与热 key | `redis-big-hot-key` | 危害与探测方法、拆分与本地缓存、`UNLINK` 异步删除 |

---

## 未纳入规划的模块（二期）

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
导入器会把错误带文件名报出来，写错不会静默进库。
