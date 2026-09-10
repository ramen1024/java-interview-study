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

### 4.2 六个章节

| 顺序 | 章节标题 | 对应字段 | 写作要求 |
|---|---|---|---|
| 1 | `## 电梯版回答` | `elevator_answer` | **最重要**。30 秒能说完，先说结论再给理由，面试开场直接能用。纯文本，可含行内代码，不要用列表。 |
| 2 | `## 展开讲解` | `detail_md` | 原理层。可用任意 Markdown：小标题、列表、表格、代码块、公式。 |
| 3 | （追问链见 4.3） | `follow_up` | 独立章节，见下 |
| 4 | `## 常见坑` | `pitfalls_md` | 答错就扣分的表述，一条一个列表项，格式：`- **错误说法** —— 为什么错` |
| 5 | `## 加分点` | `bonus_md` | 能让面试官眼前一亮的内容：源码细节、线上案例、横向对比 |
| 6 | `## 版本差异` | `version_diff_md` | 版本行为变化，推荐用表格：`| 版本 | 差异 |` |

### 4.3 `## 追问链` 章节格式

这是本站的核心差异化内容。模拟面试官由浅入深的连续追问，**至少 3 层**。

用 `###` 表示第一层追问，`####` 表示第二层，`#####` 表示第三层。标题必须以
`Q<编号>:` 开头，编号形如 `Q1`、`Q1.1`、`Q1.1.2`。编号即 `q_key`，在同一张卡片内必须唯一。

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
而不是构造方法里（1.8 延迟初始化）。

### Q2: 扩容时元素怎么重新分配？

1.8 不需要重新计算 hash。因为容量翻倍后，元素要么留在原位置 i，
要么移动到 i + oldCap，取决于 `hash & oldCap` 是 0 还是 1。
```

**写法要求**：追问要"像真的面试官"，即下一问必须由上一问的答案自然引出，
而不是并列的另一道题。`Q2` 与 `Q1` 平行是可以的，但 `Q1.1` 必须是在追问 `Q1`。

### 4.4 `## 自测题` 章节格式

用 fenced YAML 代码块，一个 `questions` 列表。字段说明：

```markdown
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
      A: Java 8 之后链表长度达到 8 且数组长度 ≥ 64 会转红黑树
      B: 扩容时元素可能留在原位，也可能移动到 i + oldCap
      C: HashMap 是线程安全的
      D: key 允许为 null
    answer: ABD
    analysis: C 错误，HashMap 非线程安全，并发 put 可能丢数据。

  - type: JUDGE           # 判断
    stem: HashMap 在 Java 8 中扩容采用头插法。
    answer: F
    analysis: Java 7 是头插法（并发扩容会形成环形链表）；Java 8 改为尾插法。

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
```
```

字段规则：

- `type` 只能是 `CHOICE` / `MULTI` / `JUDGE` / `CLOZE`
- `answer`：
  - `CHOICE` → 单个选项字母，如 `B`
  - `MULTI` → 连续字母，如 `ABD`
  - `JUDGE` → `T` / `F`
  - `CLOZE` → 不需要 `answer`，改用 `blanks`
- `blanks`：数组的数组。外层每个元素对应一个空，内层是该空**所有可接受的答案**
  （大小写不敏感、忽略首尾空格）。这样"oldCap"和"oldCap（原容量）"都算对
- `{{1}}` `{{2}}` 是空位占位符，必须与 `blanks` 的顺序一一对应
- 每题必须有 `analysis`（解析），这是学习价值所在
- `difficulty` 可选，默认 2

每张卡片建议 2~4 道题。**至少一道不是 CHOICE**，避免全是背选项。

---

## 五、完整示例

见 `content/01-java-basics/01-hashmap-internals.md`，那是一份符合本规范的样板卡片。

---

## 六、导入行为

导入器扫描 `content/` 并对数据库执行 upsert：

| 内容 | 行为 |
|---|---|
| 新增卡片 | 插入，并为**所有用户**创建一条 `review_state`（状态 New，立即到期） |
| 已有卡片（slug 相同） | 更新内容字段，保留 `review_state` / `user_note` / `user_favorite` |
| 追问链 | 以 `kp_id + q_key` 为键 upsert；Markdown 中已删除的节点会被删除 |
| 自测题 | 以全局 `q_key`（`<卡片slug>-<序号>`）为键 upsert；已删除的会被删除 |
| 关联 | 以三元组 upsert；Markdown 中已删除的会被删除 |
| `related` 指向不存在的 slug | 跳过并记录 WARN，不算失败 |

**幂等性**：同一份内容重复导入结果一致，用户可以放心反复导入。

导入由 Redis 分布式锁保护，避免多实例或重复点击导致并发写冲突。

---

## 七、写作口径

- **title 用面试官提问的口吻**：「HashMap 的底层结构是怎样的？」，而不是「HashMap 底层结构」
- **电梯版回答禁止出现"详见下文"** 这类指代，它必须能独立朗读
- **展开讲解里给出可验证的细节**：类名、方法名、字段名、阈值常量、源码片段。
  「底层用了红黑树」是八股；「`TREEIFY_THRESHOLD = 8`，且需要 `table.length >= 64`，
  否则先走 `resize()` 而不是树化」才是能过面试的回答
- **版本差异必须查证**。Java 8/17/21、MySQL 5.7/8.0、Redis 6/7 的行为变化要写明来源版本
- **禁止编造参数和数字**。不确定的数字宁可写成「大约」也不要给一个精确的错值
