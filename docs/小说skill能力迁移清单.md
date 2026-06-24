# 小说skill 全量能力迁移清单

版本：V1.0  
日期：2026-06-20  
用途：在删除 `小说skill/` 目录前，保留其全部功能、资产和产品化映射  
结论：`小说skill/` 可以删除，但其能力需要以本文档为基线进入产品、设计和研发排期

## 1. 迁移原则

`小说skill/` 不是当前项目的正式运行时依赖，而是一套网文创作方法论、提示词流程、Agent 角色、Hook 规范、采集脚本和样例素材。迁移方式不是把目录原样复制进产品，而是将其中能力拆成页面、任务、服务、提示词模板、质量规则和后续迭代模块。

迁移原则：

- 能力全部纳入产品蓝图，不遗漏。
- MVP 聚焦写小说、改小说、小说转短剧分场稿。
- 扫榜、拆文库、封面、多 Agent 审查、浏览器采集进入后续版本，但保留完整设计入口。
- `.agents` 与 `.claude` 下的 skills 基本重复，迁移时以 `.agents/skills` 为主清单。
- `.claude/agents`、hooks、rules、templates 视为工程资产，转化为平台内的审查角色、质量规则、自动检查和项目模板。
- 两个 DOCX 样例保留为验收样例，不作为长期运行依赖。

## 2. 顶层能力总览

`小说skill/` 下共有 13 个核心 skill：

| Skill | 原能力 | 产品化模块 | 纳入阶段 |
|---|---|---|---|
| `story` | 网文工具箱主入口和意图路由 | 创作中心 AI 意图入口 | V1 |
| `story-long-write` | 长篇网文写作 | 长篇创作工作台 | V1 |
| `story-short-write` | 短篇网文写作 | 短篇创作工作台 | V1 |
| `story-import` | 逆向导入已有小说 | 作品导入与项目反向解析 | V1 |
| `story-deslop` | 网文去 AI 味 | 改小说/润色/去 AI 味 | V1 |
| `story-review` | 多视角对抗式审查 | 审查中心/质量报告 | V1.5 |
| `story-long-analyze` | 长篇拆文 | 拆文库/对标分析 | V1.5 |
| `story-short-analyze` | 短篇拆文 | 短篇拆文库/爆点分析 | V1.5 |
| `story-long-scan` | 长篇扫榜 | 市场洞察/长篇榜单分析 | V2 |
| `story-short-scan` | 短篇扫榜 | 市场洞察/短篇风口分析 | V2 |
| `story-cover` | 小说封面生成 | 封面生成器 | V2 |
| `story-setup` | 写作基础设施部署 | 项目模板/规则/Agent 配置中心 | V2 |
| `browser-cdp` | Chrome CDP 自动化 | 榜单采集与登录态数据抓取 | V2 |

## 3. 主入口与意图路由

### `story`

原功能：

- 根据用户意图自动路由到写长篇、写短篇、拆文、扫榜、去 AI 味、封面、导入、环境部署、浏览器采集等 skill。
- 感知当前项目状态、活跃书目、多书切换。
- 在意图不明确时引导用户选择下一步。

产品化设计：

- 在「创作中心」新增 AI 意图入口。
- 用户输入“我要写小说”“帮我改稿”“把这本改成短剧”“帮我扫榜”等自然语言时，系统自动推荐对应功能卡片。
- 多书切换转为作品库中的“当前工作项目”。

## 4. 写小说能力

### `story-long-write` 长篇写作

原能力：

- 长篇开书。
- 从情绪目标反推题材。
- 结合扫榜和拆文确定选题。
- 世界观、人物、金手指、势力、主线、支线、卷纲、章纲。
- 日更续写。
- 章节大修、回炉、重写。
- 对标书召回和跨书参考。
- 角色状态、伏笔、设定、上下文追踪。
- 题材公式、爽点、反转、感情线、对话、文风、质量检查。

产品化模块：

- 长篇开书向导。
- 设定管理。
- 人物关系管理。
- 大纲/卷纲/章纲管理。
- 章节编辑器。
- 日更续写任务。
- 章节大修任务。
- 对标资料引用。
- 伏笔和状态追踪。

### `story-short-write` 短篇写作

原能力：

- 短篇从构思到成稿。
- 以情绪为目标函数。
- 一个反转撑一篇。
- 开头 3 句定生死，结尾定传播。
- 默认第一人称。
- 支持知乎盐言、黑岩、点众、七猫短篇、番茄短篇等平台风格。
- 对标短篇拆文结果，读取故事核、情节节点、写作手法、原文和 `_meta.json`。

产品化模块：

- 短篇开篇向导。
- 情绪目标选择。
- 反转结构设计。
- 小节大纲。
- 短篇正文编辑器。
- 爆点和结尾传播性检查。

## 5. 导入与反向解析

### `story-import`

原能力：

- 接收单文件、目录或粘贴文本。
- 自动检测书名、章节数、字数、章节格式。
- 识别长篇/短篇，灰区时让用户确认。
- 判断是否完本、最后一章是否残稿。
- 先走拆文管道，再迁移为标准项目结构。
- 标注 `[导入反推]`，提醒用户复核。
- 兼容长篇和短篇后续写作。

产品化模块：

- 作品导入。
- DOCX/TXT/Markdown 解析。
- 章节切分。
- 篇幅识别。
- 残稿检测。
- 反向项目生成。
- 导入来源标记。

## 6. 改小说与去 AI 味

### `story-deslop`

原能力：

- 扫描 AI 味。
- 按轻度、中度、重度分级。
- 禁用词检测。
- 句式套路检测。
- 抽象心理描写检测。
- 节奏工整检测。
- 对话标签和书面腔检测。
- 结尾升华/总结体检测。
- 保留剧情、人设、设定，不改变“说什么”，只改变“怎么说”。
- 支持白名单 `.deslop-whitelist`。
- 控制删除比例，避免过度去味。

产品化模块：

- 改小说中的“去 AI 味”模式。
- AI 味检测报告。
- 段落级问题标注。
- 白名单/专有名词保护。
- 改写前后差异对照。

## 7. 审查能力

### `story-review`

原能力：

- full、lean、solo 三种审查模式。
- full 模式调用 story-architect、character-designer、narrative-writer、consistency-checker。
- lean 模式调用 story-architect 和 consistency-checker。
- Agent 不可用时自动降级 solo。
- 平台 rubric：番茄、起点、知乎盐言、通用网文。
- 统一 Findings Schema。
- 严重度 S1-S4。
- 审查结构、角色、文字、设定、一致性、平台适配、格式。

产品化模块：

- 审查中心。
- 多视角审查报告。
- 平台化评分。
- 问题列表和修复建议。
- 自动降级与基础规则 fallback。

## 8. 拆文能力

### `story-long-analyze` 长篇拆文

原能力：

- 拆解爆款长篇小说。
- Stage 0 概要和章节边界。
- Stage 1 黄金三章深度拆解，产出快速预览并停靠。
- Stage 2 逐章摘要。
- Stage 3 剧情聚合和角色合并。
- Stage 4 设定、角色档案、角色关系。
- Stage 5 汇总拆文报告。
- Stage 6 文风分析。
- 支持断点续跑、已有结果复用、原文备份、质量门控。
- 产出 `拆文库/{书名}/`。

产品化模块：

- 长篇拆文库。
- 黄金三章分析。
- 全书结构拆解。
- 角色/设定/剧情聚合。
- 文风提取。
- 对标资料库。

### `story-short-analyze` 短篇拆文

原能力：

- 拆解短篇故事的故事核、结构、情感线、反转、写作手法、共鸣层。
- 字数探针：小于 15000 字按短篇，15000-20000 字灰区，超过 20000 字建议长篇。
- 题材识别：追妻、重生复仇、死人文学、小三、世情、仙侠等。
- Stage 2-6 全量拆解。
- 输出 `拆文报告.md`、`情节节点.md`、`写作手法.md`、`_meta.json`。
- 支持 resume 和覆盖归档。
- 报告 AI 腔自检和结构门控。

产品化模块：

- 短篇拆文库。
- 故事核提取。
- 情绪曲线。
- 爆点和反转分析。
- 写作手法复用。
- 短篇对标引用。

## 9. 扫榜能力

### `story-long-scan` 长篇扫榜

原能力：

- 分析起点、番茄、晋江、七猫等长篇榜单。
- 脚本采集、用户提供、内置知识三种数据来源。
- 支持起点移动端 SSR、番茄、七猫、晋江等榜单目标。
- 数据完整性、字段一致性、简介截断和质量状态检查。
- 输出市场趋势、题材候选、风险阈值、选题决策。
- 区分流量型平台和付费型平台。

产品化模块：

- 市场洞察。
- 长篇榜单采集。
- 趋势识别。
- 选题决策。
- 平台对比。

### `story-short-scan` 短篇扫榜

原能力：

- 分析知乎盐言、番茄短篇、七猫短篇、黑岩、点众等短篇平台。
- 关注情绪类型、触发场景、释放节奏、传播点。
- 强调短篇风口有效期、饱和风险和复扫节点。
- 支持 browser-cdp 采集、用户提供、内置知识。
- 黑岩需要登录态，失败时跳过不阻断全流程。

产品化模块：

- 短篇风口分析。
- 情绪热度排行。
- 短篇题材候选。
- 平台差异分析。
- 复扫提醒。

## 10. 封面能力

### `story-cover`

原能力：

- 根据书名、作者名、目标平台、题材生成网文封面。
- 支持 GPT-Image-2。
- 支持文生图和参考图图生图。
- 默认竖版 1024x1536。
- 根据书名关键词判断题材。
- 平台风格、题材风格、字体风格、作者名装饰、构图变体。
- 输出多版本封面。

产品化模块：

- 封面生成器。
- 题材风格推荐。
- 平台封面模板。
- 参考图生成。
- 多版本封面管理。

## 11. 写作基础设施能力

### `story-setup`

原能力：

- 部署 CLAUDE.md、hooks、rules、agents、settings.local.json、上下文模板。
- 不覆盖用户已有配置，合并而非替换。
- 创建 `.story-deployed` 标记。
- 部署 7 个 Agent：chapter-extractor、character-designer、consistency-checker、narrative-writer、story-architect、story-explorer、story-researcher。
- 部署规则：story-consistency、story-format、story-narrative、story-outline。
- 部署 hooks：session-start、session-end、pre-compact、post-compact、detect-story-gaps、validate-story-commit。

产品化模块：

- 项目模板中心。
- 创作规则中心。
- AI 角色配置。
- 自动检查规则。
- 项目上下文模板。

## 12. 浏览器自动化能力

### `browser-cdp`

原能力：

- 通过 CDP 控制 Chrome。
- 复用登录态。
- 启动 debug Chrome。
- 打开 URL、等待加载、执行 JavaScript、截图、点击、输入、提取 Token。
- 启动前检测状态；如果会杀掉用户 Chrome，必须征得同意。
- 用于需要登录态的平台数据采集。

产品化模块：

- 榜单采集连接器。
- 登录态采集提示。
- 浏览器任务安全确认。
- 授权状态检测。

## 13. Agent 资产迁移

`小说skill/.claude/agents` 中的 7 个 Agent 需要产品化为 AI 角色，而不是继续保留文件依赖。

| Agent | 原职责 | 产品化角色 |
|---|---|---|
| `story-architect` | 题材定位、结构、大纲、反转、钩子 | 故事结构师 |
| `character-designer` | 人物设定、人物关系、动机 | 人物设计师 |
| `narrative-writer` | 正文写作、去 AI 味、表达优化 | 正文写手 |
| `consistency-checker` | 设定、人设、时间线、伏笔一致性 | 一致性审查员 |
| `chapter-extractor` | 长篇逐章摘要和情节点提取 | 章节抽取器 |
| `story-explorer` | 查询角色、伏笔、设定、进度 | 故事资料查询员 |
| `story-researcher` | 外部资料调研、素材搜集 | 资料研究员 |

## 14. Hook 与规则资产迁移

原 Hook：

- `session-start.sh`：会话开始检查。
- `session-end.sh`：会话结束收尾。
- `pre-compact.sh`：压缩前保存上下文。
- `post-compact.sh`：压缩后恢复上下文。
- `detect-story-gaps.sh`：检测故事上下文缺口。
- `validate-story-commit.sh`：提交前校验故事质量。

产品化设计：

- 转为平台内的自动检查和任务提醒。
- 不保留 shell hook 作为运行时依赖。

原规则：

- `story-consistency.md`：一致性规则。
- `story-format.md`：格式规则。
- `story-narrative.md`：叙事规则。
- `story-outline.md`：大纲规则。

产品化设计：

- 转为“规则中心”的可配置质检项。
- 用于写小说、改小说、短剧改编、导出前检查。

## 15. 参考资料资产迁移

`references/` 下的资料不是逐字搬入产品，而是转为提示词模板、检查规则和知识库条目。

主要资料类别：

- 题材与平台：`genre-catalog`、`genre-core-mechanics`、`genre-readers`、`genre-writing-formulas`、`genre-trends`、`publishing-guide`。
- 情绪与剧情：`emotional-methods`、`emotional-arc-design`、`plot-core-methods`、`plot-frameworks`、`plot-emotion-system`、`reversal-toolkit`。
- 钩子与节奏：`hooks-chapter`、`hooks-paragraph`、`hooks-suspense`、`opening-design`、`outline-rhythm`。
- 人物与关系：`character-basics`、`character-design-methods`、`character-relations`、`villain-and-reveal`。
- 文风与表达：`writing-craft`、`style-craft`、`style-combat-face`、`style-genre-modules`、`dialogue-mastery`、`female-audience-writing`。
- 质量与去 AI 味：`quality-checklist`、`quality-rubric`、`anti-ai-writing`、`banned-words`。
- 拆文与导入：`material-decomposition`、`output-templates`、`output-contract`、`pipeline-ops`、`state-tracking`、`length-routing`。
- 扫榜：`scan-output-format`、`reader-profiling`、`topic-decision`、`real-market-data`。
- 封面：`cover-styles`。

## 16. 脚本资产迁移

原脚本：

- `setup-cdp-chrome.js`：CDP Chrome 启动和检测。
- `qidian-rank-scraper.js`：起点榜单采集。
- `fanqie-rank-scraper.js`：番茄榜单采集。
- `jjwxc-rank-scraper.js`：晋江榜单采集。
- `qimao-rank-scraper.js`：七猫榜单采集。
- `ciweimao-rank-scraper.js`：刺猬猫榜单采集。
- `dz-browse-scraper.js`：点众短篇采集。
- `heiyan-booklist-scraper.js`：黑岩书库采集。
- `cdp-utils.js`：CDP 采集辅助。

产品化建议：

- 不直接依赖 `小说skill/` 下脚本路径。
- 后续如果实现采集，应迁移到项目正式 `scripts/` 或后端采集服务。
- 采集任务必须有登录态授权、安全确认、错误降级和数据质量报告。

## 17. DOCX 样例迁移

原文件：

- `剧本 (1).docx`
- `改编-上岸 (1).docx`

产品化用途：

- `剧本 (1).docx`：短剧分场稿初稿样例，用于格式识别、字段补齐、质量检查。
- `改编-上岸 (1).docx`：小说改编方案样例，用于题材迁移、人物映射、情节取舍、短剧化策略验证。

删除 `小说skill/` 前建议：

- 如果仍需要验收样例，应把两个 DOCX 移到正式测试样例目录，例如 `docs/samples/` 或 `src/test/resources/story-samples/`。
- 如果不保留样例文件，至少保留本文档中的验收说明。

## 18. 全量产品路线

### V1：写改编闭环

- 创作中心 AI 意图入口。
- 作品库。
- 长篇写作基础链路。
- 短篇写作基础链路。
- 作品导入与 DOCX 解析。
- 改小说三栏对照。
- 去 AI 味。
- 小说转短剧分场稿。
- 短剧质量检查。
- Markdown/DOCX 导出。

### V1.5：拆文与审查增强

- 长篇拆文库。
- 短篇拆文库。
- 对标资料引用。
- 文风提取。
- 多视角对抗式审查。
- 平台 rubric。
- 统一 Findings Schema。
- 角色/设定/伏笔一致性追踪。

### V2：市场与素材生产

- 长篇扫榜。
- 短篇扫榜。
- 榜单采集。
- 浏览器 CDP 登录态采集。
- 选题决策。
- 封面生成。
- 项目模板中心。
- AI 角色配置中心。
- 规则中心。

### V3：工作室生产系统

- 多项目看板。
- 多人协作审稿。
- 采集任务调度。
- Prompt Registry 后台。
- 平台模板市场。
- 投稿格式包。
- 成本报表。

## 19. 删除目录前检查清单

在手动删除 `小说skill/` 前，确认以下内容已保留在项目文档中：

- 13 个 skill 的能力矩阵已记录。
- 7 个 Agent 的职责已记录。
- Hook 与规则已转化为产品内自动检查设计。
- 扫榜脚本和采集目标已记录。
- 参考资料类别已记录。
- 两个 DOCX 样例的产品用途已记录。
- 产品方案和设计方案已引用本文档。

删除后不应再有文档依赖 `小说skill/` 的运行路径。后续实现应基于正式代码、正式数据库、正式 Prompt Registry 和正式测试样例目录。
