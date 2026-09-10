---
slug: redis-data-structures
title: Redis 的底层数据结构是怎样的？
module: framework-redis
tags: [Redis, SDS, 跳表, listpack, 数据结构]
difficulty: 3
frequency: 3
related:
  - slug: redis-persistence
    type: RELATED
  - slug: redis-expiration-eviction
    type: RELATED
  - slug: redis-big-hot-key
    type: RELATED
  - slug: cache-consistency
    type: RELATED
---

## 电梯版回答

Redis 对外是五种基本类型，但每种类型底层有多种编码，会根据元素个数和值的长度自动切换，用 OBJECT ENCODING 可以看到当前编码。String 用 SDS，也就是简单动态字符串，头部有 len 和 alloc，所以能 O(1) 取长度、二进制安全，还有预分配和惰性释放。List 用 quicklist，本质是 listpack 组成的双向链表；Hash 小的时候用 listpack，大了转 dict；ZSet 小的时候用 listpack，大了转 skiplist 加 dict，dict 负责按成员 O(1) 查分数，skiplist 负责按分数做范围和排名。Set 在元素都是整数且不大时用 intset 有序数组，否则小集合用 listpack，再大转 dict。ziplist 是更老的连续内存结构，Redis 7.0 起被 listpack 取代，因为 listpack 每个元素只记录自己的长度，没有 prevlen，避免了 ziplist 的连锁更新。dict 扩容是渐进式 rehash，用 rehashidx 记录进度，每次操作搬一个桶，期间两张表都要查。zset 用跳表不用红黑树，是因为范围查询和实现都更简单，还能靠 span 支持 O(log n) 的排名查询。

## 展开讲解

### 五种类型与底层编码的对应关系

| 类型 | 可用编码 | 编码选择依据 |
|---|---|---|
| String | `int` / `embstr` / `raw` | 能表示为 long 用 `int`；长度不超过 44 字节用 `embstr`（字符串与对象头一次分配，只读）；否则 `raw` |
| List | `listpack` / `quicklist` | 小列表直接用 `listpack`（Redis 7.2+）；大列表用 `quicklist` |
| Hash | `listpack` / `hashtable` | 字段数和最大 value 长度都不超阈值用 `listpack`；否则 `hashtable` |
| Set | `intset` / `listpack` / `hashtable` | 全是整数且不超 `set-max-intset-entries` 用 `intset`；小集合用 `listpack`（Redis 7.2+）；否则 `hashtable` |
| ZSet | `listpack` / `skiplist` | 不超阈值用 `listpack`；否则 `skiplist`（同时维护一个 dict） |

注意编码名和类型名不是一回事。`OBJECT ENCODING key` 返回的
可能是 `listpack`、`quicklist`、`hashtable`、`intset`、`skiplist`、`int`、
`embstr`、`raw` 之一，拿到的是**实际编码**而不是逻辑类型。

```sql
SET k1 12345
OBJECT ENCODING k1        -- int
SET k2 "hello"
OBJECT ENCODING k2        -- embstr
RPUSH biglist 1 2 3
OBJECT ENCODING biglist   -- listpack（7.2+ 的小列表）
HSET h f v
OBJECT ENCODING h         -- listpack（字段少、值短）
```

### SDS：简单动态字符串

String 类型底层用 SDS，它不是 C 字符串，而是带头的结构体：

```c
struct __attribute__ ((__packed__)) sdshdr8 {
    uint8_t  len;    /* 已用长度 */
    uint8_t  alloc;  /* 已分配容量（不含头和结尾 '\0'） */
    unsigned char flags;  /* 低 3 位标识头部类型 */
    char buf[];      /* 实际字节数组 */
};
```

有 `sdshdr5` / `sdshdr8` / `sdshdr16` / `sdshdr32` / `sdshdr64` 五种头部，
按字符串长度选最省内存的那种，`flags` 的低 3 位记录用的是哪一种。

SDS 相比 C 字符串的四个优势：

1. **O(1) 取长度**。C 字符串要 `strlen` 遍历到 `\0`，SDS 直接读 `len`
2. **二进制安全**。判断结束靠 `len` 而不是 `\0`，所以能存 `\0`、
   图片、序列化后的字节流
3. **不会缓冲区溢出**。追加前先检查 `avail` 并按需扩容
4. **预分配与惰性释放**。见下面的追问，直接影响追加性能

### dict：哈希表与渐进式 rehash

`hashtable` 编码的底层是 dict，标准链地址法哈希表。
它维护**两张哈希表** `ht[0]` 和 `ht[1]`，外加一个 `rehashidx`：

```
rehashidx == -1         → 当前没有在 rehash
rehashidx >= 0          → 正在 rehash，记录了搬迁进度
新节点常数 4             → DICT_HT_INITIAL_SIZE = 1 << 2
```

**为什么需要渐进式**：Redis 单线程，如果一次性对一个大表 rehash，
会把主线程卡住。渐进式的做法是：

```
① 给 ht[1] 分配新容量，rehashidx = 0
② 每次增删改查操作，顺带把 ht[0] 在 rehashidx 处的桶搬到 ht[1]，
   然后 rehashidx++
③ 搬迁期间：查找要同时查 ht[0] 和 ht[1]；
   新增的 key 一律直接写进 ht[1]
④ ht[0] 搬空后，把 ht[1] 变成新的 ht[0]，rehashidx = -1
```

**扩容触发条件是负载因子 `used / size >= 1`**（元素数不少于桶数）。
如果此刻正在执行 RDB / AOF 子进程，为了少触发写时复制（COW）、
避免内存翻倍，扩容阈值会放宽到**负载因子大于 5**才强制扩容。
负载因子很低时也会缩容。

### ziplist 与 listpack：连续内存的紧凑结构

**ziplist** 把整个列表/哈希/有序集合放在**一块连续内存**里，
省掉链表每个节点几十字节的指针开销。每个元素的布局是：

```
<prevlen> <encoding> <entry-data>
```

`prevlen` 记录**前一个元素**的长度，用于从后往前遍历。
代价是：在中间插入或删除元素时，被改动元素的下一个元素的
`prevlen` 可能要变长（1 字节变 5 字节），这会连锁地导致
后面所有元素的 `prevlen` 都要更新，即**连锁更新（cascade update）**，
最坏 O(n²)。

**listpack** 是 Redis 5.0 引入、Redis 7.0 起取代 ziplist 的结构。
它同样是一块连续内存，每个元素记录的是**自己的长度**（backlen 字段），
不从前往后依赖前驱，因此**不存在连锁更新**。
头部固定 6 字节：32 位总字节数 + 16 位元素个数，
末尾用 `LP_EOF`（0xFF）标记结束。

### quicklist：快速列表

List 的 `quicklist` 编码是**双向链表 + 每个节点一个 listpack**：

```c
struct quicklistNode {
    struct quicklistNode *prev;
    struct quicklistNode *next;
    unsigned char *entry;     /* 指向 listpack（PACKED）或单个元素（PLAIN）*/
    size_t sz;
    unsigned int count : 16;  /* 节点内的元素个数 */
    /* ...容器类型、压缩标记等位域 */
};
```

它是两种极端之间的折中：

- 纯双向链表：每个元素一个节点，指针开销大、内存碎片多
- 纯 ziplist/listpack：一块大内存，改动一次要整体 realloc + memmove

quicklist 把 listpack 切成若干节点串成链表，既省内存又避免大块搬迁。
节点内元素个数由 `list-max-listpack-size` 控制；
`list-compress-depth` 还可以让**中间节点**用 LZF 压缩，两端保持不压缩
以便快速 push/pop。

### intset：整数集合

Set 在全都是整数且数量不大时用 intset：

```c
typedef struct intset {
    uint32_t encoding;   /* INTSET_ENC_INT16 / INT32 / INT64 */
    uint32_t length;
    int8_t   contents[]; /* 有序数组，按 encoding 解释 */
};
```

元素**有序存放**，查找用二分（`intsetSearch`），O(log n)。

**类型升级**：如果往一个全是 int16 的集合里插入一个 int64 的数，
会调用 `intsetUpgradeAndAdd` 把整个集合升级到 int64：
重新分配内存并把已有元素逐个转换。**升级只升不降**，
即使后来删掉了那个大数，编码也不会降回 int16。

### skiplist：跳表

ZSet 在数据量大时用 `skiplist` 编码，底层是跳表加一个 dict：

```c
typedef struct zskiplistNode {
    sds ele;
    double score;
    struct zskiplistNode *backward;   /* 后退指针，支持倒序 */
    struct zskiplistLevel {
        struct zskiplistNode *forward;
        unsigned long span;           /* 跨越的节点数，用于算排名 */
    } level[];                        /* 每个节点的多层索引 */
} zskiplistNode;

typedef struct zskiplist {
    struct zskiplistNode *header, *tail;
    unsigned long length;
    int level;
} zskiplist;
```

两个关键常量：`ZSKIPLIST_MAXLEVEL = 32`（最高 32 层）、
`ZSKIPLIST_P = 0.25`（每层晋升概率 1/4）。查找、插入、删除平均 O(log n)。

**为什么 zset 不用红黑树**：

1. **范围查询更简单**。跳表底层本身就是一条有序链表，
   找到起点后顺着 `forward` 一路向右即可；红黑树做范围查询
   需要中序遍历或维护后继指针，实现更绕
2. **实现更简单**。跳表插入删除只需调整指针和更新 `span`，
   不需要红黑树的旋转与重新染色，代码出错概率低
3. **支持排名查询**。靠 `span` 累加可以在 O(log n) 内算出
   `ZRANK` / `ZREVRANK`，红黑树要额外维护子树大小
4. **并发设计友好**。跳表的局部修改涉及节点少，
   更容易做无锁并发（可对比 Java 的 `ConcurrentSkipListMap`）

**为什么 zset 还要配一个 dict**：跳表按分数有序，但
`ZSCORE member` 这类按成员查分数的操作在跳表里是 O(log n)。
配一个 `member -> score` 的 dict 后，
`ZSCORE`、`ZADD` 里判断成员是否存在都变成 O(1)。

### 编码转换阈值

以下都是**上游 Redis 的默认值**（配置文件里的值）：

| 类型 | 配置项 | 默认值 | 超过后的行为 |
|---|---|---|---|
| Hash | `hash-max-listpack-entries` | **512** | 字段数超过则转 `hashtable` |
| Hash | `hash-max-listpack-value` | 64 | 任一 value 超过 64 字节则转 `hashtable` |
| List | `list-max-listpack-size` | **-2** | 见下方说明 |
| Set | `set-max-intset-entries` | 512 | 整数集合元素数超过则转 `hashtable` |
| Set | `set-max-listpack-entries` | 128（Redis 7.2+） | 小集合元素数超过则转 `hashtable` |
| Set | `set-max-listpack-value` | 64（Redis 7.2+） | 任一成员超长则转 `hashtable` |
| ZSet | `zset-max-listpack-entries` | 128 | 元素数超过则转 `skiplist` |
| ZSet | `zset-max-listpack-value` | 64 | 任一成员超长则转 `skiplist` |

`list-max-listpack-size` 的语义要注意：
**正数**表示每个 listpack 节点最多存多少个元素；
**负数**表示按字节大小限制，`-1` 是 4KB、`-2` 是 8KB、`-3` 是 16KB、
`-4` 是 32KB、`-5` 是 64KB。默认值 `-2` 即每个节点约 8KB。

**编码转换是单向的**：一旦从 listpack 转成 hashtable / skiplist，
即使之后删掉元素也不会转回来。

## 追问链

### Q1: SDS 为什么比 C 字符串好？

一句话：它用头部几个字节换来了长度、安全和性能。具体四点：

1. **O(1) 取长度**。C 字符串 `strlen` 要遍历，SDS 直接读 `len`
2. **二进制安全**。按 `len` 判断结尾，能存 `\0` 和任意字节
3. **不会缓冲区溢出**。追加前检查 `avail`，不够就扩容
4. **预分配与惰性释放**，减少频繁 realloc

其中第 3 点和第 4 点是 C 字符串完全没有的，
也是 Redis 敢把 SDS 当通用字节容器用的原因。

#### Q1.1: 那预分配具体是怎么做的？

入口是 `_sdsMakeRoomFor(s, addlen, greedy)`（`sdsMakeRoomFor` 以 `greedy=1`
调用它）。逻辑是：

```c
size_t avail = sdsavail(s);
if (avail >= addlen) return s;          /* 剩余空间够，直接返回，不分配 */

size_t reqlen = newlen = sdslen(s) + addlen;
if (greedy == 1) {
    if (newlen < SDS_MAX_PREALLOC)      /* SDS_MAX_PREALLOC = 1024 * 1024 */
        newlen *= 2;                    /* 小于 1MB：直接翻倍 */
    else
        newlen += SDS_MAX_PREALLOC;     /* 大于 1MB：每次多给 1MB */
}
```

两个设计点：

- **小于 1MB 时翻倍**，是为了让连续追加的均摊成本降到 O(1)；
  **超过 1MB 后每次只多给 1MB**，避免超大字符串一次预留过多内存
- 分配新空间后如果长度跨过了头部类型的边界，
  会换用更大的 `sdshdr8/16/32/64`，这时不能简单 realloc，
  要在新位置分配并把内容拷过去

`greedy=0` 的版本是 `sdsMakeRoomForNonGreedy`，
用于那些不会继续追加的拷贝场景，避免多余预留。

##### Q1.1.1: 那「惰性释放」体现在哪里？

体现在**缩短字符串时不立即回收内存**。

像 `sdstrim`、`sdsrange` 这类操作，只修改 `len` 和
结尾的 `\0`，**不 realloc、不释放多余的 `alloc` 空间**：

```
缩短前：len=100, alloc=200
缩短后：len=50,  alloc=200   ← 多出的 150 字节仍在，只是不再使用
```

这么做的收益是：如果后面还要追加，很可能不需要再分配。
代价是内存不会马上归还给分配器。

需要真正回收时，显式调用 `sdsRemoveFreeSpace(s, would_regrow)`
把 `alloc` 缩到刚好容纳 `len`（参数用来决定是干脆回收多余空间，
还是为可能的再次增长保留一点余量）。

**注意不要把它和 Redis 的 `UNLINK` / lazy free 混淆**：
后者是指令层的异步删除对象机制，SDS 的惰性释放是字符串结构自身的行为。

### Q2: 渐进式 rehash 是怎么做的？为什么必须渐进？

dict 用两张表 `ht[0]`（旧）和 `ht[1]`（新），
用一个 `rehashidx` 记录搬迁进度，`-1` 表示没在 rehash。

```
① rehash 开始：给 ht[1] 分配空间，rehashidx = 0
② 每次对 dict 做增删改查，顺带把 ht[0] 在 rehashidx 位置的桶
   整个搬到 ht[1]，然后 rehashidx++
③ 搬迁期间：
     - 查找：先查 ht[0]，没有再去 ht[1]
     - 新增：一律直接写 ht[1]，不再往 ht[0] 写
④ ht[0] 的桶搬完 → 释放 ht[0]，把 ht[1] 提升为 ht[0]，rehashidx = -1
```

**为什么必须渐进**：Redis 用单线程处理命令，
如果一次 rehash 一个百万级的大表，主线程会被卡住若干毫秒甚至更久，
所有请求都得排队。渐进式把一次大搬迁摊到成千上万次操作里，
把单次延迟从「不可接受」降到「几乎不可感知」。

代价是 rehash 期间的内存会短暂翻倍（两张表同时存在），
以及查找要判断查哪张表。

#### Q2.1: 那 rehash 是什么时候触发的？负载因子是多少？

扩容判断在 `_dictExpandIfNeeded` 里，条件是：
**元素数不少于桶数，即负载因子 `used / size >= 1`**，
满足就把桶数翻倍。此外如果表是空的，先扩容到初始大小
`DICT_HT_INITIAL_SIZE = 4`。

缩容则在负载因子很低时触发（具体阈值由实现控制），
所以 `HDEL` 删光字段后哈希表占用的内存有机会还回去。

这里有一个容易忽略的细节：**负载因子 1 并不算高**。
因为哈希冲突的成本和链长度相关，Redis 选择在装填满一倍时就翻倍，
以控制冲突概率；哈希函数用的是 SipHash，抗碰撞性也更好。

##### Q2.1.1: 那为什么在 RDB / AOF 期间扩容阈值会放宽？

因为这时候可能有 `fork` 出来的子进程在写 RDB 或重写 AOF，
父子进程共享内存页，一旦有写操作就会触发**写时复制（COW）**。

```
如果此刻把哈希表扩容一倍：
  父进程要写大量新页 → 大量 COW → 物理内存可能接近翻倍
```

所以 Redis 在「有子进程在持久化」时会把扩容的强制阈值提高：
代码里 `dict_force_resize_ratio = 5`，即负载因子要**大于 5**
才不惜代价强制扩容，否则就尽量不扩，等持久化结束再说。

这是一个典型的**用一点性能换内存安全**的取舍，
也解释了为什么持久化期间内存曲线会偏高。

### Q3: ziplist 有什么问题？listpack 是怎么解决的？

ziplist 的问题是**连锁更新（cascade update）**。

ziplist 每个元素的头部存 `prevlen`，记录前一个元素的长度。
`prevlen` 是变长编码：前一个元素小于 254 字节时占 1 字节，
否则占 5 字节。于是在中间插入/删除一个元素后，
**下一个元素的 `prevlen` 可能要变长**，这会挤占空间导致
后续元素依次需要调整，最坏情况一路连锁下去，成本 O(n²)。

listpack 的解法是：**每个元素只记录自己的长度（backlen），
不记录前一个元素的长度**。这样修改一个元素不会影响别的元素的编码，
连锁更新被彻底消除。

两种结构都是连续内存、都省指针，区别就在这里：

| | ziplist | listpack |
|---|---|---|
| 元素头部存什么 | `prevlen`（前驱长度）+ encoding | encoding + 自身长度 backlen |
| 从头遍历 | 用自身长度向后走 | 用自身长度向后走 |
| 从尾遍历 | 用 prevlen 向前走 | 用 backlen 向前走 |
| 中间修改 | 可能连锁更新 | 不影响其他元素 |
| 头部 | 10 字节（zlbytes/zltail/zllen） | 6 字节（总字节数 + 元素数） |
| 结束标记 | 0xFF | `LP_EOF`（0xFF） |

**注意一个常见误解**：ziplist 的「压缩」不是指用了压缩算法，
而是指内存布局紧凑（变长编码、少指针）。
真正用压缩算法的是 quicklist 的 `list-compress-depth`，那用的是 LZF。

#### Q3.1: 那 quicklist 为什么又要把 listpack 串成链表？

因为「一整块连续内存」在数据量大时有两个硬伤：

1. **改动要整体搬移**。往一块很大的 listpack 中间插一个元素，
   后面的数据要整体 `memmove`，代价很高
2. **扩容要整体 realloc**。大块内存 realloc 可能找不到合适的连续空间，
   只能搬迁

而**纯链表**又走向另一个极端：每个元素一个节点，
前后指针就要十几到几十字节，内存利用率差。

quicklist 取中间值：**链表负责横向扩展，listpack 负责块内紧凑**。
一个 listpack 大约 8KB（默认 `-2`），块内插入的 memmove 代价可控，
块之间用指针连起来，增删块几乎零成本。

##### Q3.1.1: 那节点里到底放多少元素？怎么控制？

由 `list-max-listpack-size` 控制，默认 `-2`。
正值是「每个节点最多多少元素」，负值是「每个节点最大多少字节」：

```
-1 → 4KB      -2 → 8KB（默认）   -3 → 16KB
-4 → 32KB     -5 → 64KB
```

这个参数是**内存与延迟的调节旋钮**：

- 节点太小 → 节点数量多，链表指针开销变大，内存利用率下降
- 节点太大 → 块内插入/删除的 `memmove` 变慢，单次操作延迟上升，
  而且大块内存 realloc 更容易失败

默认的 `-2`（8KB）是官方在常见负载下推荐的经验值。
另外 `list-compress-depth` 可以把两端之外的中间节点用 LZF 压缩，
两端保持不压缩是为了保证 `LPUSH` / `LPOP` / `RPUSH` / `RPOP` 快。

### Q4: 把一个 Hash 从 100 个字段涨到 600 个字段，底层会发生什么？

会触发**编码转换**。Hash 默认在 `hash-max-listpack-entries = 512`
且最大 value 不超过 `hash-max-listpack-value = 64` 字节时用 `listpack`。
字段数涨到 600，超过 512，就会转成 `hashtable`：

```
OBJECT ENCODING myhash
-- 之前：listpack
-- 之后：hashtable
```

转换的代价不只是结构变化：

- `listpack` 是紧凑连续内存，几乎没有额外指针
- `hashtable` 每个 entry 有 `dictEntry` 和桶指针开销，
  通常还会预留空闲桶以控制冲突

所以转换后**内存占用往往明显上升**，这也是「大 key 危害」的一个来源：
一个本可以紧凑存储的 key 一旦越界，内存可能翻几倍。

#### Q4.1: 那转换之后还能变回来吗？

**不能**。编码转换是单向的：

```sql
-- 转成 hashtable 之后，再把字段删回 10 个
HDEL myhash field1 ... field500
OBJECT ENCODING myhash
-- 仍然是 hashtable，不会回到 listpack
```

哪怕全部字段删光再重新写 3 个字段，也要等这个 key 被删除、
下一次重新创建时才可能重新用 listpack。

这一点对容量规划很重要：**一次峰值把结构顶过阈值，
之后长期占用按大编码计算的内存**。所以对可能突发写入的 key，
要么预留足够内存，要么在设计上避免把大量数据塞进一个 key。

##### Q4.1.1: 那为什么阈值不能设得很大，干脆都别转？

因为 `listpack` 的查找是 **O(n) 线性扫描**。
它没有哈希索引，`HGET` 一个字段要从头遍历，靠变长编码加速而已。

把 `hash-max-listpack-entries` 调得很大，虽然省内存，但会导致：

- 单个 `HGET` / `HSET` 的延迟随字段数线性增长
- Redis 是**单线程**执行命令，一个大 listpack 上的慢操作
  会阻塞后面所有请求，尾延迟急剧恶化
- listpack 扩容/插入要整体 realloc + memmove，大块操作更慢

所以阈值本质上是**内存与 CPU（以及单线程下的尾延迟）的权衡**。
默认值就是官方的平衡点，除非有明确压测数据，不要轻易大幅调整。
调大之前先估算一下 key 的字段数量级和访问模式。

## 常见坑

- **说「Redis 的 zset 用红黑树实现」** —— 错。zset 大编码用的是
  **跳表（skiplist）+ 字典（dict）**：跳表按 `score` 有序，
  支撑范围和排名查询；dict 存 `member -> score`，让 `ZSCORE` 变成 O(1)。
  Redis 从没用过红黑树做 zset
- **说「Redis 的 List 是纯双向链表」** —— 那是很老的版本。
  现在 List 是 `quicklist`：双向链表的每个节点挂一个 listpack
- **说「Hash 字段超过 128 个就转 hashtable」** —— 数字记错了。
  `hash-max-listpack-entries` 的默认值是 **512**，不是 128；
  默认 128 的是 `zset-max-listpack-entries` 和 7.2 起的
  `set-max-listpack-entries`
- **说「ziplist 用了压缩算法」** —— ziplist 的「压缩」指内存布局紧凑
  （变长编码、少指针），不是 LZF 之类的压缩算法。
  真正压缩的是 quicklist 的中间节点（`list-compress-depth`）
- **说「ziplist 和 listpack 是一个东西」** —— 关键区别是 ziplist
  每个元素存前驱长度 `prevlen`，会有连锁更新；listpack 只存自身长度，
  没有这个问题
- **说「编码转换是可逆的，删了数据就变回去」** —— 单向的。
  转成 `hashtable` / `skiplist` 后不会因为数据变少而回退
- **说「intset 在元素变少时会降级回 int16」** —— `intsetUpgradeAndAdd`
  只升级不降级，int16 升到 int64 后就固定是 int64
- **说「渐进式 rehash 期间只查旧表 ht[0]」** —— 两张表都要查，
  新增的 key 一律写 `ht[1]`。漏掉这一点会解释不了
  「rehash 期间为什么新 key 也能查到」
- **认为 SDS 就是 C 字符串** —— SDS 有 `len`、`alloc`、`flags` 头部，
  因此才能 O(1) 取长度、二进制安全
- **说「String 的编码只有 raw 一种」** —— 还有 `int` 和 `embstr`。
  能表示为 long 的用 `int`；长度不超过 44 字节用 `embstr`

## 加分点

- 能说出具体的字段与常量，而不是只说数据结构名：
  SDS 的 `len` / `alloc` / `flags` / `buf`、
  `SDS_MAX_PREALLOC` = 1MB；
  dict 的 `rehashidx`、`DICT_HT_INITIAL_SIZE = 4`、
  `dict_force_resize_ratio = 5`；
  跳表的 `ZSKIPLIST_MAXLEVEL = 32`、`ZSKIPLIST_P = 0.25`
- 知道 **zset 同时维护跳表和 dict**，并能说清分工：
  dict 给 O(1) 的成员查分数，跳表给有序范围与排名；
  这是「用两种结构各取所长」的经典设计
- 知道跳表的 `span` 字段是排名查询的关键：`ZRANK` / `ZREVRANK`
  靠累加 `span` 在 O(log n) 内算出排名，红黑树要额外维护子树大小
- 能对比**跳表 vs 红黑树**并给出工程理由：范围查询天然有序遍历、
  实现简单无旋转、并发设计更友好（对照 Java 的 `ConcurrentSkipListMap`）
- 知道 **SDS 有五种头部**（`sdshdr5/8/16/32/64`），
  按长度选最省的那种，这是很细的内存优化
- 知道 **listpack 是 Redis 5.0 先用在 Stream、7.0 才取代 Hash / ZSet 与 quicklist 节点里的 ziplist**，
  以及 7.0 把配置项从 `*-max-ziplist-*` 改名为 `*-max-listpack-*`
- 能把**编码阈值和「大 key」危害联系起来**：大 key 不只是网络传输慢，
  编码越界导致的内存膨胀也是原因之一
- 知道 dict 的哈希函数用的是 **SipHash**（4.0 起），
  相比早期的 MurmurHash2 抗碰撞攻击更强，降低被构造哈希冲突
  打成 O(n) 链表的风险

## 版本差异

| 版本 | 差异 |
|---|---|
| Redis 3.2 | 引入 `quicklist` 作为 List 编码，取代「双向链表 + ziplist」的组合 |
| Redis 4.0 | dict 哈希函数改用 SipHash；引入 lazy free（`UNLINK`、`FLUSHALL ASYNC` 等异步释放） |
| Redis 5.0 | 引入 `listpack`（最初用于 Stream），作为 ziplist 的替代结构 |
| Redis 7.0 | `listpack` 取代 `ziplist`（Hash / ZSet，以及 quicklist 节点内部）；配置项 `*-max-ziplist-*` 改名为 `*-max-listpack-*` |
| Redis 7.2 | Set 新增 `listpack` 编码；**小 List 可直接编码为 `listpack` 而非 `quicklist`**；新增配置 `set-max-listpack-entries`（默认 128）与 `set-max-listpack-value`（默认 64）；dict 内部结构重构（两张表改为 `ht_table[2]` / `ht_size_exp[2]`），渐进式 rehash 语义不变 |
| Redis 7.4 | `hash-max-listpack-entries` 等阈值默认值保持 512 / 64 不变；`list-max-listpack-size` 默认仍为 `-2`（约 8KB） |

## 自测题

```yaml
questions:
  - type: CHOICE
    stem: 上游 Redis 中 hash-max-listpack-entries 的默认值是多少？
    options:
      A: 64
      B: 128
      C: 512
      D: 1024
    answer: C
    analysis: hash-max-listpack-entries 默认 512，配合 hash-max-listpack-value 默认 64。默认 128 的是 zset-max-listpack-entries，以及 Redis 7.2 起 Set 的 set-max-listpack-entries。很多资料把这两个数字记混，答错会显得只会背书不看配置。

  - type: MULTI
    stem: 关于 SDS（简单动态字符串），下列说法正确的有？
    options:
      A: 头部有 len 和 alloc 字段，因此取长度为 O(1)
      B: 按 len 判断字符串结尾，因此二进制安全，能存 \0
      C: 追加时的预分配策略是小于 1MB 时翻倍，超过后每次多分配 1MB
      D: 缩短字符串时会立即释放多余内存，不会延迟回收
    answer: ABC
    analysis: D 错误。SDS 采用惰性释放，缩短字符串时只改 len 不立即 realloc，多余空间保留在 alloc 中，需要回收时显式调用 sdsRemoveFreeSpace。预分配的上限常量是 SDS_MAX_PREALLOC，值为 1MB。

  - type: JUDGE
    stem: Redis 的 ZSet 在数据量大时底层使用红黑树来保证有序。
    answer: F
    analysis: ZSet 的大编码用的是跳表（skiplist）加一个 dict。跳表按 score 有序，支撑范围查询与排名；dict 存 member 到 score 的映射，让 ZSCORE 为 O(1)。跳表相对红黑树的优势是范围查询和实现更简单，并能用 span 支持排名查询。

  - type: JUDGE
    stem: 一个 Hash 从 listpack 编码转成 hashtable 编码后，删除大部分字段就会自动变回 listpack。
    answer: F
    analysis: 编码转换是单向的。一旦转为 hashtable 或 skiplist，即使之后元素减少也不会回退，只有该 key 被删除、后续重新创建时才可能重新使用小编码。这也是容量规划时要留意的一点：一次峰值越界可能让 key 长期按大编码占用内存。
```
