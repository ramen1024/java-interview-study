---
slug: completablefuture
title: CompletableFuture 怎么做异步编排？
module: concurrency
tags: [CompletableFuture, 异步编排, ForkJoinPool, 线程池]
difficulty: 3
frequency: 2
related:
  - slug: stream-and-lambda
    type: CONTRAST
  - slug: thread-pool-parameters
    type: PREREQUISITE
  - slug: virtual-threads
    type: RELATED
---

## 电梯版回答

CompletableFuture 把异步任务串成流水线，核心是三个维度。转换结果用 thenApply，相当于 map；下一步本身还是异步、函数返回 CompletableFuture 时必须用 thenCompose，相当于 flatMap，否则会得到嵌套的 CompletableFuture。合并两个独立结果用 thenCombine，只消费结果用 thenAccept，连结果都不用用 thenRun，全部完成后做动作可以用 allOf、任一完成用 anyOf。异常处理三者分工：exceptionally 只在异常时给兜底值，handle 无论成败都执行且能改写结果和类型，whenComplete 无论成败都执行但不能改结果、只适合记日志。最大的坑是默认线程池：不传 Executor 时用 ForkJoinPool.commonPool()，并行度默认是 CPU 核数减一，阻塞型任务会把公共池拖垮，并行流也用同一个池，所以生产必须显式传业务线程池。取结果时 join() 抛非受检异常，get() 抛受检异常。

## 展开讲解

### 常用方法分类

| 方法 | 签名要点 | 说明 |
|---|---|---|
| `thenApply` | `Function<T,U>` → `CF<U>` | 同步转换结果，相当于 map |
| `thenCompose` | `Function<T,CF<U>>` → `CF<U>` | 展平嵌套，相当于 flatMap |
| `thenAccept` | `Consumer<T>` → `CF<Void>` | 消费结果，不产出 |
| `thenRun` | `Runnable` → `CF<Void>` | 完全不关心上一步的结果 |
| `thenCombine` | 另一个 `CF<U>` + `BiFunction<T,U,V>` | 合并两个独立的结果 |
| `thenAcceptBoth` | 另一个 `CF<U>` + `BiConsumer<T,U>` | 两个都完成后一起消费 |
| `allOf` | `CF<?>...` → `CF<Void>` | 全部完成，但**不收集结果** |
| `anyOf` | `CF<?>...` → `CF<Object>` | 任一完成就返回，结果是那个 future 的值 |

带 `Async` 后缀的变体（`thenApplyAsync` 等）会把回调提交到线程池；不带 `Async` 的回调不加线程，在上一个阶段完成的线程里同步执行。

### thenApply 与 thenCompose

```java
// 错：下一步返回 CompletableFuture 却用 thenApply，得到 CF<CF<Order>>
CompletableFuture<CompletableFuture<Order>> nested =
    getUser(userId).thenApply(u -> queryOrder(u));     // queryOrder 返回 CF<Order>

// 对：用 thenCompose 展平
CompletableFuture<Order> flat =
    getUser(userId).thenCompose(u -> queryOrder(u));
```

判断标准很简单：lambda 的返回值是不是 `CompletableFuture`（或 `CompletionStage`）。是就用 `thenCompose`。

### 异常处理三者对比

| 方法 | 参数 | 执行时机 | 能否改写结果 |
|---|---|---|---|
| `exceptionally` | `Function<Throwable,T>` | 只在上游异常时 | 能，返回同类型兜底值 |
| `handle` | `BiFunction<T,Throwable,U>` | 成功和失败都执行 | 能，还能改变类型 |
| `whenComplete` | `BiConsumer<T,Throwable>` | 成功和失败都执行 | **不能**，返回值是 void |

关键区别：`whenComplete` 返回的 future 在成功时沿用原结果、失败时沿用原异常，它只能做旁路动作；`handle` 的 `BiFunction` 返回新值，所以既能把失败改写成成功，也能抛新异常把成功转成失败；`exceptionally` 不改类型，只做兜底。

### 异常传播

不写任何异常处理时，异常被捕获在 future 内部，后续的 `thenApply` / `thenAccept` 等回调被跳过并继续向下传递：

- `get()` 抛受检的 `InterruptedException` 和 `ExecutionException`（原始异常在 `getCause()` 里）
- `join()` 抛非受检的 `CompletionException`
- `allOf` / `anyOf` **不会主动抛异常**，必须对聚合后的 future 调用 `join()` 才会暴露

如果这个 future 从头到尾没人 `get` / `join`，异常会被静默丢弃——这是「异步任务没执行」最常见的原因。

### 默认线程池的坑

```java
// 不传 Executor：用 ForkJoinPool.commonPool()
CompletableFuture.supplyAsync(() -> callRemote());          // 阻塞调用会占住公共池线程

// 正确：显式传业务线程池
CompletableFuture.supplyAsync(() -> callRemote(), businessPool);
```

`ForkJoinPool.commonPool()` 的并行度默认是可用处理器数减一（至少 1），可以用系统属性 `java.util.concurrent.ForkJoinPool.common.parallelism` 调整。它靠工作窃取跑 CPU 密集任务很合适，但线程数固定、不区分任务类型：一旦有任务在等 DB 或 HTTP，这个线程就被占住，新任务只能排队；而并行流 `parallelStream` 用的是同一个公共池，会互相影响，极端情况下整个应用的异步能力都被拖死。

### allOf 怎么收集结果

`allOf` 返回的是 `CompletableFuture<Void>`，不携带结果，惯用法是再对每个 future `join` 一次：

```java
CompletableFuture<Void> all =
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

CompletableFuture<List<Result>> results = all.thenApply(v ->
    futures.stream()
        .map(CompletableFuture::join)     // 此时都已完成，join 不会阻塞
        .collect(Collectors.toList())
);
```

### get() 与 join()

| | `get()` | `join()` |
|---|---|---|
| 抛出的异常 | `InterruptedException` + `ExecutionException`（受检） | `CompletionException`（非受检） |
| 适用场合 | 需要显式处理中断 | lambda / stream 里更顺手 |

两者都是阻塞取结果，语义一致。Java 9 起还可以用 `orTimeout` 和 `completeOnTimeout` 给 future 加超时，不必自己维护定时器；`failedFuture` 可以直接构造一个已失败的 future。

## 追问链

### Q1: thenApply 和 thenCompose 有什么区别？

`thenApply` 是 map，把上一步的结果 T 映射成 U；`thenCompose` 是 flatMap，映射函数返回的是 `CompletionStage<U>`，会被展平。判断标准就是：lambda 的返回值如果是 `CompletableFuture`，就该用 `thenCompose`。

#### Q1.1: 那如果用错了会怎样？

类型会变成 `CompletableFuture<CompletableFuture<U>>`，取值要 `join().join()`。更严重的是生命周期不受控：外层 future 在回调返回内层 future 的那一刻就完成了，内层的异步任务还在跑。用 `allOf` 等它时会提前返回，内层的异常也不会被外层编排捕获，变成静默失败。

##### Q1.1.1: 那不带 Async 的 thenApply，lambda 在哪个线程执行？

在触发上一个阶段完成的那条线程里同步执行——可能是调用 `complete` / `join` 的线程，也可能是执行上一个 Async 阶段的池线程，不确定。只有带 `Async` 后缀的方法才会显式提交到线程池（不传 Executor 就是公共池）。所以在回调里做阻塞操作要么用 Async 加自己的池，要么就会占住上一个阶段的线程。

### Q2: 异常处理这三个方法怎么选？

`exceptionally` 只在异常时触发，给一个同类型的兜底值，最省事；`handle` 无论成败都执行，而且 `BiFunction` 可以返回新值、改变类型，能把失败改写成功，灵活度最高；`whenComplete` 也是无论成败都执行，但参数是 `BiConsumer`、返回 void，不能改写结果，适合记日志、打点、释放资源这类旁路动作。

#### Q2.1: 不写任何异常处理，异常会去哪？

被封在 `CompletableFuture` 内部，后续的 `thenApply` / `thenAccept` 等回调被跳过并向下传递。调用 `get()` 时抛 `ExecutionException` 包装原始异常，`join()` 抛非受检的 `CompletionException`；注意 `allOf` / `anyOf` 不会主动抛，得对聚合后的 future 调 `join` 才会暴露。如果这个 future 从头到尾没有被 `get` / `join`，异常就被静默丢弃——这是异步任务「莫名没执行」最常见的原因。

##### Q2.1.1: 那 handle 和 whenComplete 都返回 CompletableFuture，为什么说 whenComplete 不能改结果？

因为 `whenComplete` 的 `BiConsumer` 返回 void，没有地方放新结果，JDK 的实现是在完成时沿用原结果或原异常；`handle` 的 `BiFunction` 返回 U，所以既能用兜底值替换失败的异常结果，也能直接返回一个值把失败转成成功，甚至抛新异常把成功转成失败。一句话：whenComplete 是「看一眼」，handle 是「接管结果」。

### Q3: 不传 Executor 会怎样？

用 `ForkJoinPool.commonPool()`。它的并行度默认是可用处理器数减一（至少 1），是给 CPU 密集任务设计的，工作窃取模型假设任务不会阻塞。一旦里面跑了等 DB、等 HTTP 的阻塞任务，线程就被占住，新任务只能在队列里排；而 `parallelStream` 用的是同一个公共池，会跟着一起饿死。所以生产上必须显式传业务线程池。

#### Q3.1: 那为什么说并行流和 CompletableFuture 会互相影响？

因为它们默认共享 `ForkJoinPool.commonPool()`。一方把池子占住，另一方就排不上队。可以调大公共池（系统属性 `java.util.concurrent.ForkJoinPool.common.parallelism`），但这会影响全应用；更稳的做法是用自己的 Executor——CompletableFuture 直接把 Executor 当参数传进去，而并行流没有官方的「指定池」API，只能把整个流包在自己的 ForkJoinPool 里提交。

##### Q3.1.1: get() 和 join() 该怎么选？

语义一样，都是阻塞取结果，差别在异常：`get()` 抛受检的 `InterruptedException` 和 `ExecutionException`，调用处必须 try/catch，`join()` 抛非受检的 `CompletionException`，在 lambda 和 stream 里更顺手。另外想知道底层到底抛了什么，要看 cause 链：`ExecutionException` / `CompletionException` 都只是包装，真正的原因在 `getCause()` 上。

## 常见坑

- **说 CompletableFuture 不传线程池就是「没有线程池」** —— 有，默认是 `ForkJoinPool.commonPool()`，只是不可控、且与并行流共享
- **说 thenApply 和 thenCompose 都能处理返回 future 的函数** —— 函数返回 future 时必须用 thenCompose，否则得到嵌套的 `CompletableFuture<CompletableFuture<...>>`
- **说 whenComplete 可以改结果** —— 不能，它返回 void；要改结果用 handle
- **说 allOf 会返回结果集合** —— 返回的是 `CompletableFuture<Void>`，收集结果要自己 stream 加 join
- **说公共池的并行度是 CPU 核数** —— 默认是可用处理器数**减一**（至少 1），可用系统属性覆盖
- **说 get() 和 join() 完全一样** —— 抛出的异常类型不同，一个受检一个非受检
- **在 thenApply / thenAccept 里直接做阻塞 IO** —— 不带 Async 的回调跑在完成上一个阶段的线程上，阻塞会占住它
- **说 exceptionally 能捕获所有异常** —— 只能兜住上游这一条链；`exceptionally` 自己抛的异常会继续向下传递，而且它捕获不到与本链无关的线程里的异常

## 加分点

- 能说清默认并行度是可用处理器数减一、并给出覆盖用的系统属性名，这是「真的用过」的信号
- 指出「不带 Async 的回调不换线程」这条规则，能解释为什么在回调里放阻塞代码比放 Async 更危险
- 知道 `allOf` 收集结果的惯用法：`allOf(...)` 之后 `thenApply(v -> futures.stream().map(CompletableFuture::join).collect(toList()))`
- 知道 `thenCombine` 的两个 future 从一开始就是并行跑的，合并函数在最后一个完成的阶段上执行
- Java 9 的 `orTimeout` / `completeOnTimeout` 底层有 JDK 内部的延时调度，不用自己维护 `ScheduledExecutorService`；`failedFuture` 省去手写「completedFuture 再抛异常」
- 虚拟线程环境下用 `Executors.newVirtualThreadPerTaskExecutor()` 作为 Executor，阻塞 IO 不会拖垮固定大小的池
- 能指出 CompletableFuture 的短板：没有原生的超时级联、没有编排可视化，复杂依赖图（多个前置、条件分支）用 Reactor / RxJava 更合适

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 8 | 引入 `CompletableFuture`；默认执行器是 `ForkJoinPool.commonPool()`；`get()` 抛受检异常，`join()` 抛非受检异常 |
| Java 9 | 增加 `orTimeout` / `completeOnTimeout`（超时）、`failedFuture`（直接构造失败 future）、`copy`、`minimalCompletionStage`、`defaultExecutor` |
| Java 12 | 增加 `exceptionallyAsync` / `exceptionallyCompose`，异常处理也能切到线程池执行，或返回一个新的 CompletableFuture |
| Java 21 | 虚拟线程转正后，可用 `Executors.newVirtualThreadPerTaskExecutor()` 作为 Executor，避免阻塞任务挤占固定大小的公共池 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 上一步的结果需要再发起一次异步调用（函数返回 CompletableFuture），应该用哪个方法？
    options:
      A: thenApply
      B: thenCompose
      C: thenAccept
      D: thenRun
    answer: B
    analysis: thenApply 是 map，函数返回 future 时会得到嵌套的 CompletableFuture；thenCompose 是 flatMap，会把返回的 future 展平。thenAccept 只消费不返回结果，thenRun 连上一步的结果都不需要。

  - type: JUDGE
    stem: whenComplete 可以在上游异常时返回一个兜底值，把失败改写成成功。
    answer: F
    analysis: whenComplete 的参数是 BiConsumer、返回 void，不能改写结果，成功时沿用原结果、失败时沿用原异常。要改写结果用 handle，只用兜底值可以用 exceptionally。

  - type: MULTI
    stem: 关于 CompletableFuture 的默认线程池，下列说法正确的有？
    options:
      A: 不传 Executor 时使用 ForkJoinPool.commonPool()
      B: 公共池的并行度默认是可用处理器数减一
      C: 并行流 parallelStream 和它共享同一个公共池
      D: 阻塞型任务不会影响公共池的吞吐
    answer: ABC
    analysis: D 错误。公共池线程数固定、是为 CPU 密集任务设计的，阻塞任务会占住线程不放；又因为并行流共用这个池，一方阻塞会让另一方一起排队甚至饿死，所以生产要显式传业务线程池。

  - type: CLOZE
    stem: |
      补全异常处理代码：给失败提供兜底值，以及无论成败都执行日志但保留原结果：
      ```java
      CompletableFuture<String> withFallback = future
          .{{1}}(ex -> "default");                 // 仅在异常时触发，给出兜底值

      CompletableFuture<String> logged = future
          .{{2}}((v, ex) -> log.warn("done", ex)); // 成败都执行，但不能改写结果
      ```
    blanks:
      - ["exceptionally"]
      - ["whenComplete"]
    analysis: exceptionally 只处理异常并返回同类型兜底值；whenComplete 无论成功失败都执行，但参数是 BiConsumer、返回 void，只能做日志或清理这类旁路动作，结果和异常原样传递；要改写结果应该用 handle。
    difficulty: 3
````
