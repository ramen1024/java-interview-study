---
slug: io-nio-zero-copy
title: BIO、NIO、AIO 有什么区别？零拷贝是怎么实现的？
module: java-basics
tags: [NIO, 零拷贝, IO 模型, sendfile]
difficulty: 3
frequency: 2
related:
  - slug: exception-and-finally
    type: RELATED
  - slug: runtime-memory-layout
    type: RELATED
---

## 电梯版回答

BIO 是「一连接一线程」，读写阻塞，连接数上千后线程本身的内存和切换开销成为瓶颈，这就是 C10K 问题。NIO 从 Java 1.4 引入，核心是 Channel、Buffer、Selector 三件套：Channel 双向、Buffer 承载数据、Selector 让一个线程用多路复用监听大量连接的就绪事件，Linux 上底层是 epoll，线程数与连接数因此解耦。AIO 是 Java 7 的异步模型，提交请求后由系统完成再回调，但 Linux 上原生异步 IO 不完善，JDK 实现长期靠线程池模拟，所以实际很少用。零拷贝解决的是传统 read 加 write 里数据在用户态和内核态来回搬的问题：一次 read 加 write 有 4 次拷贝、4 次上下文切换；mmap 加 write 去掉一次 CPU 拷贝；sendfile 让数据从页缓存直接送到网卡，理想情况只剩 2 次 DMA 拷贝、0 次 CPU 拷贝，Java 的入口是 `FileChannel.transferTo`。

## 展开讲解

### BIO：一连接一线程

`java.net.ServerSocket` + `Socket`，`InputStream.read()` 会阻塞到有数据为止，
所以每个连接都要占一个线程。瓶颈来自线程本身：

- 平台线程是 OS 线程的 1:1 包装，默认栈大小在几百 KB 到 1 MB 量级
- 线程创建、销毁、上下文切换都是内核态开销
- 连接空闲时线程仍然存在，CPU 却在空转调度

连接数到几千以上，线程开销就压垮了系统，这就是经典的 C10K 问题。
Tomcat 早年的 BIO Connector 就是这个模型，现已默认使用 NIO。

### NIO 的三大组件

| 组件 | 代表类 | 作用 |
|---|---|---|
| Channel | `SocketChannel`、`ServerSocketChannel`、`DatagramChannel`、`FileChannel` | 双向的数据通道，可配置非阻塞 |
| Buffer | `ByteBuffer`、`CharBuffer`、`IntBuffer` 等 | 数据的载体，读写通过 position/limit/capacity 三个指针控制 |
| Selector | `java.nio.channels.Selector` | 多路复用器，一个线程监听多个 Channel 的就绪事件 |

典型服务端循环：

```java
ServerSocketChannel server = ServerSocketChannel.open();
server.bind(new InetSocketAddress(8081));
server.configureBlocking(false);

Selector selector = Selector.open();
server.register(selector, SelectionKey.OP_ACCEPT);

while (true) {
    selector.select();                          // 阻塞到有事件就绪
    for (SelectionKey key : selector.selectedKeys()) {
        if (key.isAcceptable()) {
            SocketChannel client = server.accept();
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ);
        } else if (key.isReadable()) {
            // 读数据
        }
    }
    selector.selectedKeys().clear();
}
```

几个容易踩的点：

- `selectedKeys()` 是**累积**的，处理完必须 `clear()`，否则同一事件会被反复处理
- 非阻塞的 `read()` 返回 0 表示暂时没数据，返回 -1 表示对端关闭，两者含义不同
- `FileChannel` **不能**注册到 `Selector`。它不是 `SelectableChannel` 的子类，
  文件 IO 在多数平台上也不支持非阻塞多路复用

### select / poll / epoll 的区别

| 机制 | 数据结构 | 单次调用开销 | 关注 fd 上限 |
|---|---|---|---|
| select | `fd_set` 位图 | 每次把整个集合拷进内核并线性扫描，O(n) | 受 `FD_SETSIZE` 限制（通常是 1024） |
| poll | `pollfd` 数组 | 同样每次全量拷贝并线性扫描，O(n) | 没有 1024 这个限制，但仍是轮询 |
| epoll | 内核维护的红黑树 + 就绪链表 | `epoll_wait` 只返回就绪的 fd，与总 fd 数无关 | 仅受进程可打开 fd 数限制 |

epoll 的三个系统调用是关键：`epoll_create` 创建实例，`epoll_ctl` 注册或修改关注的 fd
（**只在这里把 fd 拷进内核一次**），`epoll_wait` 等待就绪事件。
它还有水平触发（LT，默认）和边沿触发（ET）两种模式，ET 要求 fd 设为非阻塞，
并且必须一次把数据读干净，否则会丢事件。

Java 的 `Selector` 是跨平台抽象，Linux 上是 `sun.nio.ch.EPollSelectorImpl`，
macOS 上是 kqueue 实现，Windows 上走 select 或 wepoll。
Java API 呈现的是水平触发语义：`SelectionKey` 上的 interestOps 会持续生效。

### AIO 为什么在 Linux 上没流行

Java 7 的 NIO.2（JSR 203）提供了异步通道
`AsynchronousSocketChannel`、`AsynchronousServerSocketChannel`、
`AsynchronousFileChannel`，提交后通过 `CompletionHandler<V, A>` 回调或 `Future` 取结果。
Windows 上有 IOCP，是真正的内核级异步。但 Linux 上原生异步 IO（libaio）
主要面向特定文件场景，对套接字支持不好，JDK 的实现长期是靠线程池模拟，
等于把「异步」又转回了「线程池阻塞」，没有解决根本问题。
所以 Netty 早已移除 AIO 传输，主流仍是 NIO（epoll）+ 原生传输。

### 零拷贝：4 次拷贝是怎么来的

传统的「磁盘文件 → 网络」用 `read` + `write`：

```
read(fd, buf, len):
  ① DMA 拷贝：磁盘 → 内核页缓存
  ② CPU 拷贝：内核页缓存 → 用户缓冲区
write(sockfd, buf, len):
  ③ CPU 拷贝：用户缓冲区 → socket 发送缓冲区
  ④ DMA 拷贝：socket 发送缓冲区 → 网卡
```

共 4 次拷贝（2 次 DMA + 2 次 CPU），4 次上下文切换
（`read` 进出内核 2 次，`write` 进出内核 2 次）。

三种优化的对比：

| 方案 | 拷贝次数 | 上下文切换 | 说明 |
|---|---|---|---|
| read + write | 4（2 CPU + 2 DMA） | 4 | 数据在用户态多绕一圈 |
| mmap + write | 3（1 CPU + 2 DMA） | 4 | `mmap` 把页缓存映射到用户地址空间，省掉「内核 → 用户」这一次 CPU 拷贝 |
| sendfile | 2（0 CPU + 2 DMA） | 2 | 数据不进用户态，页缓存直接送网卡（需网卡支持 SG-DMA） |

`sendfile` 是最彻底的方案，代价是**不能在传输过程中修改数据**，
因为数据根本不经过用户态。Java 的入口是：

```java
FileChannel.transferTo(long position, long count, WritableByteChannel target)
FileChannel.transferFrom(ReadableByteChannel src, long position, long count)
```

注意 `transferTo` 的返回值可能小于请求的 `count`（实现会按若干 MB 分片），
所以正确写法是循环直到发完。另外只有目标通道类型适合时才会走 sendfile 优化路径，
不是调了 `transferTo` 就一定是零拷贝。

### DMA 拷贝与 CPU 拷贝

- **DMA 拷贝**：由 DMA 控制器在设备和内存之间搬数据，CPU 只负责发起和收尾，
  不逐字节参与，所以不占用 CPU 时间
- **CPU 拷贝**：由 CPU 执行内存拷贝（类似 `memcpy`），消耗 CPU 周期

所谓「零拷贝」不是一次拷贝都没有——DMA 拷贝仍在，
「零」指的是消除了 CPU 参与的拷贝，以及用户态与内核态之间的数据搬运。

### DirectByteBuffer 与 MappedByteBuffer

`ByteBuffer.allocateDirect(capacity)` 在堆外分配内存，数据不受 GC 管理、不会被移动，
因此做 IO 系统调用时不需要先复制到临时直接缓冲区。用堆上的 `ByteBuffer` 做 IO 时，
HotSpot 会退而求其次，用 `sun.nio.ch.Util` 的线程本地临时直接缓冲区做一次中转。
代价是：

- 分配和释放要走系统调用，比堆内存昂贵
- 受 `-XX:MaxDirectMemorySize` 限制（未显式设置时默认取 `-Xmx` 的值）
- 回收依赖 `Cleaner`（对象被 GC 后才触发），可能出现「堆很空，却报
  `OutOfMemoryError: Direct buffer memory`」，堆 dump 里还看不到

`FileChannel.map(MapMode, position, size)` 返回 `MappedByteBuffer`，
底层是 `mmap`，读写直接落在页缓存，`force()` 才保证刷盘。
它适合大文件的随机访问（读少改多的场景很划算），
缺点同样是释放依赖 GC，且在 Windows 上映射期间文件无法被删除。

## 追问链

### Q1: BIO 为什么撑不住高并发？

因为 BIO 是「一个连接一个线程」，而平台线程是 OS 线程的 1:1 包装，
每个线程有独立的栈空间（几百 KB 到 1 MB 量级），创建、切换、销毁都是内核开销。
大量连接空闲时线程依然占着内存、被调度器轮转，CPU 花在切换而不是干活上。
连接数到几千以上，瓶颈就从网络变成了线程模型，这就是 C10K 问题。

#### Q1.1: 那 NIO 是怎么用一个线程管住上万个连接的？

靠 Channel + Buffer + Selector：Channel 可以配成非阻塞，
`Selector` 把多个 Channel 注册进来，线程阻塞在 `selector.select()` 上，
内核在有事件时唤醒它，返回的 `selectedKeys()` 里只含就绪的连接。
于是线程只在**真正有数据要处理**时才工作，空闲连接不占线程。
这就是多路复用：线程数与连接数解耦，一个（或少数几个）线程即可管理大量连接。

##### Q1.1.1: Selector 底层的 epoll 和 select 有什么本质区别？

区别有三点。第一，select 每次调用都要把整个 fd 集合从用户态拷进内核，
epoll 只在 `epoll_ctl` 注册时拷贝一次。第二，select 返回后还要线性扫描整个集合
才知道谁就绪（O(n)），epoll 由内核维护就绪链表，`epoll_wait` 直接返回就绪的 fd
（开销与就绪数量相关）。第三，select 的 fd 数受 `FD_SETSIZE` 限制（通常 1024），
epoll 只受进程 fd 上限约束。所以连接多且活跃比例低时 epoll 优势明显；
如果几乎所有连接都一直活跃，两者差距会缩小，因为 epoll 还要承担 `epoll_ctl` 的开销。

### Q2: 零拷贝到底「零」掉了什么？

零掉的是**用户态与内核态之间的数据拷贝，以及 CPU 参与的拷贝**。
DMA 拷贝无法消除，因为数据总要落到网卡。
传统 read + write 的 4 次拷贝里，有一次「内核页缓存 → 用户缓冲区」和一次
「用户缓冲区 → socket 缓冲区」是纯粹的中转，这两次才是零拷贝要消灭的目标。
`sendfile` 让数据从页缓存直接到网卡，用户态完全不参与，所以能省掉这两次 CPU 拷贝。

#### Q2.1: sendfile 和 mmap + write 有什么区别？

`mmap + write` 把内核页缓存映射到用户地址空间，省掉了「内核 → 用户」那次拷贝，
但 `write` 仍要把数据从页缓存拷到 socket 发送缓冲区，
所以是 3 次拷贝、仍有用户态参与（可在用户态改数据）。
`sendfile` 更进一步：数据不进用户态，内核直接把页缓存内容送到 socket，
网卡支持 SG-DMA（scatter-gather）时，socket 发送缓冲区里只放
「页缓存地址 + 长度」的描述符，由 DMA 直接从页缓存取数据，
于是只剩 2 次 DMA 拷贝、0 次 CPU 拷贝，上下文切换也降到 2 次。
代价是 `sendfile` 不能修改数据，且源必须是页缓存支持的文件。

##### Q2.1.1: 那 FileChannel.transferTo 一定能走 sendfile 吗？

不一定。`transferTo` 的返回值是「实际传输的字节数」，可能小于请求的 `count`
（实现里按若干 MB 分片），所以必须循环调用直到发完。
是否走 sendfile 优化取决于平台和通道类型：Linux 上从 `FileChannel` 传到
`SocketChannel` 才可能走 sendfile；目标不是套接字时可能退化成用户态拷贝路径。
所以「用了 transferTo 就是零拷贝」是不成立的，要结合平台和目标通道看。

### Q3: DirectByteBuffer 为什么能提升 IO 性能？它有什么代价？

堆上的 `ByteBuffer` 做 IO 时，因为系统调用要求内存地址在 GC 期间固定，
JVM 得先申请一块临时的直接缓冲区把数据拷过去（`sun.nio.ch.Util`
的线程本地缓存），这一拷就是一次额外的 CPU 拷贝。
`DirectByteBuffer` 的数据在堆外，GC 不会移动它，地址稳定，可以直接交给系统调用，
省掉这次中转，这也是它和零拷贝能配合起来的原因。

#### Q3.1: 那它有什么代价，什么时候不该用？

代价有三：分配/释放比堆内存贵（要走系统调用）、受 `-XX:MaxDirectMemorySize`
限制、回收依赖 `Cleaner` 在 GC 后触发，所以可能堆很空却报堆外 OOM。
如果缓冲区很小、创建频繁、又不直接做 IO（比如只在堆内做计算），
用堆上的 `ByteBuffer` 反而更快。堆外内存适合长期存活、反复用于 IO 的大缓冲区。

## 常见坑

- **「NIO 就是 Non-blocking IO」** —— NIO 的本意是 New IO（Java 1.4），
  非阻塞只是它的能力之一；同一个包里还有阻塞式的 `FileChannel`
- **「NIO 一定比 BIO 快」** —— 单个请求的处理不一定更快，Selector 的事件分发
  本身有开销；NIO 胜在**高连接数下的线程成本**，连接很少时 BIO 更简单
- **「epoll 有 O(1) 优势，所以永远比 select 快」** —— 优势只在 fd 多且活跃比例低时；
  活跃连接很多时差距缩小，且 `epoll_ctl` 有系统调用成本
- **「零拷贝就是一次拷贝都没有」** —— DMA 拷贝仍然存在，零的是 CPU 拷贝
  和用户态/内核态之间的数据搬运
- **「transferTo 一次调用就能把文件发完」** —— 返回值可能小于 `count`，必须循环；
  且只有平台和目标通道都合适时才走 sendfile 优化
- **「FileChannel 可以注册到 Selector」** —— 它不是 `SelectableChannel`，
  文件 IO 不能多路复用
- **「DirectByteBuffer 一定比堆缓冲区快」** —— 只有做 IO 时才省一次拷贝；
  小数据、CPU 密集的随机访问用堆缓冲区反而更合适
- **「Selector.selectedKeys() 拿到的就是全部就绪事件」** —— 它是累积集合，
  处理完不 `clear()` 会导致同一事件反复触发

## 加分点

- 能讲清 sendfile 的 SG-DMA 细节：网卡支持 scatter-gather 时，
  socket 发送缓冲区里只放「页缓存地址 + 长度」的描述符，
  DMA 直接从页缓存收数据，CPU 全程不碰数据
- 能举真实用例：Kafka 消费端用 `FileChannel.transferTo` 把日志段零拷贝发给消费者，
  索引文件用 `MappedByteBuffer`；Nginx 的 `sendfile on` 是同一个思路
- 知道堆外内存排查手段：堆 dump 看不到堆外对象，要看 NMT
  （`-XX:NativeMemoryTracking=detail` + `jcmd <pid> VM.native_memory`）
  或 `jcmd <pid> GC.heap_info` 里的直接内存信息
- 知道 `mmap` 释放的麻烦：映射只有在 GC 回收 `MappedByteBuffer` 后才解除，
  Windows 上会导致文件无法删除；Java 9 起可以显式调用
  `sun.misc.Unsafe.invokeCleaner(buffer)`，但它依赖内部 API
- 能提到 Java 的 `Selector` 在 Linux 上用 `EPollSelectorImpl`、
  macOS 用 kqueue、Windows 用 select/wepoll，是跨平台抽象层

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 1.4 | 引入 NIO：`Channel` / `Buffer` / `Selector`，提供非阻塞 IO 与多路复用；`FileChannel.transferTo` / `transferFrom` 是 Java 里零拷贝的入口 |
| Java 7 | NIO.2（JSR 203）：`java.nio.file` 文件 API；异步通道 `Asynchronous*Channel` 与 `CompletionHandler`；try-with-resources 让流关闭更安全 |
| Java 9 | `Buffer` 的 `flip` / `clear` / `rewind` 增加协变返回类型的重写，可链式调用；`Unsafe.invokeCleaner` 可显式释放直接缓冲区与映射 |
| Java 21 | 外部函数与内存 API（FFM，`MemorySegment`）仍为 preview，尚未转正；堆外内存的显式生命周期管理要到后续版本才稳定 |

## 自测题

```yaml
questions:
  - type: CHOICE
    stem: 传统的 read 加 write 把磁盘文件发送到网络，一共有几次拷贝和几次上下文切换？
    options:
      A: 2 次拷贝、2 次上下文切换
      B: 3 次拷贝、4 次上下文切换
      C: 4 次拷贝、4 次上下文切换
      D: 4 次拷贝、2 次上下文切换
    answer: C
    analysis: read 触发「磁盘到页缓存」的 DMA 拷贝和「页缓存到用户缓冲区」的 CPU 拷贝，write 触发「用户缓冲区到 socket 缓冲区」的 CPU 拷贝和「socket 缓冲区到网卡」的 DMA 拷贝，共 4 次；read 和 write 各进出内核两次，共 4 次上下文切换。

  - type: JUDGE
    stem: 零拷贝意味着数据传输过程中一次内存拷贝都不会发生。
    answer: F
    analysis: 零拷贝消除的是 CPU 参与的拷贝以及用户态与内核态之间的数据搬运，DMA 拷贝仍然存在。sendfile 的理想情况下是 2 次 DMA 拷贝、0 次 CPU 拷贝。

  - type: MULTI
    stem: 关于 select、poll、epoll 和 AIO，下列说法正确的有？
    options:
      A: select 的 fd 数量受 FD_SETSIZE 限制，通常是 1024
      B: epoll 每次都把整个 fd 集合拷贝进内核再线性扫描
      C: epoll 通过 epoll_ctl 注册一次，epoll_wait 只返回就绪的 fd
      D: JDK 在 Linux 上的异步通道实现长期靠线程池模拟，所以 AIO 应用不广
    answer: ACD
    analysis: B 错误。每次全量拷贝并线性扫描是 select 和 poll 的做法；epoll 只在 epoll_ctl 注册时拷贝一次，epoll_wait 返回就绪链表中的事件。D 中 Windows 的 IOCP 是真正内核级异步，但 Linux 上原生异步 IO 对套接字支持不好，JDK 实现退化为线程池模拟。
    difficulty: 3
```
