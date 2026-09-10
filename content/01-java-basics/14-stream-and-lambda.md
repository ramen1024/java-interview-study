---
slug: stream-and-lambda
title: Stream 的惰性求值是怎么回事？并行流有什么坑？
module: java-basics
tags: [Stream, Lambda, 并行流, Optional]
difficulty: 2
frequency: 2
related:
  - slug: java-17-21-features
    type: RELATED
  - slug: completablefuture
    type: CONTRAST
  - slug: concurrent-collections
    type: RELATED
---

## 电梯版回答

Stream 是一条惰性管道：中间操作（`filter`、`map`、`sorted`、`peek` 等）只记录要做什么并返回一个新的 Stream，不会执行；只有终端操作（`collect`、`forEach`、`reduce`、`count` 等）才会从数据源拉取元素，把整条流水线跑一遍。所以没有终端操作就什么都不会发生，`peek` 里的打印也不会输出；而且一条 Stream 只能消费一次，重复使用抛 `IllegalStateException`。并行流 `parallelStream()` 默认使用 JVM 全局的 `ForkJoinPool.commonPool()`，它的并行度默认是 CPU 核数减一，并且和 `CompletableFuture` 的默认异步任务共用，有人在里面阻塞就会互相拖累。并行下 `forEach` 不保证顺序，要顺序得用 `forEachOrdered`；`findFirst` 必须顾及遇到顺序，`findAny` 不需要因此通常更快。数据量小、数据源切分代价高、有状态 lambda、IO 密集这几类场景不适合并行。另外 `Collectors.toMap` 遇到重复 key 会抛异常，必须给合并函数；`Optional` 只该做方法返回值，不要做字段和参数。

## 展开讲解

### 中间操作是惰性的，终端操作触发求值

| 类别 | 代表方法 | 返回 | 是否立即执行 |
|---|---|---|---|
| 中间操作 | `filter`、`map`、`flatMap`、`peek`、`distinct`、`sorted`、`limit`、`skip`、`takeWhile`、`dropWhile` | `Stream<T>` | 否，只构建流水线 |
| 终端操作 | `forEach`、`forEachOrdered`、`collect`、`reduce`、`count`、`min`、`max`、`anyMatch`、`allMatch`、`noneMatch`、`findFirst`、`findAny`、`toArray` | 非 Stream（void / 值 / 集合） | 是，触发整条流水线 |

```java
Stream.of("a", "bb", "ccc")
    .filter(s -> {
        System.out.println("filter " + s);
        return s.length() > 1;
    })
    .map(s -> {
        System.out.println("map " + s);
        return s.toUpperCase();
    });
// 什么都不输出，因为缺终端操作
```

补上 `.collect(Collectors.toList())` 之后才会看到输出。而且遍历是「垂直」的：每个元素依次走完 `filter` → `map` → …… 直到被终端消费或被短路截断，而不是先把所有元素过一遍 `filter` 再过一遍 `map`。

中间操作还可以分两类：

- **无状态**：`filter`、`map`、`flatMap`、`peek`，逐个元素处理，不需要记住之前看过什么。
- **有状态**：`distinct`、`sorted`、`limit`、`skip`，需要缓冲或记录状态。`sorted` 和 `distinct` 是明显的屏障，必须等上游全部（或足够多）元素到达才能继续。

`limit`、`takeWhile`、`anyMatch`、`findFirst` 这类是**短路操作**，可以让上游提前停止，这也是无限流能工作的前提：

```java
Stream.iterate(1, n -> n + 1)
    .filter(n -> n % 2 == 0)
    .limit(5)
    .forEach(System.out::println);
```

### Stream 只能消费一次

`AbstractPipeline` 里维护了管道是否已被消费的状态，第二次执行终端操作会抛：

```
java.lang.IllegalStateException: stream has already been operated upon or closed
```

要反复遍历，就保存数据源（`List`）或用一个 `Supplier<Stream<T>>`，每次调用重新生成一条流。另外 `Stream` 实现了 `AutoCloseable`：基于集合的流不关也没事，但 `Files.lines`、`Files.list`、`Files.walk` 这些持有文件句柄的流必须用 try-with-resources 关闭。

### 并行流默认用 commonPool

`collection.parallelStream()` 和 `stream().parallel()` 产生并行流；在同一个管道上后调用的 `parallel()` / `sequential()` 生效。

默认使用的线程池是 `ForkJoinPool.commonPool()`，它是整个 JVM 共享的一个池：

- 默认并行度是 `Runtime.getRuntime().availableProcessors() - 1`（至少为 1），可用系统属性 `java.util.concurrent.ForkJoinPool.common.parallelism` 覆盖，另有 `common.threadFactory`、`common.exceptionHandler`。
- 它同时被 `CompletableFuture` 不指定 Executor 的异步任务、以及其他并行流共用。线程被阻塞住就等于从公共池里扣掉了并发度。
- `ForkJoinPool` 不会为普通阻塞自动补线程，只有 `ForkJoinPool.ManagedBlocker` / `managedBlock` 这类受管阻塞才可能得到补偿。

如果确实要在并行流里做阻塞操作（HTTP 调用、JDBC 查询、文件 IO），正确做法是提交到自己的 `ForkJoinPool`，让这条并行流水线跑在私有池的 worker 上：

```java
ForkJoinPool pool = new ForkJoinPool(8);
try {
    List<Result> results = pool.submit(
            () -> ids.parallelStream()
                .map(this::callRemote)
                .collect(Collectors.toList())
        )
        .get();
} finally {
    pool.shutdown();
}
```

### 顺序：forEach / forEachOrdered、findFirst / findAny

- 并行流上 `forEach` 不保证遇到顺序，`forEachOrdered` 保证顺序（代价是并行结果要按序汇合，可能抵消并行收益）。
- `findFirst` 即使并行也必须尊重遇到顺序，要等前面元素确定没有匹配，因而有协调开销；`findAny` 只要能拿到任意一个匹配就返回，实现自由度高、通常更快，但结果不确定也不可复现。顺序流里两者都返回第一个匹配，但只有 `findFirst` 保证这一点。
- `collect(Collectors.toList())` 即使在并行下也保持顺序，因为有序归约会按序合并。所以「并行会打乱顺序」只针对无序终端操作；确实不需要顺序时，`unordered()` 能让 `distinct`、`limit` 少做协调。

### 什么情况不该用并行

- **数据量小**：拆分、调度、合并都有固定开销，元素只有几十上百个时并行通常更慢。
- **数据源切分代价高**：`ArrayList`、数组、`IntStream.range` 支持按索引 O(1) 二分，切分很好；`LinkedList`、`HashSet`、`TreeMap`、`Files.lines`、迭代器式数据源的 `trySplit` 效果差，甚至退化成单线程。
- **有状态或非结合的 lambda**：并行归约要求操作满足结合律。`reduce(0, (a, b) -> a - b)` 在并行下分段计算再合并，结果和顺序执行不同。
- **共享可变状态**：在 `forEach` 里往同一个 `ArrayList` 里 `add` 会有竞态，应改用 `collect`。
- **IO 密集或阻塞**：占用 commonPool 线程，拖累全局。
- **要求严格顺序**：`forEachOrdered` 基本抵消并行收益。

### Collectors.toMap 的两个坑

```java
// 重复 key 会抛 IllegalStateException: Duplicate key ...（或 Duplicate key ... (attempted merging values ...)）
Map<String, Integer> map = list.stream()
    .collect(Collectors.toMap(Foo::getName, Foo::getAge));

// 显式给出合并函数
Map<String, Integer> map2 = list.stream()
    .collect(Collectors.toMap(
        Foo::getName,
        Foo::getAge,
        (a, b) -> a
    ));
```

- 重复 key 且没给合并函数 → `IllegalStateException`。
- value 为 `null` 会抛 `NullPointerException`，因为 JDK 实现走的是 `Map.merge`，而 `HashMap.merge` 不接受 null value。需要容忍 null 时先 `filter(Objects::nonNull)` 或自己写收集器。
- 默认返回 `HashMap`（无序）；要顺序用四参数版本传 `LinkedHashMap::new`，要分组计数用 `Collectors.groupingBy` / `counting`。

### Optional 的定位

`Optional` 的 API 说明写得很明确：它主要是**方法返回值**的类型，用来表达「可能没有结果」；它不是通用的 Maybe，也不是用来消除所有 null 的。

- **不要做字段**：`Optional` 没有实现 `Serializable`，放进需要序列化或远程传输的对象里会出问题；它也只是一个包装对象，字段里用它只是多一层间接。
- **不要做参数**：调用方被迫先包装，方法内部还要 `isPresent` 判断；而且它无法区分「没传」和「传了 null」，只会让 API 更别扭。参数用重载或直接约定即可。
- **不要用 `Optional<List<T>>`**：没有元素就返回空集合，空集合本身就是合法值。
- **别把 null 塞进去**：用 `Optional.ofNullable` 而不是 `of(null)`；返回 Optional 的方法永远不要再返回 null。
- `orElse(x)` 的实参**总是**会被求值，`orElseGet(supplier)` 才是惰性的，有开销的默认值必须用后者。
- `get()` 尽量别用，改用 `orElseThrow()`；Java 9 起还有 `ifPresentOrElse`。
- 基本类型用 `OptionalInt`、`OptionalLong`、`OptionalDouble`，避免 `Optional<Integer>` 的装箱。

## 追问链

### Q1: 为什么说 Stream 是惰性的？peek 里打印为什么没有输出？

中间操作只是往流水线上加节点，不拉取数据。终端操作出现时才拿到数据源的 `Spliterator` 开始遍历，遍历是垂直的：每个元素依次走完下游各阶段，直到被终端操作消费或被短路操作截断。有状态操作（`sorted`、`distinct`）还会形成屏障，需要缓冲上游数据。所以缺终端操作、或终端操作提前短路，上游的 lambda 就不会执行。

#### Q1.1: 那 `filter` 和 `sorted` 谁放在前面，执行次数一样吗？

不一样。`filter` 是无状态的，`sorted` 是有状态屏障。`filter(...).sorted()` 时每个元素先过 `filter`，通过的元素才进入排序缓冲区，全部取完后排一次序；`sorted().filter(...)` 则要先缓冲并排序全部元素，再对有序流逐个 `filter`。语义和开销都不同，把能过滤的 `filter` 放在 `sorted` 前面通常更好，因为需要排序的元素更少；同理 `limit` 放在 `sorted` 后面和前面结果完全不同。

##### Q1.1.1: 那能用 `peek` 来做副作用式的消费吗？

不该。`peek` 是中间操作，它是否执行、执行几次取决于下游怎么消费：下游短路（如 `findFirst`）可能只让一部分元素经过它，而被完全优化掉的终端操作（典型是 `count()`，当流大小已知且没有改变元素数量的操作时可以直接算出结果）甚至会让它一次都不执行。JDK 文档也明确说 `peek` 主要用于调试，不建议依赖它的副作用。有副作用就用终端操作 `forEach`，或者用 `collect`。

### Q2: `parallelStream()` 用的到底是哪个线程池？

`ForkJoinPool.commonPool()`，整个 JVM 共享一个实例。默认并行度是 CPU 核数减一（至少 1），可以用 `-Djava.util.concurrent.ForkJoinPool.common.parallelism=N` 调整。它同时服务其他并行流和不指定 Executor 的 `CompletableFuture` 任务，所以池里的线程被占用会互相影响。

#### Q2.1: 那在并行流里做阻塞的 HTTP 调用会怎样？

commonPool 的可用线程被阻塞任务占住，池的并发度实际下降，其他并行流和 `CompletableFuture` 默认任务会排队甚至长时间得不到执行。`ForkJoinPool` 对普通的阻塞同步没有自动补偿机制，只有通过 `ForkJoinPool.ManagedBlocker`（或实现该接口的锁）才可能临时增加线程。正确做法是把这类工作放进专用的 `ForkJoinPool`（在私有池里提交并行流水线即可），或者用独立的业务线程池，Java 21 上也可以考虑用虚拟线程承载阻塞 IO。

##### Q2.1.1: 那把 `common.parallelism` 调大是不是就快了？

不一定，很多时候反而更慢。第一，CPU 密集型任务的并行度超过核数只会增加上下文切换。第二，对阻塞任务调大只是碰运气，不是把阻塞变成非阻塞，正确做法仍是换池。第三，`availableProcessors()` 在容器里可能读到宿主机的核数（JDK 10+ / 8u191 之后才识别 cgroup 配额，也可用 `-XX:ActiveProcessorCount` 覆盖），据此调参可能完全跑偏。第四，并行收益取决于数据源能否低代价切分、每个元素的活是否够重、合并是否无损，这些不满足时调大并行度解决不了问题。

### Q3: 并行流下 `forEach` 和 `forEachOrdered` 有什么区别？

`forEach` 是无序终端操作，多个线程各自处理各自分到的片段，完成顺序不确定；`forEachOrdered` 保证按遇到顺序执行，代价是并行结果必须按序汇合，可能把并行的收益吃掉。需要注意 `Collectors.toList()` 即使在并行下也保持顺序，所以「并行一定乱序」的说法只对无序终端操作成立。

#### Q3.1: `findFirst` 和 `findAny` 呢？

`findFirst` 的语义要求返回遇到顺序上的第一个匹配，并行实现也必须协调各分片、确认前面没有更早的匹配，因此开销更大；`findAny` 只要能返回任意一个匹配元素，实现可以拿到第一个完成的线程的结果，通常更快但不确定。顺序流里两者都会返回第一个匹配，但只有 `findFirst` 保证这一点，不要因为顺序流上 `findAny` 看起来没问题就在并行场景继续依赖它。

##### Q3.1.1: 那 `reduce` 在并行下安全吗？

只要满足结合律和单位元就安全，因为并行归约会先分段归约再把结果合并。典型错误是用非结合操作，例如 `reduce(0, (a, b) -> a - b)` 或 `reduce("", (a, b) -> a + b)` 用在无序集合上——前者结果与顺序执行不同，后者虽然字符串拼接满足结合律但顺序不确定，结果字符串的排列会变。另外还要注意单位元：`reduce(1, (a, b) -> a * b)` 在空流上会返回 1，而空流的乘积语义上应该是 1 还是无结果需要想清楚。需要可变累积时用 `collect(supplier, accumulator, combiner)` 三个参数版本，每个分片用各自独立的容器再合并，绝不能共享一个 `ArrayList` 让多个线程 `add`。

## 常见坑

- **「中间操作会立即执行，只是结果不返回」** —— 中间操作根本不会执行；没有终端操作，整条流水线（包括 `peek` 里的打印）都不会跑。
- **「并行流一定更快」** —— 数据量小、数据源切分代价高（`LinkedList`、`HashSet`、IO 流）、要求顺序、有阻塞或共享可变状态时，并行往往更慢甚至结果出错。
- **「`parallelStream()` 每次调用会新建线程池」** —— 用的是 JVM 全局 `ForkJoinPool.commonPool()`，和 `CompletableFuture` 默认异步任务共用，会互相影响。
- **「并行流里用 `forEach` 也能保证顺序，因为数据源是有序的」** —— 不保证，要用 `forEachOrdered`；`Collectors.toList()` 的有序性不能推广到 `forEach`。
- **「`Collectors.toMap` 遇到重复 key 会覆盖成后一个」** —— 会抛 `IllegalStateException`，必须显式给合并函数。
- **「`Collectors.toMap` 的 value 可以是 null」** —— JDK 实现走 `Map.merge`，null value 会抛 `NullPointerException`。
- **「Stream 可以像集合一样反复遍历」** —— 一条流水线只能消费一次，第二次终端操作抛 `IllegalStateException`，需要从数据源重新获取或每次新建流。
- **「`Optional` 用得很规范，字段和参数都该用 `Optional`」** —— `Optional` 的设计目标是方法返回值；它没有实现 `Serializable`，做字段会在序列化/RPC 场景出问题，做参数只是把包装负担转给调用方。
- **「`map` 和 `flatMap` 差不多，都能返回集合」** —— `map` 是一对一（映射成集合会得到嵌套结构），`flatMap` 是一对多并展平，处理「每个元素展开成多个」时只能用后者。
- **「基于集合的 Stream 不用关闭」** —— 集合流确实不用，但 `Files.lines`、`Files.list`、`Files.walk` 这类持有文件句柄，必须用 try-with-resources 关闭。

## 加分点

- **垂直遍历和短路**：流水线按元素推进，`limit`、`takeWhile`、`findFirst` 一旦满足条件就通知上游停止；`takeWhile` 与 `filter` 的区别正是前者发现第一个不满足就终止，配合 `Stream.iterate`（Java 9 的三参数版本带终止谓词）可以安全地表达无限序列。
- **源码层面**：中间操作通过 `AbstractPipeline.opWrapSink` 把 `Sink` 链包起来，终端操作触发 `evaluate`；有状态操作会插入屏障。并行的切分能力由源的 `Spliterator.trySplit` 决定，`ArrayList` 的 `ArrayListSpliterator` 支持按索引二分，而 `LinkedList` 只能靠遍历切分，这是「数据源决定并行效果」的根因。
- **`IntStream` 等特化流的价值**：`Arrays.stream(int[])`、`IntStream.range`、`IntStream.sum` 全部走基本类型，避免 `Stream<Integer>` 的装箱和 `Optional<Integer>` 的包装，数值计算场景通常快很多。
- **`Stream.toList()`（Java 16）** 比 `Collectors.toList()` 简洁，返回不可变 List 且允许 null 元素；Java 10 的 `Collectors.toUnmodifiableList()` 虽然也不可变，但连 null 都不允许，两者别混用。
- **Java 21 的 `SequencedCollection`** 给 `List`/`Deque` 增加了 `getFirst`、`getLast`、`reversed`，取首尾不再需要绕 Stream；同时 Java 21 的虚拟线程并不改变「并行流仍跑在 commonPool」这个事实。
- **`ForkJoinPool` 的并行度取值受容器感知影响**：JDK 10 起（以及 8u191+）会读取 cgroup 限制，也可用 `-XX:ActiveProcessorCount` 覆盖；在容器里部署时，`availableProcessors()` 是否符合预期值得确认，否则并行度可能大大偏离实际可用 CPU。

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 8 | 引入 Stream、Lambda、方法引用、Optional 与 Collectors；`parallelStream()` 使用 `ForkJoinPool.commonPool()`；`Optional` 只有 `get`、`isPresent`、`orElse`、`map` 等基础方法 |
| Java 9 | Stream 增加 `takeWhile`、`dropWhile`、`ofNullable` 与带终止谓词的三参数 `iterate`；Optional 增加 `ifPresentOrElse`、`or`、`stream`；Collectors 增加 `filtering`、`flatMapping` |
| Java 10 | `Collectors.toUnmodifiableList/Set/Map`；`Optional.orElseThrow()` 无参版本 |
| Java 11 | `Optional.isEmpty()`；`Predicate.not` |
| Java 16 | `Stream.toList()` 返回不可变 List（允许 null 元素）；`Stream.mapMulti` |
| Java 21 | `SequencedCollection` 为 `List`/`Deque` 提供 `getFirst`、`getLast`、`reversed`；虚拟线程不影响并行流默认仍使用 commonPool |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 下面哪一组方法全部都是终端操作？
    options:
      A: filter、map、sorted
      B: collect、reduce、count
      C: peek、limit、distinct
      D: flatMap、forEach、findAny
    answer: B
    analysis: collect、reduce、count 都是终端操作，会触发流水线。filter、map、sorted、peek、limit、distinct、flatMap 都是中间操作，返回新的 Stream 且不执行；forEach、findAny 虽然是终端操作，但 D 里混入了 flatMap。

  - type: JUDGE
    stem: 并行流中的 forEach 会按数据源的遇到顺序执行。
    answer: F
    analysis: forEach 是无序终端操作，并行时各分片完成顺序不确定。要保证遇到顺序必须用 forEachOrdered，代价是结果需要按序汇合、可能抵消并行收益。

  - type: MULTI
    stem: 关于 parallelStream()，下列说法正确的有？
    options:
      A: 默认使用 ForkJoinPool.commonPool()
      B: 在公共池里执行阻塞任务会占用线程，影响其他使用公共池的并行流和 CompletableFuture
      C: 传给 sorted 的比较器在并行下不会被执行
      D: 共享可变状态的 lambda 在并行下可能产生数据竞争和错误结果
    answer: ABD
    analysis: C 错误。sorted 在并行下仍会执行比较器，只是会先缓冲各分片的数据再做归并排序。A、B 是 commonPool 的共享语义，D 说明有状态副作用不能用并行。

  - type: CLOZE
    stem: |
      下面代码收集 name 到 age 的映射，在出现重复 key 时保留先出现的值，补全这两处：
      ```java
      Map<String, Integer> map = list.stream()
          .collect(Collectors.toMap(
              Foo::getName,
              Foo::getAge,
              (a, b) -> {{1}}   // 重复 key 的合并规则
          ));
      // 不传第三个参数时，重复 key 会抛 {{2}}
      ```
    blanks:
      - ["a", "a（保留先出现的值）"]
      - ["IllegalStateException", "IllegalStateException: Duplicate key"]
    analysis: Collectors.toMap 必须为重复 key 提供合并函数，二元函数返回哪个值就保留哪个（这里返回 a 即保留先出现的）。没有合并函数时 JDK 会用 Map.merge 的语义，重复 key 抛 IllegalStateException。
    difficulty: 2
````
