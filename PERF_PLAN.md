# AI-Agent 性能优化修改计划（交付执行版）

> **范围**：全部前后端 ｜ **缓存**：Redis 分布式 ｜ **索引**：改 schema.sql ｜ **RAG 配置**：核实后调整
>
> **执行顺序**：阶段 0 → 8。每个任务标注【文件:行】、【现状】、【目标】、【类型】。类型标记：`并发/异步`、`空间换时间`、`复杂度`、`UX`、`拆分`。
>
> **关键核实结论**（已读源码确认）：
> - 全项目零 `@Cacheable` / `@EnableCaching` / `CacheManager`（`AppConfig.java` 仅有 `@EnableAsync`/`@EnableScheduling`）。
> - `pom.xml:34` 有 `spring-boot-starter-data-redis`，但**缺 `spring-boot-starter-cache`**。
> - `rag.reranker.type: qianfan`（`application.yml:147`）—— 单次批量云端调用，原报告"LLM 串行批次"不适用，跳过。
> - `rag.query.rewrite.variants: 2`（`application.yml:125`）+ HyDE enabled → 每次 RAG 查询 2 次串行 LLM 调用。
> - `schema.sql` 索引全为单列；`TokenUsageMapper.xml` 所有个人用量查询 `WHERE user_id=? AND called_at>=?` 缺复合索引。
> - `OrganizationService.java:572-587` `getUserOrganizationsWithDetail` 循环内 `findByOrgId` = N+1。
> - 前端 `utils.js:4-17` 顶部 import DOMPurify + hljs + 12 语言包 + marked (~148KB)，被仅用 `copyText` 的 `/org`、`/kb` 等页面拖入。

---

## 阶段 0 — UX 即时修复（消解"点击无反馈"投诉）

### 0.1 安装并接入 NProgress 路由进度条 `UX`
- **依赖**：`frontend/package.json` 增加 `"nprogress": "^0.2.10"`，并在 `frontend/src/main.js`（或入口）`import 'nprogress/nprogress.css'`。
- **文件**：`frontend/src/router/index.js`
- **现状**（行 80-101）：仅有 `beforeEach` 做鉴权，无进度指示。
- **目标**：在 `beforeEach` 启动 `NProgress.start()`；新增 `router.afterEach(() => NProgress.done())` 与 `router.onError(() => NProgress.done())`。`beforeEach` 末尾 `NProgress.start(); return true;`。
- **可选**：改用 `vueuse` 的 `useNProgress`，但 nprogress 更轻。

### 0.2 知识库文档列表：加载态而非"暂无文档" `UX`
- **文件**：`frontend/src/views/KnowledgeBaseView.vue`
- **现状**（行 290-300）：`<div v-if="!kb.docs.length" class="empty-state">暂无文档</div>`，加载期间 `docs` 为空 → 用户看到"暂无文档"。
- **目标**：
  - 空态条件改为 `v-if="!kb.docsLoading && !kb.docs.length"`。
  - 新增 `<div v-if="kb.docsLoading" class="doc-list-skeleton">` 显示 3-5 行骨架（复用已有 KB 列表骨架样式，行 42-44）。
  - 卡片点击：在 `@click="kb.selectKb(...)"` 处增加本地 `selectingKbId` ref，点击时置入 `item.id`，卡片右上角显示 spinner，`selectKb` 的 Promise.finally 里清空。

### 0.3 创作页 projects 骨架 `UX`
- **文件**：`frontend/src/views/CreationView.vue`
- **现状**（行 68 `:projects="projects.slice(0, 6)"`，行 548 `projects = ref([])`）：加载期间渲染空。
- **目标**：新增 `projectsLoading = ref(false)`，`loadByMode` 起始置 true、finally 置 false；模板在 `projectsLoading && !projects.length` 时渲染 6 个卡片骨架。

### 0.4 Token 用量页 KPI/趋势骨架 `UX`
- **文件**：`frontend/src/views/TokenUsageView.vue`
- **现状**（行 52 `v-if="!dailySeries.length"` 显示"暂无趋势数据"）。
- **目标**：空态条件加 `&& !loading`；`loading` 期间显示 shimmer KPI 卡片（行 24-45 区域）。

---

## 阶段 1 — 前端代码分割（-148KB 全局首屏）

### 1.1 拆分 `utils.js` → 轻量 utils + 重型 markdown `拆分`
- **文件**：`frontend/src/js/utils.js`（行 4-17 顶部 import 了 DOMPurify + hljs + 12 语言包 + marked，共 ~148KB）
- **目标**：新建 `frontend/src/js/markdown.js`，把以下内容迁入：所有 `highlight.js` import 与 `hljs.registerLanguage`、`marked`/`Renderer` 配置、`formatMarkdown`、`normalizeMarkdownText`、`highlightedCode`。`utils.js` 只保留 `copyText`、`formatFileSize`、`getFileIcon`、`debounce`、`escapeHtml`、`setupCopyCodeHandler`、`downloadCode`、`codeFileExtension`，并删除重型 import。
- **更新调用方**（grep 确认）：
  - `frontend/src/components/MessageBubble.vue:134` → 从 `markdown.js` 导入 `formatMarkdown`
  - `frontend/src/views/ShareView.vue:35` → 同上
  - `frontend/src/stores/sessions.js:12` → 同上
  - `frontend/src/stores/sessionUtils.js:1` → 同上
  - 仅用轻量工具的文件保持从 `utils.js` 导入：`MainLayout.vue:120`、`OrgView.vue:463`、`KnowledgeBaseView.vue:492`、`ChatView.vue:67`、`Sidebar.vue:279`。
- **验收**：`npm run build` 后 `dist/assets` 不再有被 `/org`、`/kb`、`/profile/usage`、`/chat` 引入的 148KB markdown chunk。

### 1.2 创作页模式组件异步化 `拆分`
- **文件**：`frontend/src/views/CreationView.vue`（行 538-540 同步 import 三个模式专属组件）
- **目标**：`RewriteCompareView`、`ScriptWorkbenchView`、`ScriptExportView` 改用 `defineAsyncComponent(() => import(...))`；其余 5 个保持同步。
- **注意**：异步组件在 `v-else-if` 分支首次渲染时才加载，需保证切换 mode 时有过渡/loading（可配合 0.3）。

### 1.3 chart.js 动态导入 `拆分`
- **文件**：`frontend/src/views/TokenUsageView.vue`（行 101-115 顶层 import + `Chart.register`）
- **目标**：删除顶层 import；在 `renderChart()` 内 `const Chart = (await import('chart.js')).default;` 并在函数内 register（用模块级 `let chartRegistered=false` 避免重复 register）。`renderChart` 改为 `async`。

### 1.4 Vite manualChunks + 压缩 `拆分`
- **文件**：`frontend/vite.config.js`（行 29-36 `build.rollupOptions` 无 output 配置）
- **目标**：
  ```js
  build: {
    rollupOptions: {
      input: { index: ..., login: ... },
      output: {
        manualChunks: {
          vendor: ['vue', 'vue-router', 'pinia'],
          markdown: ['marked', 'highlight.js', 'dompurify'],
          chart: ['chart.js'],
          axios: ['axios'],
        },
      },
    },
  }
  ```
- **依赖**：`frontend/package.json` 增加 `vite-plugin-compression`，`vite.config.js` plugins 加入 `compression({ algorithm: 'brotliCompress' })` 与 gzip 实例。

---

## 阶段 2 — 前端数据请求并行化 + 去重 + 缓存

### 2.1 CreationView.loadByMode 并行化 `并发`
- **文件**：`frontend/src/views/CreationView.vue`（行 928-946，4 个串行 await）
- **现状**：`home` 模式串行执行 `listProjects` → `loadActiveTask` → `loadActiveRewriteTask`。
- **目标**：`home`/`projects` 分支改为
  ```js
  const [proj, , ] = await Promise.all([
    storyApi.listProjects(),
    (mode.value==='projects') ? loadTaskHistory() : Promise.resolve(),
    (['home','projects','editor'].includes(mode.value)) ? loadActiveTask() : Promise.resolve(),
    (['home','projects','editor'].includes(mode.value)) ? loadActiveRewriteTask() : Promise.resolve(),
  ]);
  if (mode.value==='home'||mode.value==='projects') projects.value = proj;
  if (mode.value==='editor') await loadProject();
  ```
- **注意**：`loadTaskHistory`/`loadActiveTask`/`loadActiveRewriteTask` 内部对各自 ref 赋值，无返回值依赖，可安全并行。需核对它们内部是否依赖 `projects.value`——若不依赖即可。

### 2.2 MainLayout.onMounted 并行化 `并发`
- **文件**：`frontend/src/components/layout/MainLayout.vue`（行 142-149，4 串行 await）
- **目标**：
  ```js
  setupResponsiveSidebar();
  await Promise.all([ auth.refreshProfile(), org.loadOrgs(), sess.init() ]);
  await syncKnowledgeBasesForOrg(org.currentOrgId);
  ```
- **依据**：前三个互相独立；仅 `syncKnowledgeBasesForOrg` 依赖 `loadOrgs` 产出的 `currentOrgId`。

### 2.3 TokenUsageView.loadAll 并行化 `并发`
- **文件**：`frontend/src/views/TokenUsageView.vue`（行 148-165）
- **目标**：`getMyUsageDetails` 并入首个 `Promise.all`，去掉串行 `await loadDetails()`；`loadDetails` 内部赋值 `detailRows` 后仍需 `nextTick` + `renderChart`。

### 2.4 OrgView 懒加载 tab 数据 + 去重 `并发`
- **文件**：`frontend/src/views/OrgView.vue`（行 572-580 immediate watcher 触发 5 个请求）
- **目标**：
  - immediate watcher 只调 `loadMembers()`；`loadInvitations`/`loadJoinRequests` 改为对应 tab 首次激活时调用（监听 `activeSection` 变化，加 `loadedSections` Set 去重）。
  - `loadMyInvitations`/`loadMyJoinRequests` 去重：store 已有 `pendingNoticeCount` 数据源；让 `org.js` 的 `refreshNoticeCount` 把 `invites`/`requests` 列表也存入 store state，OrgView 直接读 store 而不再重复请求（`org.js:86-98`）。

### 2.5 请求级 TTL 缓存 `空间换时间`
- **文件**：各 store（`org.js`、`kb.js`、`CreationView` 用到的 story store、`tokenUsageApi` 调用处）
- **目标**：为 `listOrganizations`、`listKnowledgeBases`、`listProjects`、`getMyUsageSummary(days)`/`getMyDailyUsage(days)` 加内存缓存（key 含参数，TTL 60s；usage 按 `days` 维度缓存，切回已查过的范围命中）。写操作（create/update/delete）后 `invalidate`。可写一个轻量 `createTtlCache(ttlMs)` 工具。

### 2.6 CreationView 请求中止 `并发`
- **文件**：`frontend/src/views/CreationView.vue`（行 922-925 `onUnmounted` 只停定时器）
- **目标**：维护 `AbortController`，`loadByMode` 起始 `abort` 旧 controller 并新建；`onUnmounted` 调 `abort`。各 `storyApi` 调用传 `signal`（需 storyApi 支持，否则至少在卸载时停止后续 setState）。

---

## 阶段 3 — 前端渲染与资源

### 3.1 会话消息 markdown 异步水合 `并发`
- **文件**：`frontend/src/stores/sessions.js`（行 236-252 `lazyLoadSessionMessages` 内 `msgs.map(... formatMarkdown ...)` 同步跑 N 次）
- **目标**：map 时 `html: ''`（或 `escapeHtml(content)`），先渲染纯文本；随后用 `requestIdleCallback`（降级 `setTimeout`）分批对每条调 `formatMarkdown` 写回 `html`。保证长会话切换不卡主线程。

### 3.2 ReAct 流式期间避免每帧 formatMarkdown `并发`
- **文件**：`frontend/src/stores/sessionUtils.js`（行 60-62 `renderReactBubble` 调 `formatMarkdown(answerText)`）+ `sessions.js:982-989`
- **目标**：流式 answer 累积期用 `renderStreamingText`（轻量 escapeHtml+`<br>`，`sessionUtils.js:18-20`）；仅在 `done`/完成时调用一次 `formatMarkdown`。参考 `doStreamChat` 已有做法（`sessions.js:717`）。

### 3.3 取消 deep watch `复杂度`
- **文件**：`frontend/src/stores/sessions.js`（行 269 `watch(messages, scheduleSave, { deep: true })`）
- **目标**：流式每帧深遍历最多 100 条消息。改为在显式变更点（`scheduleSave` 调用处）维护 `messagesVersion` 自增 ref，`watch(() => messagesVersion.value, scheduleSave)`。同样处理 `sessions` deep watch（行 270）。

### 3.4 getFileIcon memoize `复杂度`
- **文件**：`frontend/src/views/KnowledgeBaseView.vue`（行 308 每渲染每 doc 调两次 `getFileIcon`）+ `frontend/src/js/utils.js:355-365`
- **目标**：`utils.js` 内加 `const iconCache = new Map()`，`getFileIcon` 按 ext 缓存返回对象；或在 `kb.js` mapDoc 时预计算 `iconCls`/`iconHtml` 挂到 doc 对象，模板直接读。

### 3.5 虚拟滚动 `复杂度`
- **文件**：`KnowledgeBaseView.vue:302`（doc 列表）、`OrgView.vue:234`（members）
- **目标**：列表 >50 项时启用虚拟滚动。依赖 `frontend/package.json` 加 `@vueuse/core` 的 `useVirtualList`（若已装 vueuse）或轻量虚拟列表组件。

### 3.6 轮询与缓存清理 `UX/资源`
- **文件**：`frontend/src/views/KnowledgeBaseView.vue`（无 unmount hook）+ `frontend/src/stores/kb.js:71-77,180-199`
- **目标**：KnowledgeBaseView 加 `onBeforeUnmount(() => kb.stopAllDocPolling())`；切换 KB 时清空 `docChunksCache`（`KnowledgeBaseView.vue:505`）。
- **文件**：`frontend/src/stores/sessionUtils.js:27-31` `stripHtml` 每次 `document.createElement` → 模块级复用一个 detached div。

---

## 阶段 4 — 后端数据库索引（改 schema.sql）`空间换时间`

### 4.1 新增复合索引
- **文件**：`src/main/resources/schema.sql`
- **现状**（行 116-118 单列索引；行 24 `idx_kb_tenant`；行 48 `idx_doc_kb_id`；行 92-93 `idx_log_*`）
- **目标**：在对应表索引段追加（均 `IF NOT EXISTS`）：
  ```sql
  -- llm_token_usage（紧跟行 118）
  CREATE INDEX IF NOT EXISTS idx_usage_user_time  ON llm_token_usage(user_id, called_at DESC);
  CREATE INDEX IF NOT EXISTS idx_usage_time_model ON llm_token_usage(called_at DESC, model_name);

  -- kb_knowledge_base（紧跟行 24）
  CREATE INDEX IF NOT EXISTS idx_kb_tenant_created ON kb_knowledge_base(tenant_id, created_at DESC);

  -- kb_document（紧跟行 48）
  CREATE INDEX IF NOT EXISTS idx_doc_kb_created ON kb_document(kb_id, created_at DESC);

  -- kb_retrieval_log（紧跟行 93）
  CREATE INDEX IF NOT EXISTS idx_log_tenant_kb_time ON kb_retrieval_log(tenant_id, kb_id, created_at DESC);
  ```
- **重要**：`schema.sql` 仅对全新部署生效。**已有库需手动执行上述 5 条 `CREATE INDEX IF NOT EXISTS`**（Flyway 已执行过的库不会重跑 schema.sql）。建议同时新增 Flyway 迁移文件 `src/main/resources/db/migration/V{next}__perf_indexes.sql` 包含同样 5 条，以便存量库自动升级——**请执行者确认项目是否使用 Flyway 历史迁移目录，若用则双写**。

---

## 阶段 5 — 后端 Redis 分布式缓存 `空间换时间`

### 5.1 引入 Spring Cache + Redis CacheManager
- **文件**：`pom.xml`（行 34 已有 `spring-boot-starter-data-redis`，但缺 cache starter）
- **目标**：新增依赖
  ```xml
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
  </dependency>
  ```
- **文件**：`src/main/java/com/example/aiagent/config/AppConfig.java`（行 20-24）
- **目标**：类注解加 `@EnableCaching`（与已有 `@EnableAsync`/`@EnableScheduling` 并列）。新增 `CacheManager` Bean：
  ```java
  @Bean
  public CacheManager cacheManager(RedisConnectionFactory cf) {
    RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
        .serializeValuesWith(RedisSerializationContext.SerializationPair
            .fromSerializer(new GenericJackson2JsonRedisSerializer()));
    Map<String, RedisCacheConfiguration> ttl = new HashMap<>();
    ttl.put("org",        base.entryTtl(Duration.ofSeconds(60)));
    ttl.put("orgMember",  base.entryTtl(Duration.ofSeconds(60)));
    ttl.put("kb",         base.entryTtl(Duration.ofSeconds(60)));
    ttl.put("kbAccess",   base.entryTtl(Duration.ofSeconds(60)));
    ttl.put("profile",    base.entryTtl(Duration.ofSeconds(60)));
    ttl.put("userMemory", base.entryTtl(Duration.ofSeconds(30)));
    return RedisCacheManager.builder(cf).cacheDefaults(base).withInitialCacheConfigurations(ttl).build();
  }
  ```
- **注意**：缓存对象需可序列化（实体需 implements Serializable 或用 JSON 序列化器——上面已配 JSON 序列化器，实体无需改）。`Optional` 返回值需用 `@Cacheable` 的 `unless="#result == null"` 处理或改返回非 Optional。

### 5.2 在热点方法加 @Cacheable
- **OrganizationService.resolveOrgId**（`src/main/java/com/example/aiagent/security/service/OrganizationService.java:543-560`）：`@Cacheable(cacheNames="org", key="#userId + ':' + #requestedOrgId", unless="#result == null")`。在 `createOrganization`/`updateOrganization`/`deleteOrganization`/成员变更处 `@CacheEvict(cacheNames="org", allEntries=true)`。
- **OrganizationService.getOrganizationById**（对应 `findByOrgId`）：`@Cacheable(cacheNames="org", key="'org:' + #orgId", unless="#result == null")`，写操作 evict。
- **KbMemberService.checkAccess**（`src/main/java/com/example/aiagent/kb/service/KbMemberService.java:56-78`）：`@Cacheable(cacheNames="kbAccess", key="#kbId + ':' + #userId", unless="#result == null")`；KB 成员增删、KB 删除时 evict `kbAccess`+`kb`。
- **AuthService.getProfile**（`src/main/java/com/example/aiagent/security/service/AuthService.java:248-259`）：`@Cacheable(cacheNames="profile", key="#userId")`；用户资料更新处 evict。
- **UserMemoryService.getMemoryText**（`src/main/java/com/example/aiagent/memory/UserMemoryService.java:50-62`）：`@Cacheable(cacheNames="userMemory", key="#userId")`；`upsert`（`UserMemoryService.java:72-95` 区间的写方法）处 `@CacheEvict(cacheNames="userMemory", key="#userId")`。
- **依据**：全项目零 `@Cacheable`（已确认）；这些数据读多写少，是 KB/聊天/org/profile 每次请求的重复开销。

---

## 阶段 6 — 后端 N+1 与查询复杂度 `复杂度`

### 6.1 getUserOrganizationsWithDetail N+1 → JOIN
- **文件**：`src/main/java/com/example/aiagent/security/service/OrganizationService.java:572-587`（循环内 `organizationMapper.findByOrgId`，N+1）+ `src/main/resources/mapper/OrganizationMapper.xml`
- **目标**：
  - `OrganizationMapper`（接口 + XML）新增：
    ```sql
    <select id="findOrgsWithDetailByUserId" resultType="java.util.Map">
      SELECT m.org_id AS "orgId", m.role AS "role", o.name AS "name", o.org_type AS "orgType"
      FROM sys_org_member m
      JOIN sys_organization o ON o.org_id = m.org_id
      WHERE m.user_id = #{userId}
    </select>
    ```
  - Service 改为单次调用该 mapper，去掉循环内 `findByOrgId`。
- **影响端点**：`GET /api/v1/org`（`OrganizationController.java:86`）—— 直接加速 /org 列表。

### 6.2 getMyPendingInvitations / getMyJoinRequests N+1 `复杂度`
- **文件**：`OrganizationService.java:319-333`（行 325 循环 `findByOrgId`）与 `:457-474`
- **目标**：改为批量查 org：收集所有 `orgId` 后用 `organizationMapper.findByOrgIds(List)`（新增 batch mapper，`WHERE org_id IN (...)`），在内存中组装 orgName。或直接 JOIN。

### 6.3 deleteOrganization 循环 DELETE → 批量 `复杂度`
- **文件**：`OrganizationService.java:730-733`（循环 `deleteByOrgIdAndUserId`）
- **目标**：`OrgMemberMapper` 新增 `deleteByOrgId(String orgId)`（`DELETE FROM sys_org_member WHERE org_id = #{orgId}`），单条执行。配合 `@CacheEvict`。

### 6.4 ChatHistoryService 批量插入 `复杂度`
- **文件**：`src/main/java/com/example/aiagent/chat/service/ChatHistoryService.java:236-269`（`syncFromClient` 嵌套循环逐条 insert）与 `:160-167`（`rewriteMessages` 逐条 insert）
- **目标**：`ChatMessageMapper` 新增 `batchInsert(List<ChatMessage>`（多行 VALUES，`useGeneratedKeys` 不需要）。Service 用 `SqlSessionFactory.openSession(ExecutorType.BATCH)` 或多行 INSERT。迁移大量 localStorage 消息时显著减少往返。

### 6.5 OrganizationController.getOrganization 去冗余 `复杂度`
- **文件**：`src/main/java/com/example/aiagent/security/controller/OrganizationController.java:95-122`
- **现状**：`isMemberOf`（SQL#1）+ `getOrganizationById`（SQL#2）+ `getOrgMembersWithUsername`（SQL#3,4）= 4 SQL。
- **目标**：去掉 `isMemberOf` 单独查询；用 `getOrgMembersWithUsername` 返回的成员列表判断 `userId` 是否在内。降到 2-3 SQL。叠加 5.2 缓存后多数命中 0 SQL。

### 6.6 TokenUsageService 合并冗余查询 `复杂度`
- **文件**：`src/main/java/com/example/aiagent/observability/service/TokenUsageService.java:74-80`（`getTodayTotalCost` 调 `aggregateByModelSince` 后 Java 端求和）与 `:214-219`（`getRecentErrorRate` 两次 COUNT）
- **目标**：
  - `TokenUsageMapper` 新增 `sumCostSince(Instant)` → `SELECT COALESCE(SUM(cost_usd),0) FROM llm_token_usage WHERE called_at >= #{since}`，`getTodayTotalCost` 直接用。
  - 新增 `countTotalAndErrorsSince(Instant)` → `SELECT COUNT(*) AS "total", COUNT(*) FILTER (WHERE success=false) AS "errors" FROM llm_token_usage WHERE called_at >= #{since}`，`getRecentErrorRate` 用一次查询得两个数。
  - XML 在 `TokenUsageMapper.xml` 追加这两个 `<select>`。

### 6.7 列表端点轻量投影 `复杂度`
- **文件**：`src/main/resources/mapper/KnowledgeBaseMapper.xml:22-28`（`findByTenantId` SELECT 含 `chunk_config`、`embed_model`）+ `src/main/resources/mapper/DocumentMapper.xml:28-35`（`findByKbId` SELECT 含 `parse_error`、`file_path`、`allowed_roles`、`file_hash`）
- **目标**：新增 `findListByTenantId`（轻列：id, kb_id, name, description, doc_count/动态, status, created_at）与 `findListByKbId`（轻列：id, kb_id, filename, status, chunk_count, size, created_at），列表端点改用轻投影；详情端点保留原全列查询。
- **注意**：若前端列表展示了某些字段，需对照前端确认保留哪些列，避免漏字段。

### 6.8 会话搜索 ILIKE 优化（可选，较大）`复杂度`
- **文件**：`src/main/resources/mapper/ChatSessionMapper.xml:65-74`（`m.content ILIKE '%kw%'` 前缀通配全扫）
- **目标**：为 `chat_message.content` 加 PostgreSQL `tsvector` 生成列 + GIN 索引，搜索改 `to_tsvector('simple', content) @@ plainto_tsquery('simple', #{keyword})`。中文需 `simple` 或 zhparser 扩展。**此项较重，可列为可选/后续**。

---

## 阶段 7 — 后端多线程/异步并行 `并发/异步`

### 7.1 KnowledgeBaseService.getStats 并行化
- **文件**：`src/main/java/com/example/aiagent/kb/service/KnowledgeBaseService.java:399-444`（5 个串行 mapper 调用：行 405 docCount、406 chunkCount、407-408 recentQueries、411-412 answerTypeRows、422-423 recentLogs）
- **目标**：用 `CompletableFuture.supplyAsync(..., ragRetrievalExecutor)` 并行提交这 5 个只读查询，`CompletableFuture.allOf(...).join()` 后组装。`ragRetrievalExecutor` 已存在（`AppConfig.java:185-200`）。
- **可选合并**：`countByTenantIdAndKbIdAndCreatedAtAfter` 与 `countGroupByAnswerType` 合并为一条返回 total + 分组的 SQL，进一步减往返。
- **事务**：`@Transactional(readOnly=true)` 在并行子线程中不传播——需把查询放到无事务的 mapper 调用，或拆出 `@Transactional(readOnly=true)` 的子方法由主线程外的 executor 调用（Spring 事务是线程绑定的）。**执行者需验证事务边界**：可把 5 个 count 查询改为不走事务（只读 mapper 自身安全），`getKnowledgeBase` 归属校验保留主线程事务。

### 7.2 ChatController.chat 预 LLM 阶段并行 `并发`
- **文件**：`src/main/java/com/example/aiagent/controller/ChatController.java:139-155`
- **现状**：串行 `chatRagContextService.resolve` → `warmup` → `getSummaryForPrompt` → `getMemoryText`。
- **目标**：`resolve` 与 `warmup`/`getSummaryForPrompt`/`getMemoryText` 三者并行（`getMemoryText` 已加缓存 5.2 后多数 0 SQL）。用 `CompletableFuture`，注意 `resolve` 可能产出 RAG context 供后续 LLM 用——并行仅限这些预取，LLM 调用仍需等全部完成。
- **注意**：SecurityContext 已用 `INHERITABLETHREADLOCAL`（`AppConfig.java:29`），但 `CompletableFuture.supplyAsync` 默认 ForkJoinPool 不一定继承；建议显式传 `ragRetrievalExecutor` 并确认 SecurityContext 可用，或把需要的 userId/orgId 作为参数显式传入。

### 7.3 QueryRewriter HyDE + 多视角并行 + 缓存 `并发/空间换时间`
- **文件**：`src/main/java/com/example/aiagent/rag/query/QueryRewriter.java`（`generateHypotheticalDocument`:34-43，`rewriteMultiPerspective`:49-69）+ `src/main/java/com/example/aiagent/rag/pipeline/HybridRagPipeline.java:139-141`（串行调用）
- **目标**：
  - 在 `HybridRagPipeline.retrieveOnly` 用 `CompletableFuture` 并行跑 HyDE 与多视角重写（两者独立）。
  - 加 Redis 缓存：key = `rag:hyde:{md5(query)}` / `rag:rewrite:{md5(query)+variants}`，TTL 复用 `rag.cache.ttl-seconds`(300)。命中跳过 LLM 调用。
  - BM25 检索（`retrieveBm25Candidates`，行 145）与向量检索独立 → 也并行。
- **依据**：每次 RAG 查询当前 2 次串行 LLM（1-5s）发生在检索前，是首字延迟主因。

### 7.4 同步邮件移出事务 `异步`
- **文件**：`src/main/java/com/example/aiagent/security/service/OrganizationService.java:190`（`sendInvitationEmail` 在 `@Transactional` 内同步）与 `:374`（`sendJoinRequestNotification`）
- **现状**：SMTP/HTTP 邮件 I/O 阻塞 DB 事务，占用连接池。
- **目标**：复用已有 `mailTaskExecutor`（`AppConfig.java:165`）改为 `@Async("mailTaskExecutor")`，或参考已有 `MailEventListener`（`src/main/java/com/example/aiagent/security/mail/MailEventListener.java:34`）发布事务后事件 `TransactionalEventListener(phase=AFTER_COMMIT)`。**关键**：邮件内容（org 名、token）须在事务内捕获为 final 变量再传给异步任务，避免懒加载失效。

### 7.5 Reranker（当前 qianfan，无需改动）
- 已核实 `application.yml:147` `type: qianfan`（单次批量云端调用），原报告 D5「LLM 串行批次」**不适用**，跳过。

---

## 阶段 8 — RAG 配置调整（已核实）`空间换时间`

### 8.1 降低查询改写 LLM 调用
- **文件**：`src/main/resources/application.yml:125`
- **现状**：`rag.query.rewrite.variants: 2`（每次 RAG 多 1 次 LLM 多视角重写）+ HyDE enabled。
- **核实结论**：CLAUDE.md 注明"HyDE 单独通常足够"。
- **目标（待最终拍板，默认建议）**：改为 `variants: 0`，仅保留 HyDE，省掉每次 RAG 的 1 次 LLM 调用。若更看重召回充分性，保持 2 但依赖 7.3 的并行+缓存抵消延迟。
- **reranker** `qianfan` 保持不变。

---

## 验收清单

**后端**：
- `mvn clean compile` 通过
- `mvn test`（重点 `HybridRagPipelineTest`、`KnowledgeBaseServiceIntegrationTest`、`DocumentIngestAndRetrievalIntegrationTest`；集成测试需 Docker Testcontainers + Redis）
- 手动对已有库执行 5 条 `CREATE INDEX` 后，验证 `/profile/usage`、`/org`、`/kb/{id}/stats` 响应时间下降
- 启动后 `/actuator/health` 正常，Redis 中能看到 `org::`、`kbAccess::` 等 key

**前端**：
- `cd frontend && npm install`（新增 nprogress、vite-plugin-compression、可能的 vueuse）
- `npm run build`，检查 `dist/assets`：markdown chunk 不再被 /org、/kb、/profile/usage、/chat 引入；`manualChunks` 生效
- 手动验证 4 个慢页面：进度条出现、骨架/loading 显示、切换更快
- DevTools Network：/org 进入不再重复请求 `listMyInvitations`/`listMyJoinRequests`；CreationView 切换为并行请求

**回归点**：
- 缓存 evict：org 增删改、KB 成员变更、用户资料更新后，旧数据不残留
- 异步邮件：邀请/申请加入后邮件仍能送达（事务提交后才发）
- ReAct/流式：markdown 水合异步化后，历史会话切换显示正常
- 索引：`EXPLAIN ANALYZE` 确认 `aggregateDailyByUserSince` 走 `idx_usage_user_time`

---

## 优化类型汇总

| 类型 | 涉及任务 |
|---|---|
| `并发/异步` | 2.1, 2.2, 2.3, 2.6, 3.1, 3.2, 7.1, 7.2, 7.3, 7.4 |
| `空间换时间` | 2.5, 4.1, 5.1, 5.2, 7.3, 8.1 |
| `复杂度` | 3.3, 3.4, 3.5, 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8 |
| `UX` | 0.1, 0.2, 0.3, 0.4, 3.6 |
| `拆分` | 1.1, 1.2, 1.3, 1.4 |

## 四个慢页面 → 根因 → 对应阶段映射

| 慢页面 | 前端根因 | 后端根因 | 解决阶段 |
|---|---|---|---|
| `/profile/usage` | chart.js 168KB 同步加载；`loadDetails` 串行；加载显示"暂无数据" | 缺 `(user_id, called_at DESC)` 复合索引；`getTodayTotalCost`/`getRecentErrorRate` 冗余查询 | 0.4, 1.3, 2.3, 4.1, 6.6 |
| 切换到"创作"页 | `loadByMode` 4 串行 await；3 模式组件 40KB 同步加载；无 loading | `/api/v1/org` N+1、`/api/v1/auth/profile` 无缓存 | 0.3, 1.2, 2.1, 5.2, 6.1 |
| `/org` 组织设置 | 5 请求全量触发；`listMyInvitations`/`listMyJoinRequests` 重复请求 | `getUserOrganizationsWithDetail` N+1；`getOrganization` 4 串行 SQL；`resolveOrgId` 无缓存 | 2.4, 5.2, 6.1, 6.5 |
| `/kb` 知识库 | 点击卡片显示"暂无文档"而非 loading；`getFileIcon` 重复创建对象；轮询泄漏 | `getStats` 8-11 串行 SQL；`checkAccess` 无缓存；列表缺排序索引且 SELECT 重列 | 0.1, 0.2, 3.4, 3.6, 4.1, 5.2, 6.7, 7.1 |
