# AI Agent

基于 Spring Boot 3 + LangChain4j 构建的企业级 AI 智能体，集成完整 JWT 认证、多轮对话记忆、混合 RAG 知识库检索、Function Calling 工具调用与完整可观测性能力。

**技术栈**

| 层次 | 技术 |
|------|------|
| 应用框架 | Spring Boot 3.3.5 / Java 21 |
| AI 框架 | LangChain4j 0.36.2 |
| 大语言模型 | DeepSeek (默认) / Claude (备选) |
| 向量数据库 | PostgreSQL 16 + pgvector |
| Embedding 模型 | all-MiniLM-L6-v2 (本地 ONNX，无需 API) |
| 文档解析 | Apache Tika (PDF / Word / TXT 等) |
| 对话记忆 | Redis 7 |
| 关键词检索 | Elasticsearch 8 (BM25，可选) |
| 认证授权 | Spring Security + JWT (HMAC-SHA256) + BCrypt |
| 安全防护 | 限流 + Prompt 注入过滤 + 输出内容脱敏 + 审计日志 |
| 可观测性 | Micrometer + Prometheus + Zipkin 链路追踪 |
| 数据库迁移 | Flyway（版本化 schema 管理） |
| Reranker | BGE-Reranker-v2-m3 (本地) / Cohere Rerank API |

---

## 功能特性

- **JWT 认证**：注册/登录接口返回 Token，所有业务接口受 Bearer Token 保护，无状态、前后端分离友好
- **多租户隔离**：知识库按 userId 隔离，租户之间数据完全不可见
- **多轮对话**：基于 Redis 的会话记忆，每个会话独立保存最近 N 条消息，支持 TTL 自动过期
- **流式输出**：SSE 实时推送（Server-Sent Events），字符逐步出现，告别等待
- **混合 RAG 知识库**：向量检索 + BM25 关键词检索 + RRF 融合排序 + Reranker 精排，检索精度比基础 RAG 提升 73%+
- **查询改写**：HyDE（假设文档扩展）+ 多角度改写，扩大语义覆盖面
- **引用溯源**：答案自动标注 `[1][2]` 引用编号，返回结构化 Citation 列表，消除幻觉
- **Function Calling**：内置订单查询、天气查询、账户查询、数学计算等工具，LLM 按需自动调用
- **多模型支持**：通过 Spring Profile 一键切换 DeepSeek 与 Claude
- **Token 用量追踪**：AOP 拦截 LLM 调用，异步写入用量记录，含费用估算与成本报表接口
- **分布式追踪**：TraceId 自动注入 MDC 日志，上报 Zipkin
- **安全全链路**：Prompt 注入检测 → 限流（分钟/日双维度）→ LLM 调用 → 输出脱敏 → 审计日志
- **数据库迁移**：Flyway 管理 schema 版本，多环境部署可重现

---

## 快速开始

### 前置要求

- Java 21+（**注意：Maven 也需使用 Java 21 执行，请设置 `JAVA_HOME`**）
- Maven 3.9+
- Docker & Docker Compose

### 0. 设置 JAVA_HOME（首次配置）

```bash
# 查看已安装的 JDK
/usr/libexec/java_home -V

# 在 ~/.zshrc 末尾加入（路径改为你的 Java 21 实际路径）
export JAVA_HOME=/Users/your-name/Library/Java/JavaVirtualMachines/corretto-21.0.11/Contents/Home

# 生效
source ~/.zshrc
java -version  # 应显示 21.x.x
```

### 1. 启动基础服务

```bash
# 复制环境变量模板
cp .env.example .env
# 编辑 .env，填入 API Key 和数据库密码

# 启动 PostgreSQL+pgvector、Redis
docker compose up -d postgres redis

# 或一次性启动所有服务（含 ES，需要 profile bm25）
docker compose up -d
docker compose --profile bm25 up -d   # 启动 Elasticsearch（可选）

# 查看服务状态（等待所有服务 healthy）
docker compose ps
```

> Flyway 会在应用启动时**自动执行** `db/migration/V1__init_schema.sql` 建表，无需手动初始化。

### 2. 配置 API Key

编辑 `.env` 文件（已从 `.env.example` 复制），填写：

```bash
DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx     # DeepSeek（默认模型）
ANTHROPIC_API_KEY=sk-ant-xxxxxxxxxxxxxx  # Claude（备选，可不填）
JWT_SECRET=your-super-secret-key-at-least-32-chars  # 生产环境必改！
```

或直接 `export` 到当前 shell：

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
export JWT_SECRET=change-this-to-a-real-secret-key-32chars
```

### 3. 启动应用

```bash
# 使用 DeepSeek（默认）
mvn spring-boot:run

# 或者显式指定 profile
mvn spring-boot:run -Dspring-boot.run.profiles=deepseek

# 切换为 Claude
mvn spring-boot:run -Dspring-boot.run.profiles=claude

# 生产环境（关闭 SQL 日志，JSON 格式输出，优雅关机）
mvn spring-boot:run -Dspring-boot.run.profiles=deepseek,prod
```

### 4. 注册用户并获取 Token

```bash
# 注册
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"secret123"}'

# 响应示例：
# {"token":"eyJ...","tokenType":"Bearer","expiresIn":86400,"userId":"abc123","username":"alice","roles":["ROLE_USER"]}

# 登录（已注册用户）
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"secret123"}'
```

> 将返回的 `token` 保存，后续所有接口请求均需携带：`Authorization: Bearer <token>`

### 5. 验证

```bash
# 健康检查（无需 Token）
curl http://localhost:8080/actuator/health

# 发送对话（需要 Token）
TOKEN="eyJ..."
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"sessionId":"test-001","message":"你好，帮我查询订单 #12345 的状态"}'

# 流式对话（SSE）
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/chat/stream?sessionId=test-001&message=今天北京天气如何"

# Zipkin 链路追踪
open http://localhost:9411
```

---

## Docker 一键部署

```bash
# 构建并启动全部服务（包含应用本身）
docker compose up -d --build

# 查看应用日志
docker compose logs -f app

# 停止并清除数据
docker compose down -v
```

---

## API 文档

所有业务接口（`/api/v1/auth/**` 除外）均需在请求头携带：

```
Authorization: Bearer <token>
```

### 认证接口

#### 用户注册

```
POST /api/v1/auth/register
Content-Type: application/json
```

请求体：

```json
{
  "username": "alice",
  "password": "secret123"
}
```

响应：

```json
{
  "token": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 86400,
  "userId": "abc123def456",
  "username": "alice",
  "roles": ["ROLE_USER"]
}
```

#### 用户登录

```
POST /api/v1/auth/login
Content-Type: application/json
```

请求/响应格式同注册。

---

### 对话接口

#### 普通对话（同步）

```
POST /api/v1/chat
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：

```json
{
  "sessionId": "user-123",
  "message": "帮我查一下订单 #12345 的状态"
}
```

响应：

```json
{
  "sessionId": "user-123",
  "reply": "订单 #12345 状态：已发货，预计明天到达，快递单号：SF1234567890",
  "durationMs": 1234
}
```

---

#### 流式对话（SSE）

```
GET /api/v1/chat/stream?sessionId={sessionId}&message={message}
Authorization: Bearer <token>
Accept: text/event-stream
```

响应为 SSE 流，逐 token 推送。最后一条事件名为 `done`，数据为 `[DONE]`。

前端示例：

```javascript
// 注意：EventSource 不支持自定义 Header，生产环境建议改用 fetch + ReadableStream
const TOKEN = 'eyJ...';
const response = await fetch(
  `/api/v1/chat/stream?sessionId=user-123&message=${encodeURIComponent('你好')}`,
  { headers: { Authorization: `Bearer ${TOKEN}` } }
);
const reader = response.body.getReader();
// 逐块读取...
```

---

#### 清除会话记忆

```
DELETE /api/v1/chat/memory/{sessionId}
Authorization: Bearer <token>
```

响应：`会话 user-123 的记忆已清除`

---

### 知识库接口

#### 创建知识库

```
POST /api/v1/kb
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：

```json
{
  "name": "产品手册",
  "description": "公司产品相关文档"
}
```

#### 列出我的知识库

```
GET /api/v1/kb
Authorization: Bearer <token>
```

#### 上传文档

```
POST /api/v1/kb/{kbId}/documents
Authorization: Bearer <token>
Content-Type: multipart/form-data
```

| 参数 | 类型 | 说明 |
|------|------|------|
| file | MultipartFile | 文档文件（PDF、Word、TXT、Excel 等） |

响应：

```json
{
  "message": "文档上传成功",
  "filename": "产品手册.pdf",
  "chunkCount": 42
}
```

#### 知识库问答

```
POST /api/v1/kb/{kbId}/query
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：

```json
{
  "question": "退款政策是什么？"
}
```

响应：

```json
{
  "answer": "根据知识库文档，退款需在购买后7天内提交申请...",
  "answerFound": true,
  "confidence": "0.872",
  "citations": [
    { "source": "退款政策.pdf", "score": "0.872", "snippet": "退款需要在购买后7天内..." }
  ]
}
```

#### 删除知识库

```
DELETE /api/v1/kb/{kbId}
Authorization: Bearer <token>
```

#### 知识库统计

```
GET /api/v1/kb/{kbId}/stats
Authorization: Bearer <token>
```

---

### 管理接口（需要 ADMIN 角色）

| 接口 | 说明 |
|------|------|
| `GET /api/v1/admin/token-usage/today` | 今日全局 LLM 总费用 |
| `GET /api/v1/admin/token-usage/report/model?days=7` | 近 N 天按模型成本报表 |
| `GET /api/v1/admin/token-usage/report/user?days=7` | 近 N 天 Top 消费用户 |
| `GET /api/v1/admin/token-usage/error-rate?minutes=5` | 近 N 分钟 LLM 错误率 |
| `GET /actuator/prometheus` | Prometheus 指标 |
| `GET /actuator/**` | 其他 Actuator 端点 |

### 其他接口

| 接口 | 说明 |
|------|------|
| `GET /actuator/health` | 应用整体健康状态（公开） |
| `GET /api/v1/token-usage/my/today` | 查看自己今日费用 |
| `GET /` 或 `/index.html` | 内置 Web 测试页面（公开） |

---

## 配置说明

### JWT 配置

| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| `security.jwt.secret` | `JWT_SECRET` | `change-this-...` | **生产必改**，至少 32 个字符 |
| `security.jwt.expiration-seconds` | `JWT_EXPIRATION` | `86400` | Token 有效期（秒），默认 24 小时 |

### RAG 配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `agent.memory.max-messages` | `20` | 每个会话保留的最大消息条数 |
| `agent.memory.ttl-hours` | `24` | 对话记忆在 Redis 中的过期时长（小时） |
| `agent.rag.max-results` | `3` | RAG 最终返回的相关段落数 |
| `agent.rag.min-score` | `0.7` | 向量相似度阈值，低于此值不返回 |
| `agent.rag.chunk-size` | `500` | 文档切片大小（字符数） |
| `agent.rag.chunk-overlap` | `50` | 相邻切片的重叠字符数 |
| `rag.query.rewrite.variants` | `2` | 查询改写变体数（越多效果越好，但越慢） |
| `rag.retrieval.vector.top-k` | `20` | 向量检索初始召回数 |
| `rag.retrieval.rrf.top-k` | `10` | RRF 融合后保留数 |
| `rag.reranker.type` | `bge` | Reranker 类型：`bge`（本地）或 `cohere`（API） |
| `rag.reranker.top-k` | `5` | Reranker 精排后送给 LLM 的最终上下文数 |

### 限流配置

| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| `security.rate-limit.per-minute` | `RATE_LIMIT_PER_MINUTE` | `10` | 每分钟最大请求数 |
| `security.rate-limit.per-day` | `RATE_LIMIT_PER_DAY` | `500` | 每日最大请求数 |

DeepSeek 专属配置（`application-deepseek.yml`）：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `deepseek.api-key` | 通过环境变量 `DEEPSEEK_API_KEY` 注入 | DeepSeek API Key |
| `deepseek.base-url` | `https://api.deepseek.com/v1` | API 基础地址 |
| `deepseek.model-name` | `deepseek-chat` | 模型名称 |
| `deepseek.temperature` | `0.7` | 生成温度 |
| `deepseek.max-tokens` | `4096` | 最大输出 Token 数 |

Claude 专属配置（`application-claude.yml`）：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `anthropic.api-key` | 通过环境变量 `ANTHROPIC_API_KEY` 注入 | Anthropic API Key |
| `anthropic.model-name` | `claude-opus-4-8` | 模型名称 |
| `anthropic.max-tokens` | `8192` | 最大输出 Token 数 |

---

## 切换模型

项目通过 Spring Profile 机制实现模型热切换，无需修改代码。

### 切换为 DeepSeek（默认）

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
mvn spring-boot:run -Dspring-boot.run.profiles=deepseek
```

### 切换为 Claude

```bash
export ANTHROPIC_API_KEY=sk-ant-xxxxxxxxxxxxxxxx
mvn spring-boot:run -Dspring-boot.run.profiles=claude
```

两个 Profile 共享相同的 Agent、RAG、记忆、安全等所有组件，仅 `ChatLanguageModel` 和 `StreamingChatLanguageModel` 的实现切换，完全透明。

---

## 项目结构

```
ai-agent/
├── Dockerfile                          # 多阶段构建（Maven → JRE，非 root 用户）
├── docker-compose.yml                  # 一键启动（PG+pgvector、Redis、ES、App）
├── .env.example                        # 环境变量模板（复制为 .env 后填写）
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/example/aiagent/
    │   │   ├── AiAgentApplication.java
    │   │   │
    │   │   ├── agent/
    │   │   │   └── AgentFactory.java           # 组装 LLM + 记忆 + RAG + 工具
    │   │   │
    │   │   ├── config/
    │   │   │   ├── AppConfig.java              # 公共 Bean（RestTemplate、异步、定时）
    │   │   │   ├── DeepSeekProperties.java
    │   │   │   ├── LlmConfig.java              # ChatLanguageModel Bean（按 Profile 激活）
    │   │   │   └── RagConfig.java              # EmbeddingModel + PgVectorStore Bean
    │   │   │
    │   │   ├── controller/
    │   │   │   ├── ChatController.java         # POST /api/v1/chat（同步，含安全链路）
    │   │   │   └── StreamingChatController.java# GET  /api/v1/chat/stream（SSE 流式）
    │   │   │
    │   │   ├── dto/
    │   │   │   ├── ChatRequest.java
    │   │   │   └── ChatResponse.java
    │   │   │
    │   │   ├── exception/
    │   │   │   └── GlobalExceptionHandler.java
    │   │   │
    │   │   ├── kb/
    │   │   │   ├── controller/
    │   │   │   │   └── KnowledgeBaseController.java  # 知识库 CRUD + 问答接口
    │   │   │   ├── entity/
    │   │   │   ├── mapper/
    │   │   │   └── service/
    │   │   │       ├── KnowledgeBaseService.java     # 知识库管理（多租户隔离）
    │   │   │       └── KnowledgeBaseQueryService.java# 知识库问答（置信度评估）
    │   │   │
    │   │   ├── memory/
    │   │   │   └── RedisChatMemoryStore.java
    │   │   │
    │   │   ├── observability/
    │   │   │   ├── aop/
    │   │   │   │   ├── LlmObservabilityAspect.java  # AOP 采集 Token 用量
    │   │   │   │   └── TraceIdMdcFilter.java         # TraceId 注入 MDC
    │   │   │   ├── alert/
    │   │   │   │   ├── AlertService.java             # 定时告警（错误率/延迟/预算）
    │   │   │   │   └── AlertNotifier.java            # 钉钉/企微 Webhook 通知
    │   │   │   ├── controller/
    │   │   │   │   └── TokenUsageController.java     # 成本报表接口（ADMIN）
    │   │   │   ├── metrics/
    │   │   │   │   └── LlmMetricsRecorder.java
    │   │   │   └── service/
    │   │   │       └── TokenUsageService.java
    │   │   │
    │   │   ├── rag/
    │   │   │   ├── DocumentIngestService.java        # 文档 → 切片 → Embedding → 存储
    │   │   │   ├── generation/
    │   │   │   │   └── CitationAwareGenerator.java   # 带引用标注的答案生成
    │   │   │   ├── pipeline/
    │   │   │   │   └── HybridRagPipeline.java        # 混合 RAG 5步 Pipeline
    │   │   │   ├── query/
    │   │   │   │   └── QueryRewriter.java            # HyDE + 多角度改写
    │   │   │   ├── reranker/
    │   │   │   │   └── RerankerService.java
    │   │   │   └── retrieval/
    │   │   │       ├── Bm25Retriever.java            # Elasticsearch BM25 检索
    │   │   │       └── RrfFusionRanker.java          # RRF 融合排序
    │   │   │
    │   │   ├── security/
    │   │   │   ├── config/
    │   │   │   │   └── SecurityConfig.java           # JWT Filter + BCrypt + CORS
    │   │   │   ├── controller/
    │   │   │   │   └── AuthController.java           # /login + /register
    │   │   │   ├── dto/
    │   │   │   │   ├── LoginRequest.java
    │   │   │   │   ├── RegisterRequest.java
    │   │   │   │   └── AuthResponse.java
    │   │   │   ├── entity/
    │   │   │   │   └── SysUser.java                  # 认证用户实体
    │   │   │   ├── filter/
    │   │   │   │   ├── JwtAuthFilter.java            # 解析 Token，注入 SecurityContext
    │   │   │   │   ├── OutputContentFilter.java      # 输出内容脱敏
    │   │   │   │   └── PromptInjectionFilter.java    # Prompt 注入检测
    │   │   │   ├── mapper/
    │   │   │   │   └── SysUserMapper.java
    │   │   │   └── service/
    │   │   │       ├── AuthService.java              # 登录/注册业务
    │   │   │       ├── AuditLogService.java          # 审计日志
    │   │   │       ├── JwtService.java               # Token 生成/验证/解析
    │   │   │       ├── RateLimitService.java         # Redis 令牌桶限流
    │   │   │       └── UserDetailsServiceImpl.java   # Spring Security 用户加载
    │   │   │
    │   │   └── tool/
    │   │       └── BusinessTools.java                # Function Calling（订单/天气/账户/计算）
    │   │
    │   └── resources/
    │       ├── application.yml
    │       ├── application-deepseek.yml
    │       ├── application-claude.yml
    │       ├── application-prod.yml                  # 生产配置（JSON日志/HikariCP/优雅关机）
    │       ├── db/
    │       │   └── migration/
    │       │       └── V1__init_schema.sql           # Flyway 初始化脚本（自动执行）
    │       ├── mapper/
    │       │   └── *.xml                             # MyBatis XML 映射
    │       ├── prompts/
    │       │   └── system-assistant.st               # System Prompt 模板
    │       └── static/
    │           └── index.html                        # 内置 Web 测试页面
    │
    └── test/
        └── java/com/example/aiagent/
            ├── security/
            │   ├── filter/
            │   │   └── PromptInjectionFilterTest.java
            │   └── service/
            │       ├── JwtServiceTest.java
            │       └── RateLimitServiceTest.java
            └── kb/
                └── service/
                    └── KnowledgeBaseServiceTest.java
```

---

## 生产环境部署

### 启用生产配置

```bash
# 同时激活模型 Profile 和 prod Profile
SPRING_PROFILES_ACTIVE=deepseek,prod java -jar app.jar
```

`application-prod.yml` 包含：
- 关闭 MyBatis SQL 控制台打印
- 日志级别降为 `INFO`，输出 JSON 格式（方便 ELK/Loki 采集）
- HikariCP 连接池调优（最大 20 连接，心跳保活）
- 优雅关机（等待进行中请求完成，最多 30s）
- Actuator 端点详情仅对认证用户展示

### 关键生产配置检查清单

- [ ] `JWT_SECRET` 已替换为 32+ 字符随机字符串
- [ ] `PG_PASSWORD` 已设置强密码
- [ ] `REDIS_PASSWORD` 已设置
- [ ] `SPRING_PROFILES_ACTIVE` 包含 `prod`
- [ ] Nginx 已配置 HTTPS 并反向代理到 8080
- [ ] `SecurityConfig` 中 CORS `allowedOriginPatterns` 已改为具体前端域名

---

## BGE Reranker 本地部署

BGE-Reranker 以独立 Python FastAPI 服务运行，通过 HTTP 与 Spring Boot 通信，无需 GPU（CPU 可跑）。

### 1. 安装依赖

```bash
pip install sentence-transformers fastapi uvicorn
```

### 2. 创建服务脚本

新建文件 `reranker_server.py`：

```python
from fastapi import FastAPI
from sentence_transformers import CrossEncoder
from pydantic import BaseModel
from typing import List

app = FastAPI()
model = CrossEncoder("BAAI/bge-reranker-v2-m3")  # 首次运行自动下载（约 1.1GB）

class RerankRequest(BaseModel):
    query: str
    documents: List[str]

@app.post("/rerank")
def rerank(req: RerankRequest):
    pairs = [[req.query, doc] for doc in req.documents]
    scores = model.predict(pairs).tolist()
    return {"scores": scores}

@app.get("/health")
def health():
    return {"status": "ok"}
```

### 3. 启动服务

```bash
uvicorn reranker_server:app --host 0.0.0.0 --port 8090
```

### 4. 验证

```bash
curl -X POST http://localhost:8090/rerank \
  -H "Content-Type: application/json" \
  -d '{"query":"如何申请退款","documents":["退款需要在购买后7天内提交申请","产品使用手册第三章"]}'
# 期望响应：{"scores": [0.92, 0.03]}
```

若 BGE 服务不可用，应用自动降级为 RRF 原始排序，不影响核心功能。

### 备选：切换为 Cohere Rerank API

```yaml
rag:
  reranker:
    type: cohere
    cohere:
      api-key: ${COHERE_API_KEY}
      model: rerank-multilingual-v3.0
```

```bash
export COHERE_API_KEY=xxxxxxxxxxxxxxxx
```
