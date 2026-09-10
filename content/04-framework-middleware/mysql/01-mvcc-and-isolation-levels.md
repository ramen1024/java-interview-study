---
slug: mvcc-and-isolation-levels
title: MySQL 的 MVCC 是怎么实现的？四种隔离级别分别解决什么问题？
module: framework-mysql
tags: [MySQL, MVCC, 事务, 隔离级别, undo log]
difficulty: 3
frequency: 3
related:
  - slug: cache-consistency
    type: RELATED
  - slug: index-b-plus-tree
    type: RELATED
---

## 电梯版回答

MVCC 靠三个东西配合实现：隐藏字段、undo log 版本链、ReadView。InnoDB 每行都有两个隐藏字段，trx_id 记录最后修改它的事务 ID，roll_pointer 指向 undo log 里的上一个版本，这样所有历史版本串成一条链。查询时生成一个 ReadView，记录当时活跃的事务 ID 列表，然后沿着版本链找「第一个对自己可见的版本」。RC 和 RR 的唯一区别是 ReadView 的生成时机：RC 每次查询都重新生成，所以能看到别人已提交的新数据；RR 只在第一次查询时生成一次并复用，所以整个事务看到的是同一个快照，这就是可重复读。四种隔离级别里，读未提交有脏读，读已提交解决脏读但有不可重复读，可重复读解决不可重复读但理论上还有幻读——MySQL 的 RR 用间隙锁把幻读也基本解决了，串行化则全部加锁。

## 展开讲解

### 四个隐藏字段

InnoDB 的每一行（严格说是聚簇索引的每个记录）都有：

| 字段 | 含义 |
|---|---|
| `DB_TRX_ID`（6 字节） | 最后一次插入或修改这行的事务 ID |
| `DB_ROLL_PTR`（7 字节） | 回滚指针，指向 undo log 中该行的上一个版本 |
| `DB_ROW_ID`（6 字节） | 隐藏主键，仅当表没有主键且没有唯一非空索引时才有 |

注意 `DB_ROW_ID` 是条件存在的：**如果表有主键（或有唯一非空索引），
InnoDB 就用它做聚簇索引，不再生成隐藏主键**。

### undo log 版本链

每次修改都会把旧值写进 undo log，`roll_pointer` 把版本串起来：

```
当前行  trx_id=100   name='C'  roll_ptr ─┐
                                         ↓
undo    trx_id=90    name='B'  roll_ptr ─┐
                                         ↓
undo    trx_id=80    name='A'  roll_ptr = NULL
```

注意是 **`roll_pointer` 从新版本指向旧版本**，形成一条单向链表。
查询时如果当前版本不可见，就顺着指针往回找。

### ReadView 的四个关键字段

```java
class ReadView {
    m_ids;              // 生成 ReadView 时，当前活跃（未提交）的事务 ID 列表
    min_trx_id;         // m_ids 中的最小值
    max_trx_id;         // 生成 ReadView 时应该分配给下一个事务的 ID（即 m_ids 最大值 + 1）
    creator_trx_id;     // 生成本 ReadView 的事务自己的 ID
}
```

### 可见性判断规则

拿到一个版本的 `trx_id` 后，按四条规则判断：

```
① trx_id == creator_trx_id
   → 是我自己改的，可见

② trx_id < min_trx_id
   → 这个事务在我生成 ReadView 之前就已经提交了，可见

③ trx_id >= max_trx_id
   → 这个事务在我生成 ReadView 之后才开启，一定不可见

④ min_trx_id <= trx_id < max_trx_id
   → 事务在生成 ReadView 时已经启动但还没提交
   → 再查 m_ids：
        在 m_ids 里   → 仍活跃，不可见
        不在 m_ids 里 → 已提交，可见
```

**规则 ③ 有个细节容易被问**：`trx_id >= max_trx_id` 直接判不可见，
不需要查 `m_ids`。因为 `max_trx_id` 是「下一个待分配的 ID」，
比它大的都是 ReadView 生成之后才开始的事务。

判断不可见时，就沿着 `roll_pointer` 找上一个版本，重复这套判断，
直到找到可见的版本或走到链尾（链尾说明这行在 ReadView 生成前
还不存在，对该事务不可见）。

### RC 与 RR 的唯一区别

**两者的可见性判断规则完全相同，区别只在 ReadView 的生成时机**：

| | ReadView 时机 | 效果 |
|---|---|---|
| **RC（读已提交）** | **每次 SELECT 都重新生成** | 每次查询都能看到「已提交的最新数据」→ 不可重复读 |
| **RR（可重复读）** | **只在第一次 SELECT 时生成一次**，之后复用 | 整个事务用同一个快照 → 可重复读 |

用一个例子说明：

```
时刻  事务 A（RR 或 RC）                事务 B
t1    BEGIN
t2    SELECT name → 'A'                BEGIN
t3                                      UPDATE name = 'B'; COMMIT
t4    SELECT name → ?                  已提交

RC：t4 重新生成 ReadView，B 已提交 → 读到 'B'   （不可重复读）
RR：t4 复用 t2 的 ReadView，B 不在快照里 → 读到 'A'（可重复读）
```

**这个差异的实现代价**：

- RC 每次查询都要生成 ReadView，开销更大
- RR 复用 ReadView，但需要**长时间保留 undo log**——
  因为可能要用到很旧的版本。这是 RR 下 undo 表空间膨胀的原因

### 三种「读」问题的定义

| 问题 | 现象 |
|---|---|
| **脏读** | 读到其他事务**未提交**的数据（对方回滚了，读到的就是脏数据） |
| **不可重复读** | 同一事务内两次读**同一行**结果不同（别人在中间提交了修改） |
| **幻读** | 同一事务内两次**范围查询**行数不同（别人在中间提交了插入/删除） |

### 四种隔离级别

| 隔离级别 | 脏读 | 不可重复读 | 幻读 | MySQL 默认 |
|---|---|---|---|---|
| 读未提交 RU | ✗ 有 | ✗ 有 | ✗ 有 | |
| 读已提交 RC | ✓ | ✗ 有 | ✗ 有 | |
| **可重复读 RR** | ✓ | ✓ | **基本解决** | ★ |
| 串行化 Serializable | ✓ | ✓ | ✓ | |

**MySQL 的 RR 为什么能解决幻读**——这是关键差异点，
标准 SQL 的 RR 只保证不可重复读，不保证幻读。
MySQL 的 RR 额外靠**间隙锁（Gap Lock）+ 临键锁（Next-Key Lock）**
在范围查询时把区间锁住，阻止其他事务在这个区间插入。

具体做法（这也是 InnoDB 处理幻读的两条防线）：

1. **快照读**（普通 `SELECT`）：靠 MVCC。
   因为复用了 ReadView，别人新插入的行天然不在快照里，看不见
2. **当前读**（`SELECT ... FOR UPDATE` / `LOCK IN SHARE MODE` /
   `UPDATE` / `DELETE`）：靠**临键锁**锁住范围，阻止插入

所以严格说，**MySQL 的 RR 解决幻读靠的是「MVCC + 间隙锁」两套机制配合**，
不是一个机制包打天下。

### 快照读与当前读

| | 语句 | 读取方式 | 是否加锁 |
|---|---|---|---|
| 快照读 | 普通 `SELECT` | 沿版本链找可见版本 | 不加锁 |
| 当前读 | `SELECT ... FOR UPDATE`、`LOCK IN SHARE MODE`、`UPDATE`、`DELETE`、`INSERT` | 读最新版本 | 加锁 |

**这个区分非常重要**，因为大量「MVCC 为什么没生效」的疑问都源于它：

```sql
-- 事务 A
BEGIN;
UPDATE account SET balance = balance - 100 WHERE id = 1;   -- 当前读，加行锁
-- 事务 B（此时发起）
BEGIN;
SELECT * FROM account WHERE id = 1;    -- 快照读，不加锁，能立即返回
SELECT * FROM account WHERE id = 1 FOR UPDATE;  -- 当前读，被阻塞！
```

第二句 `SELECT` 不会被阻塞（快照读），
第三句会被阻塞（当前读要拿锁）。这个差异经常让开发者困惑：
「为什么普通查询能读到，加 FOR UPDATE 就卡住了」。

### RC 为什么是很多互联网公司的选择

MySQL 默认是 RR，但很多公司显式改成 RC：

```sql
-- 查看
SELECT @@transaction_isolation;

-- 设置（可在 my.cnf 里全局配置）
SET GLOBAL transaction_isolation = 'READ-COMMITTED';
```

理由有三个：

**① 减少间隙锁带来的锁冲突**

RR 下范围更新会加间隙锁，锁的范围比 RC 大得多，
并发插入容易被阻塞。RC 下基本只用行锁。

**② 减少死锁**

间隙锁是死锁的重要来源之一（两个事务各持有部分间隙，
再互相申请对方的间隙）。RC 没有间隙锁，死锁概率显著降低。

**③ 配合「双一」配置满足大多数业务**

RC 已经解决了脏读，对绝大多数业务够用。
配合 `binlog_format = ROW` 和
`sync_binlog = 1`、`innodb_flush_log_at_trx_commit = 1`，
能保证数据不丢且主从一致。

代价是**需要业务自己处理不可重复读**——
不能在一个事务里假设「读过的数据不变」。
对「先查余额、再扣减」这类逻辑，
必须用 `FOR UPDATE` 或条件更新的方式，而不能依赖 RR 的快照特性。

### RC 下 binlog 为什么必须用 ROW 格式

这是个很经典的问题。假设 `binlog_format = STATEMENT`
（记录 SQL 语句本身），RC 会出现主从数据不一致：

```
主库：
  t1 事务 A：DELETE FROM t WHERE a > 1     （快照读，匹配到 3 行）
  t2 事务 B：INSERT INTO t VALUES (5)      （提交）
  t3 事务 A：COMMIT

binlog 记录的是「DELETE FROM t WHERE a > 1」
从库重放时执行这条语句，此时 (5) 已经存在 → 删掉 4 行
```

主库删 3 行，从库删 4 行，数据不一致。

**根因**：STATEMENT 格式下，从库重新执行 SQL 时的
环境（数据状态）可能与主库执行时不同。
RC 每次查询生成新 ReadView，更容易出现「语句执行时看到的数据
与 binlog 重放时看到的数据不同」。

**ROW 格式记录的是「每一行的前后镜像」**，
从库不需要重新执行条件判断，直接按行应用变更，因此不会不一致。
所以 **RC + ROW 是标准搭配**，MySQL 也有检查会警告这个组合问题。

## 追问链

### Q1: MVCC 是在什么隔离级别下生效的？

MVCC 在 **RC 和 RR** 下都生效，两者的可见性规则完全相同，
区别只在 ReadView 的生成时机。

**RU（读未提交）下不生效**：它直接读最新版本，不做可见性判断。
**串行化下也不靠 MVCC**：它给所有读取加共享锁，靠锁保证隔离。

一个常见的误解是「MVCC 是 RR 特有的」。实际上：

```
RU   → 不加锁也不做可见性判断，直接读最新数据
RC   → MVCC，每次查询新建 ReadView
RR   → MVCC，首次查询建 ReadView 后复用（+ 间隙锁解决幻读）
串行化 → 全部加锁，不依赖 MVCC
```

#### Q1.1: 那 RR 下 ReadView 具体是什么时候创建的？

准确说是在**第一次快照读（普通 SELECT）时创建**，
而不是 `BEGIN` 时。

```sql
BEGIN;                    -- 此时不创建 ReadView
-- 这里可以做别的事，比如睡 10 秒
SELECT * FROM t;          -- ★ 这时才创建 ReadView
```

这个细节有一个反直觉的后果：

```
t1  事务 A：BEGIN
t2  事务 B：BEGIN; INSERT (1); COMMIT
t3  事务 A：SELECT → 能看到 (1)！
```

因为 ReadView 在 t3 才创建，而 B 在 t2 就已提交，
所以 B 的插入对 A 可见——**A 看到了「事务开始之后才提交的数据」**。

这与「可重复读意味着看到事务开始那一刻的快照」这个直觉不符。
准确的说法是：**RR 保证「事务内多次读取结果一致」，
而不是「读到事务开始时刻的数据」**。

这个区别在排查「为什么我读到了不该读到的数据」时很关键。

##### Q1.1.1: 那 `START TRANSACTION WITH CONSISTENT SNAPSHOT` 是干什么的？

它让 **ReadView 在语句执行的那一刻就创建**，
而不是等到第一次 SELECT：

```sql
START TRANSACTION WITH CONSISTENT SNAPSHOT;   -- ★ 立即创建 ReadView
-- 之后无论等多久，第一次 SELECT 也是用这个快照
SELECT * FROM t;
```

这样就得到了「真正的事务开始时刻快照」的语义，
与上面那个反直觉的行为不同。

**什么时候需要它**：需要在事务开始就确定「读一致性视图」时。
典型是**逻辑备份/数据同步**场景：

```
① START TRANSACTION WITH CONSISTENT SNAPSHOT
② 读表 A（建立一致视图）
③ 读表 B（同视图）
④ ...
⑤ COMMIT
```

这样 A、B、C 三张表的读取都基于同一个时间点，
不会因为「读 B 时有人提交了数据」而出现跨表不一致。
`mysqldump --single-transaction` 就是这个原理。

### Q2: 什么是「当前读」，为什么要区分它？

因为**当前读会绕过 MVCC 去读最新数据，并加锁**。
如果不区分，很多现象无法解释。

前面举过的例子再展开一下，这个场景在生产上非常常见：

```sql
-- 事务 A
BEGIN;
UPDATE stock SET count = count - 1 WHERE goods_id = 1;   -- 持有该行行锁
-- 事务 B
SELECT count FROM stock WHERE goods_id = 1;              -- 快照读，立即返回旧值
SELECT count FROM stock WHERE goods_id = 1 FOR UPDATE;   -- 当前读，阻塞等待
UPDATE stock SET count = count - 1 WHERE goods_id = 1;   -- 阻塞等待
```

**含义**：普通查询读到的是「历史快照」，
而所有写操作读到的都是「最新数据」。这意味着：

- 你查到的库存可能已经是过期的，**不能用来做扣减判断**
- 想基于最新数据做判断，必须用 `FOR UPDATE`（或条件更新）

**这也是「先查再更新」必须加锁的原因**：不加锁的话，
两次查询之间数据可能已经变了。

```sql
-- ❌ 危险：查和更新之间可能被别人插入修改
SELECT count FROM stock WHERE goods_id = 1;   -- 读到 1
-- 别人扣减到 0 并提交
UPDATE stock SET count = 0 WHERE goods_id = 1;  -- 覆盖了别人的修改

-- ✅ 用条件更新，把判断和修改合为一条原子语句
UPDATE stock SET count = count - 1 WHERE goods_id = 1 AND count > 0;
-- 判断受影响行数即可
```

**注意：整个 InnoDB 的写操作都是当前读**，
包括 `INSERT`（要检查唯一约束）、`UPDATE`（要找到并锁住行）、
`DELETE`。

#### Q2.1: 那快照读能读到「自己事务内的修改」吗？

能。这是 ReadView 的**第一条规则**：

```
trx_id == creator_trx_id → 可见（自己改的）
```

```sql
BEGIN;
UPDATE t SET name = 'X' WHERE id = 1;   -- 自己改的
SELECT name FROM t WHERE id = 1;        -- 读到 'X'（不是旧快照值）
```

如果没有这条规则，事务内更新后再查会读到旧值，
逻辑上完全说不通。

**一个容易混淆的点**：如果事务 B 修改了数据但还没提交，
事务 A 的快照读**看不到** B 的修改（B 的 trx_id 在 A 的 m_ids 里）。
这正是「脏读被杜绝」的实现方式。

**另一个细节**：如果一行被事务 A 自己修改过，
它的 `trx_id` 就变成了 A 的 ID，那么后续版本链上
A 自己的修改都会命中第一条规则。这保证了
「事务内自己的修改总是可见」。

## 常见坑

- **说「RR 下 ReadView 在 BEGIN 时创建」** —— 实际在**第一次快照读**时创建。
  这意味着 BEGIN 之后、第一次 SELECT 之前已提交的事务，其修改可见
- **说「MVCC 只在 RR 下生效」** —— RC 也用 MVCC，
  区别只是每次查询都重建 ReadView
- **认为 MySQL 的 RR 完全解决了幻读** —— 快照读靠 MVCC、
  当前读靠间隙锁，是两套机制配合。纯快照读下确实看不到新插入的行，
  但当前读场景仍需间隙锁
- **把「不可重复读」和「幻读」混为一谈** ——
  前者针对**同一行**的值变化，后者针对**结果集行数**变化（插入/删除）
- **忘记区分快照读与当前读** ——
  `UPDATE`、`DELETE`、`SELECT ... FOR UPDATE` 都是当前读，
  读的是最新数据并加锁
- **把 `DB_ROW_ID` 说成总会存在** —— 只有表没有主键且没有
  唯一非空索引时才会生成
- **RC 下仍用 STATEMENT 格式的 binlog** ——
  会导致主从数据不一致，RC 必须配 ROW 格式
- **认为 RR 一定比 RC 好** —— RR 的间隙锁会带来更多锁冲突和死锁，
  很多互联网公司主动选择 RC

## 加分点

- 能**准确说出四条可见性判断规则**，并指出
  `trx_id >= max_trx_id` 时无需查 `m_ids` 的原因
  （那是 ReadView 生成之后才开启的事务）
- 知道 **ReadView 在第一次快照读时创建**，
  并能推导出「BEGIN 之后、首次 SELECT 之前提交的事务可见」
  这个反直觉结论
- 知道 `START TRANSACTION WITH CONSISTENT SNAPSHOT` 的用途，
  并能联系到 `mysqldump --single-transaction` 的一致快照备份原理
- 能讲清 **RC 下 binlog 必须用 ROW 格式**的原因，
  并用主从删除行数不一致的例子说明 STATEMENT 的问题
- 提到 **RR 下 undo log 需要长时间保留**（因为 ReadView 复用），
  这是长事务导致 undo 表空间膨胀、以及
  `history list length` 增长的根因
- 能解释**为什么互联网公司偏好 RC**：减少间隙锁冲突与死锁，
  且配合 ROW binlog 能满足大多数业务
- 提到**长事务的危害**（MVCC 视角）：
  旧 ReadView 会阻止 undo log 清理，
  导致 undo 表空间膨胀、甚至拖慢所有查询。
  所以 `information_schema.innodb_trx` 里长时间运行的事务要告警
- 知道 `DB_TRX_ID` 是 6 字节，所以事务 ID 上限约 2^48，
  达到上限后会回绕（实际工程上无需担心，但能体现看过结构）

## 版本差异

| 版本 | 差异 |
|---|---|
| MySQL 5.7 | 参数名为 `tx_isolation` / `tx_read_only` |
| MySQL 5.7 | 引入 `information_schema.innodb_trx` 等表便于观察长事务 |
| MySQL 8.0 | 参数改名为 `transaction_isolation` / `transaction_read_only`，旧名废弃 |
| MySQL 8.0 | 默认隔离级别仍为 `REPEATABLE-READ`；`binlog_format` 默认 `ROW` |
| MySQL 8.0 | 引入 `performance_schema.data_locks` / `data_lock_waits`，观察锁与死锁比 `innodb_locks` 更方便 |
| MySQL 8.0.30+ | undo 表空间管理改进；支持 `SET PERSIST` 让参数持久化，重启不丢配置 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: MySQL 中 RC 与 RR 在 MVCC 实现上的唯一区别是什么？
    options:
      A: RC 不使用 undo log
      B: RC 每次 SELECT 都重新生成 ReadView，RR 只在第一次快照读时生成并复用
      C: RR 不使用 MVCC
      D: RC 的可见性判断规则与 RR 不同
    answer: B
    analysis: 两者的可见性判断规则完全相同，区别只在 ReadView 的生成时机。RC 每次都新建，因此能看到别人新提交的数据（不可重复读）；RR 复用同一个快照，因此事务内多次读取结果一致。

  - type: MULTI
    stem: 关于 MySQL RR 隔离级别下的幻读，下列说法正确的有？
    options:
      A: 快照读靠 MVCC 复用 ReadView，别人新插入的行不在快照里，因此看不见
      B: 当前读（如 SELECT ... FOR UPDATE）靠临键锁锁住范围，阻止其他事务插入
      C: RR 下幻读被完全消除，不需要任何锁
      D: 间隙锁是 RR 下死锁的重要来源之一
    answer: ABD
    analysis: C 错误。RR 处理幻读靠 MVCC 与间隙锁两套机制配合，且间隙锁只在当前读时才加。间隙锁也是 RR 下死锁的重要来源，这正是很多公司改用 RC 的原因之一。

  - type: JUDGE
    stem: 在 RR 隔离级别下，ReadView 是在执行 BEGIN 语句时创建的。
    answer: F
    analysis: ReadView 在第一次快照读（普通 SELECT）时才创建。因此 BEGIN 之后、首次 SELECT 之前已提交的其他事务，其修改对当前事务是可见的——这与「RR 读到事务开始时刻快照」的直觉不同。准确表述是：RR 保证事务内多次读取一致，而非读事务开始时刻的数据。

  - type: CHOICE
    stem: 关于 InnoDB 的快照读与当前读，下列说法正确的是？
    options:
      A: 普通 SELECT 是当前读，会加锁
      B: UPDATE、DELETE、SELECT ... FOR UPDATE 都是当前读，读取最新数据并加锁
      C: 快照读只能读到其他事务已提交的数据
      D: 当前读不加锁，只是跳过版本链
    answer: B
    analysis: 普通 SELECT 是快照读，沿版本链找可见版本且不加锁；UPDATE、DELETE、INSERT、SELECT ... FOR UPDATE、LOCK IN SHARE MODE 都是当前读，读最新数据并加锁。这个区别解释了「为什么普通查询能立即返回而 FOR UPDATE 会阻塞」。

  - type: JUDGE
    stem: 在 RC 隔离级别下，binlog 使用 STATEMENT 格式不会导致主从数据不一致。
    answer: F
    analysis: RC 每次查询生成新 ReadView，同一条件在不同时刻匹配的行可能不同。STATEMENT 格式记录的是 SQL 语句本身，从库重放时环境可能已变化（例如主库删 3 行、从库删 4 行），导致主从不一致。因此 RC 必须搭配 ROW 格式，记录每行的前后镜像。
    difficulty: 3
````
