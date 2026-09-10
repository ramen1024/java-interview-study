---
slug: oom-troubleshooting
title: 线上 OOM 和 CPU 100% 该怎么排查？
module: jvm
tags: [OOM, 排查, jstack, MAT, 线上问题]
difficulty: 3
frequency: 3
related:
  - slug: runtime-memory-layout
    type: PREREQUISITE
  - slug: garbage-collection-algorithms
    type: RELATED
---

## 电梯版回答

排查的第一步是别急着调参数，而是先确定「哪块内存溢出」——OOM 有七八种类型，堆溢出、元空间溢出、直接内存溢出、无法创建线程，对应的根因和修法完全不同。堆溢出要先区分是内存泄漏还是确实不够用：用 HeapDumpOnOutOfMemoryError 自动 dump，再用 MAT 看支配树找占用最大的对象和 GC Roots 引用链；如果两次 dump 对比发现同一批对象持续增长就是泄漏，如果只是峰值高就是容量不足。CPU 100% 走 top 找进程、top -Hp 找线程、printf 把线程号转十六进制、再到 jstack 里按 nid 定位代码。工具上 jstat 看 GC 趋势、jstack 看线程栈、arthas 可以不停机排查。

## 展开讲解

### 先分清是哪种 OOM

这是最容易被跳过、也最不该跳过的一步。不同 OOM 的根因完全不同：

| 错误信息 | 出问题的区域 | 典型根因 |
|---|---|---|
| `Java heap space` | 堆 | 内存泄漏，或堆确实不够 |
| `GC overhead limit exceeded` | 堆 | 98% 时间在 GC 却只回收不到 2% 空间 |
| `Metaspace` | 元空间 | 类加载器泄漏、动态生成类过多 |
| `Compressed class space` | 元空间 | 同上（压缩类指针区域） |
| `Direct buffer memory` | 直接内存 | NIO `DirectByteBuffer` 未释放，超出 `MaxDirectMemorySize` |
| `unable to create native thread` | 线程栈 | 线程数失控，或系统线程数达上限 |
| `Requested array size exceeds VM limit` | 堆 | 要分配的数组大小超过 int 上限 |
| `Out of swap space?` | 系统 | 物理内存 + swap 都被耗尽 |

**`GC overhead limit exceeded` 值得单独说**：它不是「堆满了」，
而是「堆快满了但回收效率极低」。JVM 的判据是
「超过 98% 的时间花在 GC 上，却回收了不到 2% 的堆」。
这意味着存活对象太多、可用空间太少，
继续跑下去只是在空转，所以主动抛错。

这个 OOM 往往比 `Java heap space` 更早出现，
是堆内存不足的**预警信号**。

### 堆 OOM 的排查流程

**第一步：让 JVM 把现场留下来**

```bash
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/data/dump/
```

**没有 dump 的 OOM 是没法查的**——进程重启后现场就没了。
这个参数必须在部署时就加上，事后补不了。

**第二步：区分泄漏还是容量不足**

这是整个排查中最重要的判断。方法是**对比两个时间点的 dump**：

```
t1 的 dump：某类对象 10 万个，占 200MB
t2 的 dump：同一类对象 80 万个，占 1.6GB   ← 持续增长 = 泄漏
```

如果两次 dump 里对象数量和占用基本相当，只是峰值高，
那是容量问题（调大堆或减少对象）。

**第三步：在 MAT 里找支配树**

MAT 的 **Dominator Tree** 按「支配关系」排序——
如果对象 A 被回收后对象 B 才能被回收，就说 A 支配 B。
支配树能直接告诉你「谁把内存占住了」，比看对象数量直观得多。

重点看两处：

```
① Histogram：按类统计实例数与占用（retained size）
② Dominator Tree：谁支配了最多的内存
   然后对可疑对象右键 → Path to GC Roots → 排除弱引用
      → 找到「谁在强引用它」，那就是泄漏源头
```

**「排除弱引用后仍有强引用链」是关键判断**。
如果引用链经过 `WeakReference` / `SoftReference`，
说明这就是设计上可回收的对象，不算泄漏。

### 常见的内存泄漏模式

| 模式 | 机制 |
|---|---|
| 静态集合只增不减 | `static Map` 当缓存用但没有淘汰策略 |
| `ThreadLocal` 未 `remove` | 线程池线程长期存活，`ThreadLocalMap` 里的 value 强引用无法释放 |
| 监听器/回调未注销 | 注册了但没反注册，容器持有所有注册者 |
| 未关闭的资源 | 连接、流、`DirectByteBuffer` 的 Cleaner 未触发 |
| 自定义类加载器未释放 | Tomcat 热部署、动态代理，导致元空间泄漏 |
| 内部类持有外部类引用 | 非静态内部类隐式持有 `Outer.this` |

**最隐蔽的一类是「有界但不够淘汰」**：
缓存设了上限，但上限远大于实际可用内存，
或者 key 的基数在增长（比如用用户 ID 做 key），
表现是「有上限却还在涨」。

### CPU 100% 的排查流程

四步定位到代码行：

```bash
# ① 找进程：确认是哪个 Java 进程吃 CPU
top -c

# ② 找线程：-H 显示线程，-p 指定进程
top -Hp <pid>
# 记下占用最高的线程 ID，比如 12345

# ③ 转十六进制（jstack 里的 nid 是十六进制）
printf '%x\n' 12345        # 得到 3039

# ④ 到 jstack 输出里按 nid 定位
jstack <pid> > /tmp/stack.txt
grep -A 30 'nid=0x3039' /tmp/stack.txt
```

**为什么必须转十六进制**：`top -Hp` 给的是十进制 TID，
而 `jstack` 输出的 `nid` 是十六进制。不转换就永远对不上，
这个换算步骤是新手最容易卡住的地方。

也可以在 `top -Hp` 界面里直接按 `H` 切换线程显示，
或按 `1` 看各核负载——后者能帮你判断是单线程打满还是多线程。

**另一种更省事的做法**：`arthas` 的 `thread` 命令
直接列出 CPU 占用最高的线程和对应栈，不需要手工换算：

```bash
thread -n 3        # 显示 CPU 占用最高的 3 个线程及其栈
thread -b          # 直接找阻塞其他线程的线程（死锁检测）
```

### 常见的高 CPU 场景

| 现象 | 栈的特征 | 根因 |
|---|---|---|
| 死循环 | 栈顶是业务方法，行号固定不变 | 循环条件写错 |
| 正则回溯 | 栈里有 `Pattern`、`Matcher` | 灾难性回溯（Catastrophic Backtracking） |
| 频繁 GC | 多个线程停在 GC 相关方法 | 堆不足或泄漏（CPU 高是**结果**不是原因） |
| 锁竞争 | 大量线程 `BLOCKED` | 锁粒度过大 |
| 序列化/反序列化 | Jackson/Fastjson 相关栈 | 大对象或深嵌套 |

**要注意区分「CPU 高」和「CPU 高但是 GC 引起的」**。
如果是 GC 引起的，光看栈会误以为是 GC 线程的问题，
其实要按堆 OOM 的思路去查。判断方法是看 `jstat -gcutil`
的 `GCT`（GC 总耗时）是否在快速上涨。

### jstack 里的线程状态

| 状态 | 含义 |
|---|---|
| `RUNNABLE` | 正在运行或等待 CPU/IO（注意：一次不返回的 socket 读也是 RUNNABLE） |
| `BLOCKED` | 等待 `synchronized` 监视器锁 |
| `WAITING` | 无限期等待，如 `Object.wait()`、`Thread.join()` |
| `TIMED_WAITING` | 限时等待，如 `Thread.sleep()`、`await(timeout)` |

**`RUNNABLE` 不一定是「在算」**：涉及网络 IO 时，
Java 线程在 socket 读上是 `RUNNABLE` 状态但实际在等数据。
所以判断 CPU 问题不能只看状态，要结合栈的内容。

### 死锁的排查

`jstack` 能**自动识别** `synchronized` 与 `ReentrantLock` 死锁：

```
Found one Java-level deadlock:
=============================
"Thread-1":
  waiting to lock monitor 0x00007f... (object 0x0000000..., a java.lang.Object),
  which is held by "Thread-0"
"Thread-0":
  waiting to lock monitor 0x00007f... (object 0x0000000..., a java.lang.Object),
  which is held by "Thread-1"
```

看到这段就是死锁，且它直接指出了互相等待的两个线程和锁对象。
比手工分析栈快得多。

**预防死锁的四条**（也是排查后要落地的修改）：

1. **固定加锁顺序**：所有线程按同一顺序获取多把锁
2. **加超时**：`tryLock(timeout)` 替代无限等待的 `lock()`
3. **减少锁嵌套**：尽量不在持锁时调用外部代码
4. **一次性申请**：需要多把锁时用一个协调者统一分配

## 追问链

### Q1: 怎么判断是内存泄漏还是内存不足？

看**趋势**而不是看快照。具体三个判据：

**判据一：Full GC 后的堆占用**

```bash
jstat -gcutil <pid> 5000
#   S0     S1     E      O      M     CCS    YGC   YGCT   FGC   FGCT     GCT
#  0.00  96.11  62.34  99.87  95.21  92.88  1234  45.67    87  123.45  169.12
```

关键看 **`O`（老年代使用率）在 Full GC 之后能降到多少**：

- Full GC 后 `O` 回落到 30% 左右 → 内存够用，可能有波动但无泄漏
- Full GC 后 `O` 仍在 95% 以上，且 `FGC` 持续增长 → **泄漏**

**判据二：两次 dump 的对象对比**（最可靠）

```
t1：OrderItem 40 万个
t2：OrderItem 320 万个   ← 8 倍增长，且业务量没变 = 泄漏
```

**判据三：业务量是否增长**

如果对象增长与业务量增长同步（流量涨了 10 倍，对象也涨了约 10 倍），
那是容量问题。要区分「绝对值大」和「增速异常」。

#### Q1.1: 那堆该调多大？有通用的调优公式吗？

**没有通用公式**，但有几条可靠的推导路径：

**路径一：按存活对象集反推**

```
堆大小 ≥ 存活对象集大小 × 3
```

乘 3 是为了给新生代和回收过程留余量。
如果存活集是 2GB，堆至少 6GB。
可以通过 Full GC 后的老年代占用来估存活集。

**路径二：按分配速率和 GC 频率反推**

```
新生代大小 ≥ 分配速率(MB/s) × 期望的 Young GC 间隔(s)
```

比如分配速率 200MB/s，希望 10 秒一次 Young GC，
新生代就该有 2GB。

**路径三：从停顿目标反推（G1）**

```
-XX:MaxGCPauseMillis=200    G1 会自己决定每轮回收多少 Region
```

G1 里与其调堆大小，不如设停顿目标让它自适应。

**但调参的前提是先排除泄漏**。如果存在泄漏，
把堆从 4G 调到 16G 只是把 OOM 从 1 小时推迟到 4 小时，
根因还在。**调参是最后一步，不是第一步**。

##### Q1.1.1: 那 Xms 和 Xmx 该怎么设？

**建议设成相等**：

```bash
-Xms8g -Xmx8g
```

三个理由：

1. **避免运行期扩缩堆**。堆扩容要申请新内存并可能触发 Full GC，
   缩容要在 GC 时归还内存，都是额外开销
2. **让 GC 的"已提交内存"稳定**，相关指标（如
   `jstat` 的容量列）才有可比性，便于观察趋势
3. **容器环境下的行为更可预期**。容器里内存超限会被
   OOM Killer 直接杀掉进程（不是抛 OOM 异常），
   堆能扩多少难以预判，不如一次性申请到位

前提是**机器内存要留足堆外空间**：

```
需要的内存 = Xmx + 元空间 + 直接内存 + 线程数 × 栈大小 + JVM 自身 + 代码缓存
```

一个 `-Xmx4g` 的进程 RSS 到 6GB 以上是正常的。
在 K8s 里如果 limit 设成 4G 而 JVM 要求 4G 堆，
必然被 OOM Killer 杀掉——这是容器环境下非常常见的坑。
解法是留出余量（比如 limit 6G 配 `-Xmx4g`），
或让 JVM 感知容器限制（JDK 10+ 默认开启
`UseContainerSupport`，可用 `MaxRAMPercentage` 按比例分配）。

### Q2: 没有 dump 文件的情况下怎么排查内存问题？

只能做**在线快照**，而且要注意开销：

```bash
# ① 先看 GC 趋势，判断是泄漏还是容量问题
jstat -gcutil <pid> 1000 20

# ② 看对象直方图（轻量，几乎无影响）
jmap -histo:live <pid> | head -30
#   :live 会先触发一次 Full GC，所以看到的是存活对象；
#   不加 :live 则包含垃圾对象，更轻量但噪声大

# ③ 导出堆快照（重量级，会 STW 数秒到数十秒）
jmap -dump:live,format=b,file=/tmp/heap.hprof <pid>

# ④ 元空间明细（JDK 11+）
jcmd <pid> VM.metaspace

# ⑤ 类加载器统计
jmap -clstats <pid>
```

**`jmap -dump` 的风险必须说明**：
它会触发 STW，堆越大停越久，在线上可能造成几秒到几十秒的
服务不可用。大堆（16G+）生产环境建议先用 `arthas` 的
`heapdump` 命令（同样是 STW，但可以配合监控择机执行），
或者用 G1 的 `-XX:+HeapDumpBeforeFullGC` 在下次 Full GC 前自动 dump。

**`jmap -histo:live` 也有 STW**（因为它要先 Full GC），
所以「轻量」只是相对 `-dump` 而言。

**最实用的组合**是：`jstat` 看趋势（无影响）→
`arthas` 的 `dashboard` 和 `heapdump` 进一步定位。
`arthas` 免重启接入，这对不能重启的核心服务极其重要。

#### Q2.1: 元空间泄漏怎么确认？

判据是**Full GC 之后 Metaspace 仍持续增长**：

```bash
jstat -gcutil <pid> 5000
# 关注 M（Metaspace 使用率）与 FGCT
```

正常的类加载是**有上限的**（应用启动完成后类数基本稳定），
所以 Metaspace 应该在启动后进入平台期。
如果 Full GC 后它继续涨，就是类加载器泄漏。

进一步定位：

```bash
# 按类加载器统计已加载类数，看哪个加载器在涨
jmap -clstats <pid>

# JDK 11+ 看元空间细分（类、方法、常量池各占多少）
jcmd <pid> VM.metaspace
```

**根因永远是「类加载器无法被回收」**。类卸载的前提是
加载它的类加载器可回收，所以只要有一条强引用链指着
类加载器，它加载的所有类都卸载不了。

典型场景：

```
Tomcat 热部署 → 每次 reload 新建 WebAppClassLoader，
                旧的被某个静态字段或 ThreadLocal 引用住
动态代理/字节码增强 → CGLIB、SkyWalking、Arthas 在运行期生成类
脚本引擎         → Groovy / JSP 每次编译产生新类
ThreadLocal     → 线程池线程长期存活，ThreadLocalMap 的 value
                  强引用了类对象
```

## 常见坑

- **看到 OOM 就直接调大 `-Xmx`** —— 泄漏才是更常见的原因，
  调大只是推迟问题。必须先区分泄漏与容量不足
- **没加 `HeapDumpOnOutOfMemoryError`** —— 事后无法取证。
  这个参数必须在部署时就配置，出问题时补不了
- **把 `GC overhead limit exceeded` 当成普通的堆溢出** ——
  它表达的是「GC 效率低于 2%」，
  是堆不足的预警，阈值可用 `-XX:GCTimeLimit` / `GCHeapFreeLimit` 调整
- **在 `RUNNABLE` 状态上直接下结论说「线程在算」** ——
  socket 读也是 `RUNNABLE`，要结合栈内容判断
- **不把线程号转十六进制就去 jstack 里找** ——
  `top -Hp` 是十进制 TID，`jstack` 的 `nid` 是十六进制，对不上
- **用 `jmap -dump` 时不评估停顿** —— 大堆会 STW 数秒到数十秒，
  线上要先确认影响，或用 arthas 择机执行
- **容器里把 `-Xmx` 设成等于内存 limit** ——
  必然被 OOM Killer 杀掉。必须给元空间、直接内存、线程栈留余量
- **说「元空间默认无限大所以不会 OOM」** ——
  受物理内存和容器限制约束，类加载器泄漏照样 OOM

## 加分点

- 能**先分类再排查**，针对 OOM 类型给出不同的方向，
  而不是所有 OOM 都往堆上想
- 知道 **`GC overhead limit exceeded` 是堆不足的预警**，
  比 `Java heap space` 更早出现，见到它就该开始查泄漏
- 知道区分泄漏与容量不足的**两个硬判据**：
  Full GC 后老年代能否回落、两次 dump 的对象数是否持续增长
- 提到 **MAT 的 Dominator Tree + Path to GC Roots（排除弱引用）**
  这套组合拳，而不是只说「用 MAT 看看」
- 知道**容器环境下 OOM Killer 与 OOM 异常的区别**：
  被 Killer 杀掉时进程直接消失，**没有异常、没有 dump**，
  日志里只会看到进程没了。这个区别决定了排查方向完全不同
- 提到 **`arthas` 的生产价值在于免重启接入**，
  可用于不能重启的核心服务；`thread -b` 能直接检测死锁
- 能把「CPU 100%」和「GC 频繁」区分开：
  后者的根因在内存，`jstat` 的 `GCT` 增速是判断依据
- 提到 `-XX:+HeapDumpBeforeFullGC` / `HeapDumpAfterFullGC`
  可以在指定时机自动 dump，避免手工操作的风险

## 版本差异

| 版本 | 差异 |
|---|---|
| JDK 8 | `jmap` / `jstack` / `jstat` / `jinfo` 均在；无 `jcmd` 的部分子命令 |
| JDK 9 | 引入统一诊断工具 `jcmd`；`jmap -clstats` 可用；JEP 158 统一 JVM 日志（`-Xlog`）取代散落的 `-XX:+PrintGC*` |
| JDK 10 | `UseContainerSupport` 默认开启，JVM 能感知容器内存限制，容器里不再需要手工算 `-Xmx` |
| JDK 11 | `jcmd VM.metaspace` 可看元空间细分；JFR（飞行记录器）开源可用，`-XX:StartFlightRecording` 可低开销持续采集 |
| JDK 14 | `-XX:+HeapDumpOnOutOfMemoryError` 行为不变；JFR 事件更完整 |
| JDK 17+ | JFR 成为排查 CPU/内存/锁问题的首选低开销手段，可在生产常态开启 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 报错为 GC overhead limit exceeded，它的准确含义是？
    options:
      A: 堆内存已被 100% 占满
      B: GC 耗时超过 98% 但回收不到 2% 的堆空间
      C: 元空间不足
      D: 直接内存不足
    answer: B
    analysis: 它的判据是「超过 98% 的时间用于 GC 却只回收不到 2% 的堆」，说明存活对象过多、继续运行只是空转。它往往比 Java heap space 更早出现，是堆不足的预警信号。

  - type: MULTI
    stem: 判断「内存泄漏」而非「容量不足」，可靠的判据有哪些？
    options:
      A: 连续多次 Full GC 后老年代占用仍维持在 95% 以上且 FGC 次数持续增长
      B: 两次间隔半小时的堆 dump 中，同一类对象数量成倍增长而业务量未变
      C: 对象数量随业务流量同步增长
      D: 某个类的实例数在 Histogram 中长期排第一
    answer: AB
    analysis: C 是容量问题的特征（业务涨了对象也涨）。D 单独看没有意义——某个类实例多可能是正常的业务对象，必须结合趋势和支配关系判断。

  - type: JUDGE
    stem: jstack 输出中的 nid 与 top -Hp 显示的线程 ID 可以直接对应，无需转换。
    answer: F
    analysis: top -Hp 显示的是十进制 TID，jstack 的 nid 是十六进制，必须先 printf '%x' 转换。这个换算步骤是线上排查时最容易卡住的地方。用 arthas 的 thread -n 3 可以省掉换算。

  - type: CHOICE
    stem: 在容器（如 K8s）中把 -Xmx 设为与容器内存 limit 相等，最可能的后果是什么？
    options:
      A: JVM 启动失败
      B: 正常，这是标准做法
      C: 进程被内核 OOM Killer 直接杀掉，且没有异常与 dump
      D: 自动扩容容器内存
    answer: C
    analysis: 进程总内存除堆之外还包括元空间、直接内存、线程栈、JVM 自身与代码缓存，超过限制时内核直接杀进程。这种死亡没有 Java 异常、没有 heap dump，日志里只表现为进程消失，排查方向与 OOM 异常完全不同。应留出余量，或用 MaxRAMPercentage 按比例分配。

  - type: JUDGE
    stem: 发现堆内存溢出时，最有效的第一步是调大 -Xmx。
    answer: F
    analysis: 内存泄漏是更常见的原因，调大堆只是把 OOM 推迟，根因仍在。正确顺序是先分类 OOM 类型，再用 jstat 看 Full GC 后老年代能否回落、用两次 dump 对比对象数，先区分泄漏与容量不足，调参放在最后。
````
