# AGENTS.md

Java 面试学习站。把「读完八股」变成「讲得出来」的学习系统：
六段式知识点卡片 + 面试官追问链 + FSRS 间隔重复 + 自测闭环。

技术栈：Spring Boot 3.5.3 / Java 21 / MyBatis-Plus 3.5.17 / MySQL 8 / Redis 8
+ Vue 3.5 / Vite 8 / TypeScript 5.9 / Element Plus。

## 目录

```
content/     内容源，Markdown + YAML frontmatter（唯一事实源，git 管理）
docs/        content-spec.md（卡片编写契约）、content-backlog.md（待写清单）
backend/     Spring Boot，包结构 com.jis.{auth,content,review,quiz,stats,importer,common,config}
frontend/    Vue 3 SPA，src/{api,stores,router,layouts,components,composables,views}
```

## 命令

```bash
cd backend && mvn spring-boot:run          # 后端，端口 8081
cd backend && mvn test                     # 单元测试（目前仅 FSRS 调度）
cd backend && mvn test -Dtest=FsrsSchedulerTest

cd frontend && pnpm dev                    # 前端，5173，/api 代理到 8081
cd frontend && pnpm build                  # vue-tsc --noEmit && vite build（即类型检查）
cd frontend && pnpm type-check

mysql -uroot -p < backend/src/main/resources/db/schema.sql   # 建库，脚本幂等
```

验证要求：后端改动跑 `mvn test`，前端改动跑 `pnpm build`。**两者都必须真正执行过**，
不要只凭「看起来对」就报告完成。

## 环境前提（易踩）

- **后端端口是 8081，不是 8080**——本机 8080 被另一个服务占用，改回去会启动失败
- **不使用 Docker**。直接用本机已装的 MySQL 8（库名 `jis`，root/root）与 Redis。
  连接信息、JWT 密钥都可用环境变量覆盖：`JIS_DB_USERNAME` / `JIS_DB_PASSWORD` /
  `JIS_DB_HOST` / `JIS_DB_PORT` / `JIS_JWT_SECRET` / `JIS_CONTENT_ROOT` / `JIS_AUTO_IMPORT`
- 启动时会自动导入 `content/`。导入失败**不会**阻止应用启动（这是有意的）
- 前端的 `pnpm-workspace.yaml` 是 pnpm 11 自动生成的（记录允许的较新版本），别删

## 架构约定（改动前必读）

**内容以 Markdown 为唯一事实源，数据库只负责检索与状态。**

- 不要直接改数据库里的内容字段，下次导入会覆盖。改 `content/` 下的 Markdown，
  然后调 `POST /api/admin/content/import`（或重启）生效
- 导入器**只写内容表**，绝不触碰 `review_state` / `user_note` / `user_favorite` /
  `quiz_record`。用户复习到一半重新导入内容，进度不能丢
- 导入器**不自动删除**数据库里未覆盖的卡片，只在结果里列为警告。这是有意的：
  本次导入若有文件解析失败，那张卡片看起来同样是「未覆盖」，自动删除会造成内容丢失
- 一切以业务键 upsert（模块 slug、卡片 slug、追问 qKey、题目 qKey），必须保持幂等

**对外接口一律用业务键（卡片 slug、题目 qKey），不暴露自增 id。**
这既让 URL 可读，也避开 BIGINT 超出 JS 安全整数范围的精度问题。
需要 id 时在服务层换算（`ContentQueryService#findKpId`），那是唯一的转换点。

**响应约定**：HTTP 状态码恒为 200，业务结果走响应体 `code`。
错误码分段：0 成功 / 4xx-5xx 对齐 HTTP 语义 / 1xxx 认证 / 2xxx 内容 / 3xxx 复习与答题。

**核心业务规则**：

- 新卡惰性纳入复习队列——导入时**不**为用户批量建 `review_state`（那是
  O(用户数 × 卡片数) 的写放大）。队列 = 到期卡 + 每日配额内的新卡
- 复习调度用 FSRS-6，公式与默认参数来自官方参考实现。两处**有意简化**写在
  `FsrsScheduler` 的类注释里（不做多步学习计划、不做个性化参数训练），不要当成 bug 修
- 抽题接口绝不下发 `answer` / `blanks` / `analysisMd`（一律为 null），
  由 `withDetail` 参数控制。返回 VO 而不是实体，是为了从结构上避免顺手泄漏答案
- 错题本收录「最近一次作答错误」的题目，答对自动移出。不要改成「曾经错过」——
  那样错题本只增不减

## 技术坑（都真实踩过）

**MyBatis-Plus**

- SQL 注解包在 `<script>` 里时按 XML 解析，比较运算符必须写实体（`&lt;=`）；
  **不在** `<script>` 里时必须写字面量（`>=`）。两者混用会生成字面量 `&gt;=` 导致语法错误
- 3.5.9 起 jsqlparser 被拆成独立依赖，分页插件必须显式引入 `mybatis-plus-jsqlparser`
- 自定义 mapper 方法不要叫 `selectByIds` 等与 `BaseMapper` 同名的方法——
  签名不兼容会编译失败，而内置方法通常已经够用
- `ON DUPLICATE KEY UPDATE` 的右侧被加数必须用**表名限定**（`study_daily.review_count`），
  否则与行别名冲突报 `Column ... is ambiguous`

**Redis 缓存**

- 缓存对象多是 record（final 类型），**不能用 RedisTemplate 的多态反序列化**——
  类型信息写不进去、读回退化成 Map。缓存层用 `StringRedisTemplate` +
  显式 `TypeReference`
- 不要在 config 里定义 `ObjectMapper` Bean：会让 Spring Boot 的 Jackson
  自动配置退让，影响 Web 层 JSON 行为。缓存的 ObjectMapper 在
  `ContentCache` 内部自行构造
- 失效用**版本号**（`jis:cache:v{n}:...` 加 `INCR`），不要改成遍历删除——
  `KEYS` 会阻塞 Redis

**依赖版本**

- **不要换成 Knife4j**：最新版 4.5.0 绑定的 springdoc 2.3 与 Spring Boot 3.5 不兼容，
  会启动失败。现用官方 springdoc 2.8.17
- **TypeScript 停在 5.9**：TS 7 是 Go 重写版，与 vue-tsc 的配合未验证，不要升

**前端**

- Markdown 渲染统一走 `src/composables/useMarkdown.ts`，不要在组件里另建 markdown-it 实例
- 挖空题的占位符 `{{n}}` **必须先在 Markdown 原文里换成高亮安全的标记再渲染**：
  highlight.js 会把 `{{1}}` 切成 `{{` + `<span class="hljs-number">1</span>` + `}}`，
  渲染后占位符不再连续，直接正则替换会一个输入框都渲染不出来
- 挖空题的输入框是注入的 HTML，无法 `v-model`，靠容器上的事件委托
  （`@input`）写回响应式状态。**不要在 computed 里 querySelectorAll 读 DOM**——
  computed 没有响应式依赖会被永久缓存，计数永远停在初始值
- **依赖 DOM 的 watcher 必须加 `flush: 'post'`**。默认的 `'pre'` 时机下，
  回调跑在组件重新渲染**之前**，此时 ref 还是旧值（甚至为 null）。
  `MasteryRadar` 就踩过：容器受 `v-if` 控制，数据到达时 ref 尚未挂上，
  `render()` 提前返回且再无机会执行——页面表现是容器存在、尺寸正常、
  但内部永远为空，既不出图也不走兜底分支，很难定位
- 不要给日历/列表类大元素加 hover 位移（`transform`）+ `transition: all`：
  指针停在边缘时元素会反复进出悬停状态变成移动靶，点击会偶发失败

## 内容编写

写新卡片前**必须**先读 `docs/content-spec.md`（格式契约），
它同时是导入器的解析依据，写错会导入失败。

三个高频 YAML 坑（都真实触发过）：

1. 值以 `@` 开头必须加引号（如 `B: "@PostConstruct → ..."`）
2. 值中间出现「冒号 + 空格」必须加引号，否则报 `mapping values are not allowed here`
   （异常名里带冒号时最容易踩到，如 `OutOfMemoryError: unable to ...`）
3. 挖空题的题干内嵌 ```java 代码块时，外层 YAML 围栏要用**四个反引号**

追问链的编号即层级：`### Q1:` / `#### Q1.1:` / `##### Q1.1.1:`，
`#` 的个数必须与编号段数一致，父编号必须存在（导入器会校验并报错）。

写作口径：追问要像真面试官——下一问由上一问的答案自然引出；
常见坑要写出**错误说法本身**；禁止编造参数和数字，版本差异必须查证。

待写清单见 `docs/content-backlog.md`。

## Java 代码风格

遵循全局规则（链式调用每行一个方法、参数过多时换行且右括号独占一行、
禁止全限定名、逻辑运算符置于行首）。**不要「顺手整理」这些换行**——
它们是有意的可读性要求。

注释只写「代码本身表达不了的约束」，不写「这行在做什么」。
算法与缓存等易错处要在类注释里说明**为什么这样做、以及有意简化了什么**。

## 提交习惯

- 提交信息用中文，简洁概括变更
- 修掉真实缺陷时，在提交信息里记下**根因**（项目历史提交都这么写，
  便于日后回溯）。文档里也维护了一份「过程中修掉的真实缺陷」表格
- 提交前必须跑过验证命令
