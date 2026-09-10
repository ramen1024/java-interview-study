---
slug: innodb-locks
title: InnoDB 的行锁、间隙锁和临键锁分别是什么？
module: framework-mysql
tags: [MySQL, InnoDB, 锁, 间隙锁, 死锁]
difficulty: 3
frequency: 3
related:
  - slug: mvcc-and-isolation-levels
    type: PREREQUISITE
  - slug: index-b-plus-tree
    type: PREREQUISITE
  - slug: sharding-replication
    type: RELATED
---

## 电梯版回答

InnoDB 的锁按粒度分全局锁、表锁和行锁，支持行锁是它区别于 MyISAM 的核心。表级还多一类意向锁 IS / IX，它不加在数据上，只用来标记「这个表里已经加了行锁」，让加表锁的请求不必逐行检查。行锁有三种形式：记录锁锁住索引记录本身；间隙锁锁住两条索引记录之间的开区间，只在可重复读隔离级别用；临键锁是记录锁加它前面的间隙锁，区间左开右闭，是 RR 下的默认加锁单位。RR 就是靠间隙锁阻止其他事务往查询区间里插入，配合 MVCC 快照读一起解决幻读。必须记住加锁是加在索引上的，不是加在行上的，SQL 没走索引会退化成锁住全表的记录和间隙。另外普通 SELECT 是快照读不加锁，只有 SELECT ... FOR UPDATE 这类当前读才加锁。死锁由 InnoDB 自动检测并回滚其中一个事务，排查看 SHOW ENGINE INNODB STATUS 里的 LATEST DETECTED DEADLOCK。

## 展开讲解

### 锁的粒度：全局锁、表锁、行锁

| 层级 | 代表 | 说明 |
|---|---|---|
| 全局锁 | `FLUSH TABLES WITH READ LOCK`（FTWRL） | 整个实例只读，用于逻辑备份；会阻塞所有写 |
| 表锁 | `LOCK TABLES t READ / WRITE` | 显式表锁，整表串行 |
| 表锁 | MDL（元数据锁） | Server 层自动加，访问表结构时用，长事务会阻塞 DDL |
| 表锁 | `AUTO-INC` 锁 | 自增列分配时用 |
| 表锁 | 意向锁 IS / IX | 表级标记，表示「表里已存在共享 / 排他行锁」 |
| 行锁 | S 锁 / X 锁 | 加在**索引记录**上，InnoDB 存储引擎层实现 |

### 意向锁（IS / IX）解决什么问题

InnoDB 支持行锁，就必须回答一个问题：**另一个事务想加表锁时，怎么知道表里有没有行锁？**
如果逐行扫描去查，代价太高。

意向锁的做法是加一个表级标记：

```
事务要给某行加 S 锁 → 先对表加 IS（Intention Shared）
事务要给某行加 X 锁 → 先对表加 IX（Intention Exclusive）
```

这样加表级 S / X 锁时只需看表上的 IS / IX，**O(1) 就能判断表里有没有行锁**。

意向锁之间是兼容的（IS 与 IS、IS 与 IX、IX 与 IX 都不冲突），
它只和表级的 S / X 锁冲突，兼容矩阵：

| | IS | IX | S | X |
|---|---|---|---|---|
| **IS** | ✓ | ✓ | ✓ | ✗ |
| **IX** | ✓ | ✓ | ✗ | ✗ |
| **S** | ✓ | ✗ | ✓ | ✗ |
| **X** | ✗ | ✗ | ✗ | ✗ |

**关键点：意向锁不阻塞行锁**。你在业务里几乎感觉不到它，
它的唯一作用是让表锁判断变快。

### 行锁的三种形式

| 锁 | 锁的范围 | 生效条件 |
|---|---|---|
| 记录锁 Record Lock | 索引记录**本身** | 所有隔离级别 |
| 间隙锁 Gap Lock | 两条索引记录之间的**开区间** `(a, b)` | **仅 RR（及串行化）** |
| 临键锁 Next-Key Lock | 记录锁 + 它前面的间隙锁，`(a, b]` | RR 的**默认加锁单位** |

用数轴表示（索引值 5、10、15 三条记录）：

```
记录锁   ：      [5]        [10]        [15]
间隙锁   ： (-∞,5)   (5,10)      (10,15)      (15,+∞)
临键锁   ： (-∞,5]   (5,10]      (10,15]      (15,+∞]
```

要点：

- 间隙锁锁的是**开区间**，区间里本来就没有记录，它的语义是
  「不许往这个位置插入」，而不是「锁住某条记录」
- 临键锁是**左开右闭** `(a, b]`：右端点那条记录被记录锁锁住，
  它前面的间隙被间隙锁锁住。这是 RR 下默认的加锁形态
- 还有一类**插入意向锁（Insert Intention Lock）**：`INSERT` 执行前
  在目标间隙上加的一种特殊间隙锁，表示「打算插入」。
  **多个事务往同一个间隙插入时，插入意向锁互不冲突**，
  但它们都会被别人已经持有的间隙锁挡住

### 加锁规则：锁加在索引上，不是加在行上

这是理解一切异常加锁现象的总开关。

**规则一：只对查找过程中访问到的索引记录加锁。**

```sql
-- 主键等值命中：只锁 id = 5 这一条记录
SELECT * FROM t WHERE id = 5 FOR UPDATE;

-- 主键等值未命中：id = 7 不存在，锁住它该在的那个间隙 (5, 10)
SELECT * FROM t WHERE id = 7 FOR UPDATE;
```

**规则二：唯一索引等值查询命中时，临键锁退化为记录锁。**

因为唯一性已经保证不可能再有第二条 `id = 5`，不需要用间隙锁防插入。

**规则三：非唯一索引等值查询，会向右多锁一个间隙。**

```sql
CREATE TABLE t (
    id INT PRIMARY KEY,
    c  INT,
    KEY idx_c (c)
);
INSERT INTO t VALUES (5,5),(10,5),(15,5),(20,5),(25,10);

SELECT * FROM t WHERE c = 5 FOR UPDATE;
```

在 `idx_c` 上先给 `c = 5` 的记录加临键锁，然后继续向右扫描，
直到遇到第一个不满足 `c = 5` 的记录（`c = 10, id = 25`），
对这一段加间隙锁。所以实际锁住的是 `(-∞, 5]` 和 `(5, 10)`。
**这解释了「只查 c = 5，却连 c = 6 都插不进去」的现象。**

**规则四：没有走索引时，锁会退化成锁全表。**

```sql
-- name 上没有索引
UPDATE t SET c = 1 WHERE name = 'abc';
```

InnoDB 只能全表扫描聚簇索引，于是**扫描到的每一条记录和每一个间隙都被加锁**，
效果接近表锁，并发插入几乎全被阻塞。

### RR 下间隙锁如何防幻读

幻读是「同一事务内两次范围查询，第二次多出了别人插入的行」。
InnoDB 的 RR 用两套机制配合：

| 读类型 | 语句 | 防幻读机制 |
|---|---|---|
| 快照读 | 普通 `SELECT` | MVCC。复用同一个 ReadView，别人新插入的行不在快照里，天然看不见 |
| 当前读 | `SELECT ... FOR UPDATE`、`UPDATE`、`DELETE` | 间隙锁 / 临键锁。锁住区间，阻止其他事务插入 |

当前读的例子：

```sql
-- 事务 A（RR）
BEGIN;
SELECT * FROM t WHERE id BETWEEN 10 AND 20 FOR UPDATE;
-- 加临键锁，锁住了包含 10~20 的区间及其中间隙

-- 事务 B
INSERT INTO t (id) VALUES (15);   -- 阻塞！15 落在被锁住的间隙里
```

如果没有间隙锁，B 的插入会成功，A 再次执行同一范围查询就会多出一行，
这就是幻读。**间隙锁锁的不是已存在的行，而是「不允许新行出现的位置」。**

代价也要说清楚：间隙锁会阻塞并发插入，而且**间隙锁之间不冲突**——
两个事务可以同时持有同一个间隙的间隙锁，各自再往里面插入时就会互相等待，
这是 RR 下死锁的重要来源。很多互联网公司把隔离级别降到 RC，
正是为了去掉间隙锁、降低锁冲突和死锁概率。

### 快照读与当前读

| | 语句 | 是否加锁 |
|---|---|---|
| 快照读 | 普通 `SELECT` | **不加锁**，沿 undo log 版本链找可见版本 |
| 当前读 | `SELECT ... FOR UPDATE` | 加 **X 锁**（可能是临键锁） |
| 当前读 | `SELECT ... FOR SHARE`（旧写法 `LOCK IN SHARE MODE`） | 加 **S 锁** |
| 当前读 | `UPDATE`、`DELETE`、`INSERT` | 加锁，读最新数据 |

所以「普通 SELECT 会不会被行锁阻塞」的答案是**不会**，
而「`SELECT ... FOR UPDATE` 会不会」的答案是**会**。

### 死锁：产生、排查、避免

死锁的四个必要条件（互斥、持有并等待、不可剥夺、循环等待）里，
InnoDB 唯一能打破的是循环等待——但业务侧可以主动避免。

**典型死锁（相反加锁顺序）**：

```sql
-- T1
UPDATE t SET c = 1 WHERE id = 1;   -- 持有 id=1 的 X 锁
-- T2
UPDATE t SET c = 1 WHERE id = 2;   -- 持有 id=2 的 X 锁
-- T1
UPDATE t SET c = 1 WHERE id = 2;   -- 等 T2
-- T2
UPDATE t SET c = 1 WHERE id = 1;   -- 等 T1 → 成环，死锁
```

InnoDB 默认开启**死锁检测**（维护等待图 wait-for graph），
发现环后选择**回滚代价较小**的那个事务，向客户端返回
`ERROR 1213 (40001): Deadlock found when trying to get lock; try restarting transaction`。

**排查手段**：

```sql
-- 1. 最近一次死锁的完整现场
SHOW ENGINE INNODB STATUS\G
--    看 LATEST DETECTED DEADLOCK 段：
--    列出每个事务的 HOLDS THE LOCK(S) / WAITING FOR THIS LOCK TO BE GRANTED
--    以及 SQL 和最后一句 WE ROLL BACK TRANSACTION (n)

-- 2. 把所有死锁写进错误日志（便于长期统计）
SET GLOBAL innodb_print_all_deadlocks = ON;

-- 3. 实时看锁与锁等待（MySQL 8.0）
SELECT * FROM performance_schema.data_locks;
SELECT * FROM performance_schema.data_lock_waits;

-- 4. 更易读的聚合视图
SELECT * FROM sys.innodb_lock_waits;

-- 5. 锁等待超时时间（默认 50 秒）
SHOW VARIABLES LIKE 'innodb_lock_wait_timeout';
```

注意区分两种错误：**死锁报 1213**（检测到环，立即回滚一方）；
**锁等待超时报 1205**（`Lock wait timeout exceeded`，等到超时也没等到）。

**避免死锁的工程手段**：

1. **固定加锁顺序**。批量更新时按主键排序再执行，保证所有事务
   以相同顺序申请锁，循环等待就无法形成
2. **缩短事务**。事务里不要做 RPC、不要等用户输入、不要做大批量操作；
   锁持有时间越短，冲突窗口越小
3. **让 SQL 走索引**。缩小加锁范围，避免退化成全表加锁
4. **必要时降到 RC**。RC 基本不用间隙锁，从源头砍掉一大类死锁
5. **拆分大事务**为小批次提交
6. **极端高并发下可关闭死锁检测**（`innodb_deadlock_detect = OFF`），
   用锁等待超时兜底。这是权衡：死锁检测本身要消耗 CPU，
   但关掉后死锁只能靠超时暴露，表现为请求堆积

## 追问链

### Q1: 为什么说「加锁加在索引上」，SQL 没走索引会怎样？

因为 InnoDB 的行锁是加在**索引记录**上的，而定位「哪条索引记录」
必须靠扫描 B+ 树。走索引时扫描范围小，加的锁就少；
不走索引时只能全表扫描聚簇索引，**扫描过程中碰到的每一条记录、
每一个间隙都会被加锁**：

```sql
-- name 无索引
UPDATE t SET c = 1 WHERE name = 'abc';
```

这条语句会锁住聚簇索引上所有记录与间隙，效果接近表锁，
其他事务的插入和更新几乎全部阻塞。这就是线上「一条没走索引的
UPDATE 把整张表锁死」的根因。

**所以第一条优化永远是让加锁的 SQL 走索引**，
而不是去调锁参数。

#### Q1.1: 那如果用的是唯一索引，等值查询还会加间隙锁吗？

分两种情况，取决于**记录是否存在**：

```sql
-- ① 命中：唯一索引保证不会有第二条相同值，间隙锁没有意义
SELECT * FROM t WHERE id = 5 FOR UPDATE;   -- 只加记录锁，不加间隙锁

-- ② 未命中：id = 7 不存在
SELECT * FROM t WHERE id = 7 FOR UPDATE;   -- 加 (5, 10) 上的间隙锁
```

第 ① 种就是「**唯一索引等值查询命中，临键锁退化为记录锁**」。
第 ② 种之所以要加间隙锁，是为了挡住「别人插入 id = 7」——
否则重复执行这条当前读会看到新插入的行，也就是幻读。

**注意这里说的是唯一索引**。普通（非唯一）索引即使命中，
仍然是临键锁，因为同一个值可以重复出现，必须靠间隙锁挡住新插入。

##### Q1.1.1: 那非唯一索引的等值查询，具体会锁住哪些范围？

会**向右多锁一个间隙**。以上面 `idx_c` 的例子
（`c` 的值依次是 5、5、5、5、10）：

```sql
SELECT * FROM t WHERE c = 5 FOR UPDATE;
```

InnoDB 先给 `c = 5` 的记录加临键锁，然后继续向右扫描，
直到遇到**第一个不满足 `c = 5` 的记录**（`c = 10`），
对 `(5, 10)` 这个间隙加间隙锁。

最终锁的范围是 `(-∞, 5]` 加上 `(5, 10)`。

**这个细节能解释一个常见困惑**：明明只查了 `c = 5`，
为什么 `c = 6`、`c = 7` 也插不进去？因为它们都落在
被多锁的那个 `(5, 10)` 间隙里。

反过来说，如果 `c = 5` 后面紧挨着就是 `c = 6`（没有间隙），
那插入 `c = 5.5` 才会被挡，插入 `c = 6` 不受影响。

### Q2: 间隙锁之间会互相阻塞吗？为什么它会导致死锁？

**间隙锁之间不冲突**。两个事务可以同时持有同一个间隙的间隙锁，
因为间隙锁的语义是「不许插入」，它并不排斥另一个也想防插入的事务。

真正冲突的是**间隙锁和插入意向锁**：

```
T1: SELECT * FROM t WHERE id = 5 FOR UPDATE;  -- id=5 不存在，加 (5,10) 间隙锁
T2: SELECT * FROM t WHERE id = 5 FOR UPDATE;  -- 间隙锁之间兼容，T2 也成功

T1: INSERT INTO t (id) VALUES (5);   -- 插入意向锁与 T2 的间隙锁冲突，等待
T2: INSERT INTO t (id) VALUES (5);   -- 插入意向锁与 T1 的间隙锁冲突，等待
                                    -- → 互相等待，死锁
```

这个「两个事务都先拿间隙锁、再各自插入」的模式，
是 RR 下最典型的死锁，也是很多公司改用 RC 的直接原因。

#### Q2.1: 死锁发生时 InnoDB 怎么处理？怎么排查？

InnoDB 默认开启死锁检测，维护一张等待图，一旦发现环就
**回滚其中一个事务**（通常是 undo 量小、代价小的那个），
并给客户端返回 `ERROR 1213`。

排查要看三处：

```sql
-- 最近一次死锁的完整现场（最常用）
SHOW ENGINE INNODB STATUS\G

-- 采集所有死锁，写入 MySQL 错误日志
SET GLOBAL innodb_print_all_deadlocks = ON;

-- MySQL 8.0 实时查看锁与等待关系
SELECT * FROM performance_schema.data_locks;
SELECT * FROM performance_schema.data_lock_waits;
```

死锁日志里要重点读每个事务的两行：

```
HOLDS THE LOCK(S)                      -- 已经持有哪些锁
WAITING FOR THIS LOCK TO BE GRANTED    -- 在等哪把锁
```

把两个事务的「持有」和「等待」拼起来，就能还原出环是怎么形成的，
再定位是哪两条 SQL 加锁顺序相反。

**还要区分死锁和锁等待超时**：死锁报 1213 并立即回滚；
锁等待超时是 `innodb_lock_wait_timeout`（默认 50 秒）到了还没等到，
报 1205，说明存在长时间持锁的事务，但不一定是死锁。

##### Q2.1.1: 业务上怎么减少死锁？

按性价比排序：

1. **固定加锁顺序**：批量更新前按主键排序，让所有事务以同一顺序申请锁。
   这是消除循环等待最直接的手段
2. **缩短事务、缩小锁范围**：事务内不做 RPC 和耗时操作；
   让 SQL 走索引，避免全表加锁
3. **降级到 RC**：从根上去掉间隙锁，砍掉一大类死锁
4. **拆分大事务**：把一次更新几万行拆成多批小事务
5. **热点行改造**：把「热点行频繁 UPDATE」改成「流水插入 + 定时汇总」，
   避免大量事务争抢同一行的 X 锁

最后还有一个反直觉的选项：**高并发下关闭死锁检测**
（`innodb_deadlock_detect = OFF`）。死锁检测本身是 O(n) 的等待图遍历，
热点行场景下可能吃掉大量 CPU；关掉后靠锁等待超时兜底。
这是拿「死锁暴露变慢」换「CPU 不被检测拖垮」的权衡，要谨慎使用。

### Q3: 普通 SELECT 为什么不会和 UPDATE 互相阻塞？

因为普通 `SELECT` 是**快照读**，它走 MVCC 沿 undo log 版本链读历史数据，
**完全不加锁**；而 `UPDATE` 是当前读，会加 X 锁。

```sql
-- 事务 A
BEGIN;
UPDATE account SET balance = balance - 100 WHERE id = 1;   -- 持有 id=1 的 X 锁
-- 事务 B
SELECT * FROM account WHERE id = 1;              -- 快照读，立即返回（可能是旧值）
SELECT * FROM account WHERE id = 1 FOR UPDATE;   -- 当前读，阻塞等待 A 释放锁
UPDATE account SET balance = balance - 100 WHERE id = 1;   -- 阻塞等待
```

这也说明一个业务上的坑：**快照读读到的值不能用来做扣减判断**，
因为它可能是旧值。要做「先判断再更新」必须用 `FOR UPDATE`
或者干脆用条件更新把判断和修改合成一条原子语句。

#### Q3.1: 那 SELECT ... FOR UPDATE 和 FOR SHARE 分别加什么锁？

- `SELECT ... FOR UPDATE`：加 **X 锁**（排他），阻止其他事务加 S 锁或 X 锁
- `SELECT ... FOR SHARE`（旧写法 `LOCK IN SHARE MODE`）：加 **S 锁**（共享），
  S 锁之间兼容，但会阻止 X 锁

两者都是**当前读**，而且加的往往不是单纯记录锁，而是
**临键锁 / 间隙锁**——具体锁什么范围由前面的加锁规则决定，
取决于走的是不是唯一索引、是等值还是范围查询。

```sql
-- 排他锁：拿到后别人既不能读当前读也不能改
SELECT * FROM t WHERE id = 5 FOR UPDATE;

-- 共享锁：别人也能加 S 锁读，但不能改
SELECT * FROM t WHERE id = 5 FOR SHARE;
```

##### Q3.1.1: MySQL 8.0 在加锁语法上有什么变化？

三处值得记：

- `LOCK IN SHARE MODE` **被废弃**，官方推荐改用 `FOR SHARE`
- 新增 **`NOWAIT`**：拿不到锁立即报错返回，不等待
- 新增 **`SKIP LOCKED`**：跳过已被其他事务锁住的行，只返回没被锁的行。
  这是实现「数据库队列」的利器——多个消费者用
  `SELECT ... FOR UPDATE SKIP LOCKED` 抢任务，不会互相阻塞

```sql
SELECT * FROM job_queue
WHERE status = 'PENDING'
ORDER BY id
LIMIT 1
FOR UPDATE SKIP LOCKED;
```

另外，观察锁的手段也换了：8.0 移除了
`information_schema.innodb_locks` / `innodb_lock_waits`，
改用 `performance_schema.data_locks` / `data_lock_waits`。

## 常见坑

- **说「间隙锁锁的是两条记录之间的记录」** —— 错。间隙锁锁的是索引记录之间的**开区间**，
  区间里本来就没有记录它才叫间隙；加在记录本身的叫记录锁。这也解释了
  为什么间隙锁之间不冲突——它保护的是「不许在此插入」这个位置，不是某一行
- **说「临键锁是左闭右开 `(a, b)`」** —— 实际是**左开右闭 `(a, b]`**：
  右端点那条记录被记录锁锁住，它前面的开放间隙由间隙锁锁住
- **说「间隙锁在任何隔离级别都会加」** —— 只在 RR 和串行化下用于普通查询；
  RC 下除唯一键冲突检查和外键检查外基本不加间隙锁，这正是 RC 死锁更少的原因
- **说「没走索引只是查询慢一点，锁的范围不变」** —— 行锁加在索引上，
  没走索引会把扫描到的每条记录和每个间隙都锁上，效果接近锁全表，并发写入被拖垮
- **说「唯一索引等值查询也会锁间隙」** —— 命中时临键锁退化为记录锁，不加间隙锁；
  只有记录不存在（未命中）时才是间隙锁
- **说「普通 SELECT 会加锁」** —— 普通 SELECT 是 MVCC 快照读，不加锁；
  只有 `FOR UPDATE`、`FOR SHARE`、`UPDATE`、`DELETE`、`INSERT` 这些当前读才加锁
- **把「锁等待超时」当成死锁** —— 死锁报 1213，InnoDB 主动回滚一方；
  锁等待超时报 1205，是等 `innodb_lock_wait_timeout`（默认 50 秒）等满了，
  说明有长事务持锁，未必成环
- **说「间隙锁之间会互相阻塞」** —— 不会。间隙锁彼此兼容，
  真正冲突的是间隙锁与插入意向锁，这也是「双方各持间隙锁再互相插入」死锁的成因
- **认为死锁只能靠重启或调大超时解决** —— InnoDB 默认开启死锁检测，
  发现环立即回滚代价小的事务并报 1213；调大 `innodb_lock_wait_timeout`
  只是让非死锁的锁等待更晚报错，治标不治本
- **认为意向锁会严重影响并发** —— IS / IX 之间完全兼容，也不阻塞行锁，
  它只是让加表锁时的判断从 O(n) 变成 O(1)，业务上基本无感

## 加分点

- 能完整背出 InnoDB 的加锁规则：**加锁基本单位是临键锁**；
  只锁查找过程中访问到的对象；**唯一索引等值命中退化为记录锁**；
  **非唯一索引等值查询会向右多锁一个间隙**。这四条能解释几乎所有
  「为什么插不进去 / 为什么被阻塞」的疑问
- 知道**插入意向锁**的存在及其兼容性：多个事务往同一间隙插入互不冲突，
  但都会被已持有的间隙锁挡住——这是 RR 死锁的经典成因
- 能读懂死锁日志：看到 `lock_mode X locks gap before rec` 就知道是间隙锁参与，
  看到 `HOLDS` / `WAITING FOR` 两行就能还原加锁顺序
- 知道 `performance_schema.data_locks` 里能直接看到
  `LOCK_TYPE`（TABLE / RECORD）、`LOCK_MODE`（X、X,GAP 等）和 `LOCK_DATA`，
  比只看死锁日志更实时
- 知道 InnoDB 的**隐式锁**：新插入的记录默认不加显式锁，
  靠记录里的 `trx_id` 判断归属，其他事务要锁它时才补加锁。
  这解释了为什么「刚插入但未提交的行」别人改不了
- 能用 `SELECT ... FOR UPDATE SKIP LOCKED` 实现数据库任务队列，
  说清它相比「先查再锁」避免了消费者之间的互相阻塞
- 把「RC 减少死锁」和 MVCC 卡片里的内容串起来：RC 每次查询重建 ReadView，
  本来就不需要间隙锁来防幻读，因此锁范围更小，是互联网公司选 RC 的理由之一
- 提到 `innodb_deadlock_detect = OFF` 这个反直觉选项，并说清它的代价：
  省下等待图遍历的 CPU，但死锁只能靠锁等待超时暴露

## 版本差异

| 版本 | 差异 |
|---|---|
| MySQL 5.6 | 引入 `innodb_print_all_deadlocks`，可把所有死锁写入错误日志 |
| MySQL 5.7 | 引入 `innodb_deadlock_detect`（默认 ON，可关闭死锁检测）；`information_schema.innodb_locks` / `innodb_lock_waits` 可用于观察锁等待 |
| MySQL 8.0 | 移除 `innodb_locks_unsafe_for_binlog`，间隙锁不能再通过该参数全局关闭 |
| MySQL 8.0 | `information_schema.innodb_locks` / `innodb_lock_waits` 被移除，改用 `performance_schema.data_locks` / `data_lock_waits` |
| MySQL 8.0 | `LOCK IN SHARE MODE` 废弃，改用 `FOR SHARE`；新增 `NOWAIT` 与 `SKIP LOCKED` 两个锁选项 |

## 自测题

```yaml
questions:
  - type: CHOICE
    stem: InnoDB 中临键锁（Next-Key Lock）锁住的区间形态是什么？
    options:
      A: 左闭右开 (a, b)
      B: 左开右闭 (a, b]
      C: 左闭右闭 [a, b]
      D: 左开右开 (a, b)
    answer: B
    analysis: 临键锁等于「记录锁 + 该记录前面的间隙锁」，右端点那条记录被记录锁锁住，它前面的开放间隙被间隙锁锁住，因此是左开右闭 (a, b]。它是 RR 隔离级别下的默认加锁单位。

  - type: MULTI
    stem: 关于 InnoDB 的行锁与加锁规则，下列说法正确的有？
    options:
      A: 行锁加在索引记录上，SQL 没走索引会退化成锁住全表的记录与间隙
      B: 唯一索引等值查询命中时，临键锁退化为记录锁
      C: 普通 SELECT 是快照读，不加锁
      D: 间隙锁之间会互相阻塞
    answer: ABC
    analysis: D 错误。间隙锁之间是兼容的，两个事务可以同时持有同一个间隙的间隙锁；真正冲突的是间隙锁与插入意向锁，这也是「双方各持间隙锁再互相插入」导致死锁的成因。

  - type: JUDGE
    stem: MySQL 中出现 ERROR 1213 与 ERROR 1205 都表示发生了死锁。
    answer: F
    analysis: ERROR 1213 是 InnoDB 死锁检测发现等待环后主动回滚一方；ERROR 1205 是锁等待超时，即等满了 innodb_lock_wait_timeout（默认 50 秒）仍未拿到锁，说明有长事务持锁，未必形成死锁。两者排查方向不同。

  - type: CHOICE
    stem: 关于 SELECT ... FOR UPDATE，下列说法正确的是？
    options:
      A: 它是快照读，不加锁
      B: 它是当前读，加 X 锁，具体可能是记录锁或临键锁
      C: 它只加表级意向锁，不加行锁
      D: 它加的是 S 锁，其他事务仍可修改这行
    answer: B
    analysis: FOR UPDATE 是当前读，读取最新数据并加排他锁（X 锁）。加的锁形态取决于索引与查询类型：唯一索引等值命中时是记录锁，非唯一索引或范围查询时可能是临键锁。快照读不加锁的是普通 SELECT；加共享锁的是 FOR SHARE（旧写法 LOCK IN SHARE MODE）。
    difficulty: 3
```
