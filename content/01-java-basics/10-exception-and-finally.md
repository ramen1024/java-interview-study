---
slug: exception-and-finally
title: 异常体系是怎么设计的？finally 里 return 会发生什么？
module: java-basics
tags: [异常, finally, try-with-resources, 受检异常]
difficulty: 2
frequency: 3
related:
  - slug: io-nio-zero-copy
    type: RELATED
  - slug: initialization-order
    type: RELATED
---

## 电梯版回答

Java 的异常体系以 `Throwable` 为根，直接分成 `Error` 和 `Exception` 两支。`Error` 表示 JVM 层面的严重问题，比如 `OutOfMemoryError`、`StackOverflowError`，应用层默认不该捕获；`Exception` 再分受检异常和 `RuntimeException`。受检异常强制调用方处理，对可恢复错误有价值，但给已有方法新增受检异常会破坏所有调用方，对 API 演进不友好，所以新框架倾向非受检，Spring 的 `DataAccessException` 就是把 JDBC 的受检 `SQLException` 转译成非受检体系。`finally` 无论是否抛异常都会执行，适合作资源释放；但 `finally` 里写 `return` 会直接覆盖 try 里准备好的返回值，并且**吞掉** try 中抛出的异常，连 `getSuppressed()` 里都找不到。`try-with-resources` 在 try 和 `close()` 都抛异常时会保留原异常，把次要异常挂到 `Throwable.getSuppressed()`。只有 `System.exit()` 或 JVM 崩溃这类情况才会让 `finally` 不执行。

## 展开讲解

### Throwable 的体系结构

```
Throwable
├── Error
│   ├── VirtualMachineError        → OutOfMemoryError、StackOverflowError
│   ├── LinkageError               → NoClassDefFoundError、NoSuchMethodError
│   └── AssertionError
└── Exception
    ├── RuntimeException           （非受检）
    │   ├── NullPointerException
    │   ├── IllegalArgumentException
    │   ├── IndexOutOfBoundsException
    │   └── IllegalStateException
    ├── IOException                （受检）
    ├── SQLException               （受检）
    └── ReflectiveOperationException（受检）
```

`Error` 和 `Exception` 是平级的两支，都直接继承 `Throwable`。
`RuntimeException` 及其子类是非受检异常（unchecked），其余的 `Exception` 子类
是受检异常（checked）。编译器只强制处理受检异常。

### 受检异常与非受检异常的取舍

受检异常的设计意图是：如果一个方法可能因为外部原因失败（文件不存在、网络中断），
把这种可能性写进签名，调用方就不能装作没看见。这对**可恢复**的错误是合理的。

问题出在 API 演进和组合上：

- 给已有方法新增一个受检异常，所有调用方、以及调用方的调用方都得改签名或加
  `catch`，这是源码级不兼容。`RuntimeException` 可以沿调用栈自由上浮，
  只在真正能处理的地方捕获
- 受检异常常被写成空 `catch` 来糊弄编译器，反而掩盖了故障
- 在函数式接口里几乎无法使用受检异常：`Function.apply` 没声明受检异常，
  传进去的 lambda 一抛 `IOException` 就编译不过，只能包一层
  `RuntimeException`，绕一圈又回到了非受检

Spring 的处理方式是最典型的例子：JDBC 的 `SQLException` 是受检的，
Spring 用 `SQLExceptionTranslator` 把它转译成非受检的
`org.springframework.dao.DataAccessException` 体系，
如 `DuplicateKeyException`、`DataIntegrityViolationException`。
业务代码不必依赖具体数据库驱动，也不必层层声明受检异常。

### finally 与 return 的陷阱

`finally` 块在离开 try 区域时必定执行：正常结束执行，抛异常也执行，
`return`/`break`/`continue` 也执行。但下面这段代码会**把异常吞掉**：

```java
static int dangerous() {
    try {
        throw new IllegalStateException("业务异常");
    } finally {
        return 1;   // 异常被完全丢弃，调用方拿到 1
    }
}
```

`finally` 里的 `return` 是真实执行的返回指令，它会让先前待传播的异常
被直接丢弃——这个异常既不会打印，也不在 `getSuppressed()` 里。
同理，`finally` 里 `throw` 新异常、`break`/`continue` 跳转，也会覆盖原异常。
这是静态检查工具（如 SpotBugs、IDE 检查）会专门告警的写法。

### finally 改局部变量为什么改不动返回值

`return x` 的求值发生在进入 `finally` 之前：

```java
static int readLocal() {
    int x = 1;
    try {
        return x;      // 先把 x 的值存进临时槽位
    } finally {
        x = 2;         // 改的是局部变量，不是已暂存的返回值
    }
}
// 返回 1，不是 2
```

javac 生成的字节码会先把 `x` 读出来放入一个临时局部槽，再执行 `finally`，
最后用那个临时槽返回。但如果 `finally` 里自己写 `return x;`，
它会在 `finally` 执行时重新读变量，于是返回 2——这就是「覆盖」。

对比：如果返回的是对象引用，暂存的是引用值，`finally` 里改**对象内容**
会体现出来，改**引用指向**则不会：

```java
static StringBuilder readRef() {
    StringBuilder sb = new StringBuilder("a");
    try {
        return sb;         // 暂存引用
    } finally {
        sb.append("b");    // 改对象内容，调用方看得到
    }
}
// 返回的对象内容已是 "ab"

static StringBuilder rebindRef() {
    StringBuilder sb = new StringBuilder("a");
    try {
        return sb;
    } finally {
        sb = new StringBuilder("c");  // 只改了局部引用，无效
    }
}
// 仍返回内容为 "a" 的那个对象
```

### try-with-resources 与抑制异常

`try-with-resources` 要求资源实现 `AutoCloseable`（`Closeable` 是其子接口，
把 `close()` 收窄为只抛 `IOException`）。它按资源声明的**逆序**关闭，
并解决手写 `finally` 解决不了的问题：try 抛异常、`close()` 也抛异常时，
保留原异常，把 `close()` 的异常挂上去：

```java
try (InputStream in = Files.newInputStream(path)) {
    throw new IllegalStateException("业务异常");
} catch (Exception e) {
    // e 仍是 IllegalStateException
    // e.getSuppressed() 里能看到 close() 抛出的异常
}
```

手写 `finally` 时，`close()` 里的 `throw` 会把原异常整个覆盖掉，
所以 `try-with-resources` 不只是少写几行。

### 异常链

捕获后要包装重抛时，必须把原因带上，否则丢失根因：

```java
// 构造器传 cause（推荐）
throw new ServiceException("查询订单失败", e);

// 或者 initCause（只能调一次，且只有尚未设置过 cause 时可用）
ServiceException ex = new ServiceException("查询订单失败");
ex.initCause(e);
throw ex;
```

`Throwable` 的 `cause` 字段用 `this` 作哨兵值表示「尚未初始化」，
所以全参构造器已设置 cause 后再调 `initCause` 会抛 `IllegalStateException`。
排查线上问题时，`getCause()` 链和日志里的 `Caused by:` 就是靠这个字段串起来的。

### 不要用异常控制流程

异常对象的构造会调用 `fillInStackTrace`，逐帧记录调用栈，成本远高于一次普通分支跳转；
抛异常还要在异常表里查找处理者。即使 JIT 在异常不会逃出当前方法时能省略栈填充
（这是优化，不是语言保证），也不该把异常当作常规控制流。
判断「能不能转成数字」用 `Integer.parseInt` 加库返回，或者用
`NumberFormatException` 之外的方式；「循环直到越界」不要靠
`ArrayIndexOutOfBoundsException` 来终止。

## 追问链

### Q1: Error 和 Exception 有什么区别？为什么 Error 不该被捕获？

`Throwable` 下分两支。`Error` 代表 JVM 自身出了严重问题，
比如 `OutOfMemoryError`、`StackOverflowError`（都属 `VirtualMachineError`）、
`NoClassDefFoundError`（`LinkageError`），语义是「应用代码已经无力回天」；
`Exception` 代表运行中可预期的问题，是应用层该处理的。
捕获 `Error` 会把「JVM 已处于不一致状态」这个事实掩盖掉，
让故障在更远的地方以更难排查的方式爆发，所以通常写 `catch (Exception)`。

#### Q1.1: 那 OutOfMemoryError 就一定不能恢复吗？

不一定。堆内存溢出通常确实无法恢复，但如果是一次性分配超大数组触发的，
异常抛出后那个大对象可能已可回收，捕获后还能做优雅降级、记录日志再退出。
所以「Error 绝不能捕获」是简化说法，准确说法是「默认不该捕获，
除非你明确知道当前 JVM 状态仍然一致、且能真正恢复」。

##### Q1.1.1: 既然如此，那用 catch (Throwable) 兜底是不是更保险？

不是。`StackOverflowError` 抛出时栈几乎耗尽，`catch` 块里再调用任何方法
都可能再次溢出；`catch (Throwable)` 还会连 `ThreadDeath` 这类一起吞掉。
框架里确实有合理的兜底点，但关键在于捕获后**不吞**：
`ThreadPoolExecutor.runWorker` 用 `catch (RuntimeException | Error x)` 接住任务异常，
先记下、让 `finally` 里的 `afterExecute` 钩子跑到，再把异常重新抛出，
以免任务异常无声杀死工作线程。应用代码的兜底边界应该是 `Exception`。

### Q2: finally 里 return 会有什么后果？

它会覆盖 try 里已经算好的返回值，并且**吞掉 try 中抛出的异常**：
异常对象被直接丢弃，连 `getSuppressed()` 里都没有，症状是
「明明抛异常了，调用方却拿到了一个正常返回值」。
原因是 `finally` 里的 `return` 编译成一条无条件的返回指令，
把 try 到 finally 之间的异常传播路径短路了。等价地，
`finally` 里 `throw` 新异常也会覆盖原异常。这是应该被静态检查拦下的写法。

#### Q2.1: 如果没有写 return，只是在 finally 里改一下变量，为什么返回值没变？

因为 `return x` 的求值发生在进入 `finally` 之前。以
`int x = 1; try { return x; } finally { x = 2; }` 为例，
javac 生成的字节码先把 `x` 读到一个临时槽位，再执行 `finally` 的赋值，
最后返回临时槽里的值，所以结果是 1。`finally` 改的是局部变量，
不是那个已经暂存的返回值。

##### Q2.1.1: 那如果返回的是对象引用，在 finally 里改对象内容呢？

会生效。暂存的只是引用值（地址），指向的还是同一个对象，
`finally` 里改字段或调方法都没有换对象，所以调用方看到的内容变了。
要区分三类动作：

```java
StringBuilder sb = new StringBuilder("a");
sb.append("b");                        // 改对象内容 → 生效
sb = new StringBuilder("c");           // 改局部引用 → 不生效
return sb.toString();                  // 字符串在进 finally 前已构造好 → 改不动
```

第三种最容易被忽略：`return sb.toString()` 时值已经算出来了，
`finally` 里再 `append` 也影响不了它，而且 `String` 本身不可变。

### Q3: try-with-resources 相比手写 finally 好在哪？

三点。一是自动按声明**逆序**关闭，省掉嵌套的 try-finally；
二是 try 块已抛异常、`close()` 又抛异常时不会丢掉原异常；
三是资源变量隐式 final，避免在 `finally` 里用错变量。
这也是为什么它不只是语法糖。

#### Q3.1: 那 close 抛出的异常去哪了？

如果 try 正常结束，`close()` 的异常正常抛出。
如果 try 已经抛了异常 A、`close()` 又抛了异常 B，那么 A 继续向外传播，
B 被挂到 A 上，通过 `A.getSuppressed()` 能拿到。
手写 `finally` 做不到这一点——`finally` 里的 `throw` 会把原异常覆盖掉，
所以「资源的 close 失败」这个信息会丢失，排查时只能看到业务异常。

### Q4: 为什么新框架倾向于非受检异常？

核心是 API 演进和组合性。给已有方法新增受检异常会强制所有调用方改代码，
是源码级不兼容；`RuntimeException` 可以自由上浮，只在能处理的地方捕获。
另外受检异常在 lambda / 函数式接口里很难用，也常被空 `catch` 敷衍掉。
Spring 的 `DataAccessException` 就是典型：JDBC 的 `SQLException` 是受检的，
Spring 用 `SQLExceptionTranslator` 把它转成非受检的、与具体数据库无关的体系。

#### Q4.1: 那非受检异常会不会导致异常被漏掉不处理？

会，这是代价。非受检体系靠别的手段补安全性：
把具体异常转译成语义明确的子类（如 `DuplicateKeyException`），
在 `@ControllerAdvice` / `@ExceptionHandler` 里统一兜底，
未捕获的 `RuntimeException` 由 `Thread.UncaughtExceptionHandler` 接住。
取舍的实质是把「编译期强制处理」换成了「运行期统一兜底 + 文档约定」。

## 常见坑

- **「finally 一定会执行」** —— `System.exit()` / `Runtime.halt()`、JVM 崩溃、
  进程被强制杀死时不会执行；try 块里的死循环也让 `finally` 永远到不了
- **「finally 里 return 只是覆盖返回值」** —— 还会把 try 中抛出的异常整个吞掉，
  连 `getSuppressed()` 里都找不到，表现为「抛了异常却没报错」
- **「catch 顺序无所谓」** —— 子类必须写在父类前面，`catch (Exception e)` 放前面
  会让后面的 `catch (IOException e)` 编译报错「exception IOException has already
  been caught」
- **「受检异常更安全，所以应该优先用」** —— 强制处理对可恢复错误有意义，
  但破坏 API 兼容性、在函数式接口里难以使用、容易催生空 catch；
  Spring 等主流框架已转向非受检
- **「Error 是 Exception 的子类」** —— 两支平级，都直接继承 `Throwable`
- **「用异常做流程控制问题不大」** —— 异常构造要 `fillInStackTrace` 逐帧记录调用栈，
  成本远高于普通分支；JIT 的省略优化不是语言保证

## 加分点

- 能讲清 `finally` 的字节码实现：Java 7 起不再用 `jsr`/`ret` 子程序，
  而是把 `finally` 的代码复制到正常路径和异常表覆盖的路径上。
  这解释了为什么「`finally` 里 `return`」不报错而是直接生效——
  它就是一条普通返回指令
- 知道 `Throwable.cause` 用 `this` 作哨兵值（`cause == this` 表示未初始化），
  因此 `initCause` 只能调一次，重复调用抛 `IllegalStateException`
- Java 7 的 multi-catch：`catch (IOException | SQLException e)`，
  编译后只生成一份处理代码，且捕获变量隐式 final，
  所以不能在 catch 块里给它重新赋值
- Java 14 引入 helpful NullPointerException（JEP 358，Java 15 起默认开启），
  报错会指出具体是哪个变量/字段为 null，排查 NPE 效率大幅提升
- precise rethrow：`catch (Exception e) { throw e; }` 这种写法下，
  编译器仍能根据 try 块实际可能抛出的受检异常推断调用方要处理什么，
  所以不必把方法声明成 `throws Exception`

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 7 | 引入 try-with-resources、`Throwable.addSuppressed` / `getSuppressed`、multi-catch；`finally` 与 `return` 的语义与之前一致，未变 |
| Java 14 | helpful NullPointerException 消息（JEP 358） |
| Java 15 | helpful NullPointerException 默认开启 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 下面哪个动作不会覆盖或丢弃 try 块中待传播的异常？
    options:
      A: finally 块里执行 return
      B: finally 块里执行 throw new RuntimeException()
      C: finally 块里执行普通赋值语句后正常结束
      D: finally 块里执行 break（在循环内）
    answer: C
    analysis: finally 里 return / throw / break / continue 都会让控制流提前跳走，从而覆盖原异常。只在 finally 里正常执行语句并结束，原异常才会继续向上传播。

  - type: JUDGE
    stem: finally 块一定会执行，即使 try 块中调用了 System.exit()。
    answer: F
    analysis: System.exit() 会触发关闭钩子并终止 JVM，但不会返回去执行 finally；Runtime.halt()、JVM 崩溃、进程被强杀同理。此外 try 中的死循环也会让 finally 永远到不了。

  - type: CLOZE
    stem: |
      补全下面两处：在 finally 里写哪条语句会吞掉 try 抛出的异常；以及获取
      被 try-with-resources 抑制的次要异常用哪个方法。

      ```java
      try {
          throw new IllegalStateException("boom");
      } finally {
          {{1}};   // 这一句会让上面的异常被完全丢弃
      }

      // try-with-resources 中 close() 抛出的次要异常：
      // original.{{2}}()
      ```
    blanks:
      - ["return", "return;"]
      - ["getSuppressed", "getSuppressed()"]
    analysis: finally 里的 return 编译成无条件返回指令，会覆盖 try 中待传播的异常，且该异常不会进入 getSuppressed()。try-with-resources 在 close() 也抛异常时保留原异常，把次要通过 Throwable.getSuppressed() 挂上去，这是手写 finally 做不到的。
    difficulty: 3
````
