# 内容编写规范

本文件是 `content/` 目录下所有 Markdown 文件的**唯一契约**。导入器按本规范解析，
卡片作者（包括 AI 生成的初稿）也必须严格遵循，否则导入会失败或字段丢失。

---

## 一、为什么用 Markdown 作为唯一事实源

内容以 Markdown 存放在仓库里，由导入器解析后 upsert 进 MySQL。这样做的原因：

- **git 友好**：每张卡片的修订都有 diff，可以 review、可以回滚
- **可审校**：不需要开数据库客户端就能改内容
- **数据库只负责检索与状态**：全文索引、知识点关联、复习进度、错题本

因此有一条硬规则：**不要直接改数据库里的内容字段**。下次导入会被覆盖。
要改内容，改 `content/` 下的 Markdown，然后重新导入。

---

## 二、目录结构

```
content/
├── 01-java-basics/
│   ├── _module.yml              # 模块元信息（必需）
│   ├── 01-hashmap-internals.md  # 卡片，一个文件一张
│   └── 02-hashmap-resize.md
├── 02-concurrency/
│   ├── _module.yml
│   └── ...
├── 03-jvm/
│   ├── _module.yml
│   └── ...
└── 04-framework-middleware/
    ├── _module.yml              # 一级模块
    ├── spring/
    │   ├── _module.yml          # 二级模块
    │   └── ...
    ├── mysql/
    └── redis/
```

规则：

- 目录名开头的 `01-` 这类数字前缀**只用于排序**，不参与 slug 生成
- 每个含卡片的目录**必须**有 `_module.yml`
- 子目录若自带 `_module.yml`，则成为该模块的二级子模块（最多两级）
- 卡片文件名 `01-hashmap-internals.md` 中，`hashmap-internals` 是 `slug` 的来源；
  但**以 frontmatter 中的 `slug` 字段为准**，文件名仅作人眼排序用
- **卡片 slug 全局唯一**。跨模块重名会让导入报错，这是有意的，方便双链引用

---

## 三、`_module.yml` 格式

```yaml
name: Java 基础与集合        # 必需，展示名
slug: java-basics            # 必需，全局唯一
description: 集合、String、泛型、反射、异常、IO、新特性
icon: Collection             # 可选，Element Plus 图标名
sort: 1                      # 可选，同级排序，默认取目录名数字前缀
```

---

## 四、卡片文件格式

一张卡片 = 一个 Markdown 文件 = 六个固定章节。顺序不可变，标题文字必须完全一致
（导入器按标题定位章节）。**没有内容的章节可以省略，但顺序不能乱。**

### 4.1 frontmatter（必需）

```yaml
---
slug: hashmap-internals           # 必需，全局唯一，kebab-case
title: HashMap 的底层结构与 put 流程是怎样的？   # 必需，用面试官提问的口吻
module: java-basics               # 必需，所属模块 slug（可以是二级模块）
tags: [HashMap, 集合, 红黑树]      # 必需，至少一个
difficulty: 2                     # 必需，1 易 / 2 中 / 3 难
frequency: 3                      # 必需，面试热度 1 低频 / 2 常见 / 3 高频
related:                          # 可选，知识点双链
  - slug: hashmap-resize
    type: PREREQUISITE            # RELATED(默认) / PREREQUISITE / CONTRAST / DEEPEN
  - slug: hashmap-thread-unsafe
    type: DEEPEN
  - slug: concurrenthashmap
    type: CONTRAST
---
```

`related` 里可以引用**尚未写好**的 slug —— 导入器会跳过并记录警告，等目标卡片
落地后下次导入自动补全。这允许你先画知识网、再填内容。

kebab-case 校验：`slug` 只能是小写字母、数字和连字符。

### 4.2 六个章节

| 顺序 | 章节标题 | 对应字段 | 写作要求 |
|---|---|---|---|
| 1 | `## 电梯版回答` | `elevator_answer` | **最重要**。30 秒能说完，先说结论再给理由，面试开场直接能用。纯文本，可含行内代码，不要用列表。 |
| 2 | `## 展开讲解` | `detail_md` | 原理层。可用任意 Markdown：小标题、列表、表格、代码块、公式。 |
| 3 | `## 追问链` | `follow_up` | 见 4.3，**必填** |
| 4 | `## 常见坑` | `pitfalls_md` | 答错就扣分的表述，一条一个列表项，格式：`- **错误说法** —— 为什么错` |
| 5 | `## 加分点` | `bonus_md` | 能让面试官眼前一亮的内容：源码细节、线上案例、横向对比 |
| 6 | `## 版本差异` | `version_diff_md` | 版本行为变化，推荐用表格 |

`## 电梯版回答` 与 `## 追问链` 是**必填**的，其余章节可省略。顺序按上表排列。

### 4.3 `## 追问链` 章节格式

这是本站的核心差异化内容。模拟面试官由浅入深的连续追问，**至少 3 层**。

用 `###` 表示第一层追问，`####` 表示第二层，`#####` 表示第三层。标题必须以
`Q<编号>:` 开头，编号形如 `Q1`、`Q1.1`、`Q1.1.2`。

- 编号即 `q_key`，同一张卡片内必须唯一
- **`#` 的个数必须与编号段数匹配**：`Q1` 用 `###`，`Q1.1` 用 `####`。
  层级与编号不一致导入器会直接报错，这是为了防止复制粘贴时把追问链挂错父节点
- 父编号必须存在。写了 `Q2.1` 却没有 `Q2` 会报错，不允许断链

```markdown
## 追问链

### Q1: 为什么默认容量是 16？

扩容阈值 = 容量 × 负载因子。16 × 0.75 = 12，容量太小会频繁扩容，
太大会浪费空间。16 是空间与扩容代价的折中，且是 2 的幂次。

#### Q1.1: 为什么容量必须是 2 的幂次？

因为 `(n - 1) & hash` 等价于 `hash % n`，但位运算更快。
只有 n 是 2 的幂时，`n - 1` 的低位才全是 1，取模才等价。

##### Q1.1.1: 那如果构造时传了非 2 的幂次会怎样？

`tableSizeFor()` 会向上取到最近的 2 的幂。
例：传 17 → 实际容量 32。注意这个调整发生在**第一次 put 时**，
而不是构造方法里（Java 8 是延迟初始化）。

### Q2: 扩容时元素怎么重新分配？

Java 8 不需要重新计算 hash。容量翻倍后元素要么留在原位置 i，
要么移动到 i + oldCap，取决于 `hash & oldCap` 是 0 还是 1。
```

**写法要求**：追问要"像真的面试官"，即下一问必须由上一问的答案自然引出，
而不是并列的另一道题。`Q2` 与 `Q1` 平行是可以的，但 `Q1.1` 必须是在追问 `Q1`。

### 4.4 `## 自测题` 章节格式

用 fenced YAML 代码块，一个 `questions` 列表：

````markdown
## 自测题

```yaml
questions:
  - type: CHOICE          # 单选
    stem: HashMap 默认的负载因子是多少？
    options:
      A: 0.5
      B: 0.75
      C: 1.0
      D: 2.0
    answer: B
    analysis: 负载因子 0.75 是空间与查找效率的折中，可在构造时指定。
    difficulty: 1

  - type: MULTI           # 多选
    stem: 以下关于 HashMap 的说法正确的有？
    options:
      A: Java 8 之后链表长度达到 8 且数组长度不小于 64 会转红黑树
      B: 扩容时元素可能留在原位，也可能移动到 i + oldCap
      C: HashMap 是线程安全的
      D: key 允许为 null
    answer: ABD
    analysis: C 错误，HashMap 非线程安全，并发 put 可能丢数据。

  - type: JUDGE           # 判断
    stem: HashMap 在 Java 8 中扩容采用头插法。
    answer: F
    analysis: Java 7 是头插法（并发扩容会形成环形链表）；Java 8 改为尾插法。
```
````

字段规则：

- `type` 只能是 `CHOICE` / `MULTI` / `JUDGE` / `CLOZE`
- `answer`：
  - `CHOICE` → 单个选项字母，如 `B`
  - `MULTI` → 连续字母，如 `ABD`（字母不能重复、必须都是已定义的选项）
  - `JUDGE` → `T` / `F`
  - `CLOZE` → 不需要 `answer`，改用 `blanks`
- `blanks`：数组的数组。外层每个元素对应一个空，内层是该空**所有可接受的答案**
  （判分时大小写不敏感、忽略首尾空格）
- `{{1}}` `{{2}}` 是空位占位符，必须与 `blanks` 顺序对应、**从 1 连续编号**
- 每题必须有 `analysis`（解析），这是学习价值所在
- `difficulty` 可选，默认 2
- 选项数量至少 2 个

**YAML 转义坑**：值以 `@`、`` ` ``、`%`、`*`、`&`、`!`、`|`、`>`、`{`、`[`
等字符**开头**时，YAML 不允许它作为纯量，必须加引号。选项里出现注解
（如 `@PostConstruct`）时极易踩到：

```yaml
options:
  A: "afterPropertiesSet → @PostConstruct → init-method"   # 加引号
  B: AfterConstruct 与 @PostConstruct 的区别               # 不以特殊字符开头，可省略引号
```

**值中间出现「冒号 + 空格」也会报错**（会被当成映射起始），
报错信息是 `mapping values are not allowed here`。异常名里带冒号时
最容易踩到：

```yaml
# ❌ 报 YAML 语法错误
stem: 当线程池报 OutOfMemoryError: unable to create native thread 时……

# ✅ 加引号
stem: "当线程池报 OutOfMemoryError: unable to create native thread 时……"
```

同理，值中间出现「空格 + `#`」会被当成注释，例如 `A: 用 AOP # 这里开始是注释`。
需要保留时也要加引号。

每张卡片建议 2~4 道题。**至少一道不是 CHOICE**，避免全是背选项。

#### 代码挖空题（CLOZE）的围栏注意

题干里要内嵌 `java` 代码块，而 YAML 本身也被围栏包裹，此时 YAML 必须用
**四个反引号**包裹，否则内层的三个反引号会把 YAML 块提前闭合：

````markdown
## 自测题

````yaml
questions:
  - type: CLOZE           # 代码挖空
    stem: |
      补全 Java 8 扩容时判断元素留在原位还是移动的逻辑：
      ```java
      if ((e.hash & oldCap) == {{1}}) {
          // 留在原索引
          loTail.next = e;
      } else {
          // 移动到新索引
          hiTail.next = e;   // 新索引 = j + {{2}}
      }
      ```
    blanks:
      - ["0", "零"]
      - ["oldCap", "oldCap（原容量）"]
    analysis: hash & oldCap 为 0 说明新增的那一位是 0，索引不变。
    difficulty: 3
````
````

这是 CommonMark 的围栏规则：**闭合围栏必须与起始围栏同种字符、且不短于它**。
导入器按此规则解析，因此不内嵌代码块的题目用三个反引号即可。

---

## 五、完整示例

`content/` 下已有的卡片都可以作为模板，其中这几张结构最完整，建议新卡片照着写：

| 卡片 | 可参考之处 |
|---|---|
| `01-java-basics/01-hashmap-internals.md` | 六段式最完整，追问链三层嵌套 + 挖空题的四个反引号写法 |
| `02-concurrency/01-thread-pool-parameters.md` | 执行流程用代码逐段对照讲，追问链围绕「顺序记反了会怎样」展开 |
| `03-jvm/02-garbage-collection-algorithms.md` | 用表格对比三种算法，追问链落到「为什么新生代复制、老年代整理」的成本模型 |
| `04-framework-middleware/spring/02-circular-dependency.md` | 时序图 + 源码片段 + 「为什么必须三级」的反证法 |
| `04-framework-middleware/mysql/02-index-b-plus-tree.md` | 带定量计算（三层 B+ 树能存多少行），把定性描述变成可验证结论 |

其余卡片：`01-java-basics/02-hashmap-thread-unsafe.md`、`03-hashmap-resize.md`、
`04-concurrenthashmap.md`、`02-concurrency/02-why-not-executors.md`、
`03-jvm/01-runtime-memory-layout.md`、`03-oom-troubleshooting.md`、
`04-framework-middleware/spring/01-bean-lifecycle.md`、
`spring/03-transaction-failure-scenarios.md`、
`mysql/01-mvcc-and-isolation-levels.md`、
`redis/01-cache-penetration-breakdown-avalanche.md`、
`redis/02-distributed-lock.md`、`redis/03-cache-consistency.md`。

待编写清单见 [content-backlog.md](content-backlog.md)。

---

## 六、导入行为

导入器扫描 `content/` 并对数据库执行 upsert：

| 内容 | 行为 |
|---|---|
| 新增卡片 | 插入。**不**预先为用户创建 `review_state`，新卡由复习队列按每日配额惰性纳入 |
| 已有卡片（slug 相同） | 更新内容字段，保留 `review_state` / `user_note` / `user_favorite` |
| 追问链 | 以 `kp_id + q_key` 为键 upsert；Markdown 中已删除的节点会被删除 |
| 自测题 | 以全局 `q_key`（`<卡片slug>-<序号>`）为键 upsert；已删除的会被删除 |
| 关联 | 该卡片的关联整体重建；指向不存在目标的会跳过 |
| `related` 指向不存在的 slug | 跳过并记录 WARN，不算失败 |

**幂等性**：同一份内容重复导入结果一致，用户可以放心反复导入。

**按文件隔离失败**：单个文件格式错误只跳过该文件并记入 `errors`，其余内容照常导入。

**不删卡片**：数据库里存在但本次未导入的卡片**不会被自动删除**，只在结果里列为
警告。原因是本次导入若有文件解析失败，那张卡片看起来同样是"未覆盖"，
自动删除会造成内容丢失。需要清理时手动处理。

**不动用户数据**：导入只写内容表，绝不触碰 `review_state`、`user_note`、
`user_favorite`、`quiz_record`。复习到一半重新导入内容，进度不会丢。

**新卡如何进入复习**：导入不会给用户批量建 `review_state`（那是 O(用户数 × 卡片数) 的写放大）。
复习队列由两部分拼成——已到期卡片（`review_state.due_at <= now`）加上
「尚无 `review_state` 的新卡」，新卡每日有配额上限。用户首次给某张卡评分时才创建
`review_state` 行。这与 Anki 的新卡/到期卡模型一致。

导入由 Redis 分布式锁保护，避免多实例或重复点击导致并发写冲突。
改完 Markdown 可以调 `POST /api/admin/content/import` 立即生效，不必重启应用。

---

## 七、写作口径

- **title 用面试官提问的口吻**：「HashMap 的底层结构是怎样的？」，而不是「HashMap 底层结构」
- **电梯版回答禁止出现"详见下文"** 这类指代，它必须能独立朗读
- **展开讲解里给出可验证的细节**：类名、方法名、字段名、阈值常量、源码片段。
  「底层用了红黑树」是八股；「`TREEIFY_THRESHOLD = 8`，且需要 `table.length >= 64`，
  否则先走 `resize()` 而不是树化」才是能过面试的回答
- **版本差异必须查证**。Java 8/17/21、MySQL 5.7/8.0、Redis 6/7 的行为变化要写明来源版本
- **禁止编造参数和数字**。不确定的数字宁可写成「大约」也不要给一个精确的错值
- **常见坑要写"错误说法"本身**，而不是泛泛的"注意细节"。面试官听得出来的差别
  在于你说的是「1.8 用头插法」还是「1.7 才是头插法，1.8 改成尾插修复了并发扩容成环」
