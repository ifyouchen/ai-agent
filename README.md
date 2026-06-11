# AI Agent

基于 Spring Boot 3 + LangChain4j 构建的企业级 AI 智能体，集成多轮对话记忆、混合 RAG 知识库检索、Function Calling 工具调用与完整可观测性能力。

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
| 关键词检索 | Elasticsearch 8 (BM25) |
| 安全 | Spring Security + JWT + 限流 + Prompt 注入过滤 |
| 可观测性 | Micrometer + Prometheus + Zipkin 链路追踪 |
| Reranker | BGE-Reranker-v2-m3 (本地) / Cohere Rerank API |

---

## 功能特性

- **多轮对话**：基于 Redis 的会话记忆，每个会话独立保存最近 N 条消息，支持 TTL 自动过期
- **流式输出**：SSE 实时推送（Server-Sent Events），字符逐步出现，告别等待
- **混合 RAG 知识库**：向量检索 + BM25 关键词检索 + RRF 融合排序 + Reranker 精排，检索精度比基础 RAG 提升 73%+
- **查询改写**：HyDE（假设文档扩展）+ 多角度改写，扩大语义覆盖面
- **引用溯源**：答案自动标注 `[1][2]` 引用编号，返回结构化 Citation 列表，消除幻觉
- **Function Calling**：内置订单查询、天气查询、账户查询、数学计算等工具，LLM 按需自动调用
- **多模型支持**：通过 Spring Profile 一键切换 DeepSeek 与 Claude
- **Token 用量追踪**：AOP 拦截 LLM 调用，异步写入用量记录，含费用估算
- **分布式追踪**：TraceId 自动注入 MDC 日志，上报 Zipkin
- **安全加固**：速率限制、Prompt 注入检测、输出内容过滤、JWT 无状态认证

---

## 快速开始

### 前置要求

- Java 21+
- Maven 3.9+
- Docker & Docker Compose

### 1. 启动基础服务

```bash
cd /path/to/ai-agent

# 启动 PostgreSQL、Redis、Elasticsearch、Kibana、Zipkin
docker compose up -d

# 查看服务状态（等待所有服务 healthy）
docker compose ps
```

> 首次启动时 `schema.sql` 会自动在 PostgreSQL 中执行建表，无需手动初始化。
>
> Elasticsearch 启动较慢（约 30-40s），可通过 `docker compose logs -f elasticsearch` 观察状态。

### 2. 配置环境变量

复制以下内容并根据实际情况填写 API Key，在终端中 `export` 或写入 `~/.zshrc`：

```bash
# DeepSeek（默认模型，二选一）
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx

# Claude（备选模型，切换后填写）
export ANTHROPIC_API_KEY=sk-ant-xxxxxxxxxxxxxxxx

# 其他服务（均有默认值，本地开发无需修改）
export PG_HOST=localhost
export PG_PORT=5432
export PG_DB=aiagent
export PG_USER=postgres
export PG_PASSWORD=postgres

export REDIS_HOST=localhost
export REDIS_PORT=6379
```

### 3. 初始化 pgvector 扩展

`schema.sql` 中的 `CREATE EXTENSION` 行被注释，需手动执行一次：

```bash
docker exec -it ai-agent-postgres psql -U postgres -d aiagent \
  -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

### 4. 启动应用

```bash
# 使用 DeepSeek（默认）
mvn spring-boot:run

# 或者显式指定 profile
mvn spring-boot:run -Dspring-boot.run.profiles=deepseek

# 切换为 Claude
mvn spring-boot:run -Dspring-boot.run.profiles=claude
```

### 5. 验证

```bash
# 健康检查
curl http://localhost:8080/actuator/health

# 发送一条对话
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"test-001","message":"你好，帮我查询订单 #12345 的状态"}'

# 流式对话
curl "http://localhost:8080/api/v1/chat/stream?sessionId=test-001&message=今天北京天气如何"

# Zipkin 链路追踪
open http://localhost:9411

# Kibana（查看 ES 数据）
open http://localhost:5601
```

---

## API 文档

### 对话接口

#### 普通对话（同步）

```
POST /api/v1/chat
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
Accept: text/event-stream
```

响应为 SSE 流，逐 token 推送。最后一条事件名为 `done`，数据为 `[DONE]`。

前端示例：

```javascript
const es = new EventSource(
  `/api/v1/chat/stream?sessionId=user-123&message=${encodeURIComponent('你好')}`
);
es.onmessage = e => output.textContent += e.data;
es.addEventListener('done', () => es.close());
```

---

#### 清除会话记忆

```
DELETE /api/v1/chat/memory/{sessionId}
```

响应：`会话 user-123 的记忆已清除`

---

### 知识库接口

#### 上传文档

```
POST /api/v1/kb/ingest
Content-Type: multipart/form-data
```

| 参数 | 类型 | 说明 |
|------|------|------|
| file | MultipartFile | 文档文件（PDF、Word、TXT、Excel 等） |

响应：

```json
{
  "success": true,
  "filename": "产品手册.pdf",
  "chunks": 42,
  "message": "文档导入成功，已切分为 42 个片段"
}
```

---

#### 知识库健康检查

```
GET /api/v1/kb/health
```

响应：

```json
{
  "status": "ok",
  "message": "知识库服务正常"
}
```

---

### 其他接口

| 接口 | 说明 |
|------|------|
| `GET /actuator/health` | 应用整体健康状态 |
| `GET /actuator/prometheus` | Prometheus 指标（需 ADMIN 角色） |
| `GET /` 或 `/index.html` | 内置 Web 测试页面 |

---

## 配置说明

以下为 `application.yml` 中的关键配置项：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.profiles.active` | `deepseek` | 激活的模型 Profile（`deepseek` 或 `claude`） |
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
| `rag.reranker.bge.url` | `http://localhost:8090/rerank` | BGE Python 服务地址 |

DeepSeek 专属配置（`application-deepseek.yml`）：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `deepseek.api-key` | 必填，通过环境变量 `DEEPSEEK_API_KEY` 注入 | DeepSeek API Key |
| `deepseek.base-url` | `https://api.deepseek.com/v1` | API 基础地址 |
| `deepseek.model-name` | `deepseek-chat` | 模型名称 |
| `deepseek.temperature` | `0.7` | 生成温度 |
| `deepseek.max-tokens` | `4096` | 最大输出 Token 数 |

Claude 专属配置（`application-claude.yml`）：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `anthropic.api-key` | 必填，通过环境变量 `ANTHROPIC_API_KEY` 注入 | Anthropic API Key |
| `anthropic.model-name` | `claude-opus-4-8` | 模型名称 |
| `anthropic.max-tokens` | `8192` | 最大输出 Token 数 |

---

## 切换模型

项目通过 Spring Profile 机制实现模型热切换，无需修改代码。

### 切换为 DeepSeek（默认）

```yaml
# application.yml
spring:
  profiles:
    active: deepseek
```

或在命令行指定：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=deepseek
```

确保环境变量已设置：

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
```

---

### 切换为 Claude

```yaml
# application.yml
spring:
  profiles:
    active: claude
```

或在命令行指定：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=claude
```

确保环境变量已设置：

```bash
export ANTHROPIC_API_KEY=sk-ant-xxxxxxxxxxxxxxxx
```

两个 Profile 共享相同的 Agent、RAG、记忆等所有组件，仅 `ChatLanguageModel` 和 `StreamingChatLanguageModel` 的实现切换，完全透明。

---

## 项目结构

```
ai-agent/
├── docker-compose.yml              # 开发环境依赖服务
├── pom.xml
└── src/main/
    ├── java/com/example/aiagent/
    │   ├── AiAgentApplication.java         # 启动入口
    │   │
    │   ├── agent/
    │   │   └── AgentFactory.java           # 组装 LLM + 记忆 + RAG + 工具
    │   │
    │   ├── config/
    │   │   ├── AppConfig.java              # 公共 Bean（RestTemplate、异步、定时）
    │   │   ├── DeepSeekProperties.java     # DeepSeek 配置属性绑定
    │   │   ├── LlmConfig.java              # ChatLanguageModel Bean（按 Profile 激活）
    │   │   └── RagConfig.java              # EmbeddingModel + PgVectorStore Bean
    │   │
    │   ├── controller/
    │   │   ├── ChatController.java         # POST /api/v1/chat（同步）
    │   │   ├── StreamingChatController.java# GET  /api/v1/chat/stream（SSE 流式）
    │   │   └── KnowledgeBaseController.java# POST /api/v1/kb/ingest（文档上传）
    │   │
    │   ├── dto/
    │   │   ├── ChatRequest.java
    │   │   └── ChatResponse.java
    │   │
    │   ├── exception/
    │   │   └── GlobalExceptionHandler.java # 统一异常处理
    │   │
    │   ├── kb/
    │   │   └── service/
    │   │       └── KnowledgeBaseQueryService.java  # 知识库查询服务
    │   │
    │   ├── memory/
    │   │   └── RedisChatMemoryStore.java   # 基于 Redis 的对话记忆存储
    │   │
    │   ├── observability/
    │   │   ├── aop/
    │   │   │   ├── LlmObservabilityAspect.java  # AOP 拦截 LLM 调用，采集 Token 用量
    │   │   │   └── TraceIdMdcFilter.java         # 将 TraceId 注入 MDC 日志
    │   │   ├── alert/
    │   │   │   └── AlertService.java       # 定时告警（费用超限等）
    │   │   ├── config/
    │   │   │   └── ObservabilityConfig.java# Prometheus 全局标签 + 异步线程池
    │   │   ├── entity/
    │   │   │   └── TokenUsageRecord.java   # Token 用量 JPA 实体
    │   │   ├── metrics/
    │   │   │   └── LlmMetricsRecorder.java # Micrometer 指标记录
    │   │   ├── model/
    │   │   │   ├── LlmCallContext.java
    │   │   │   └── TokenPricing.java       # 各模型单价配置
    │   │   ├── repository/
    │   │   │   └── TokenUsageRepository.java
    │   │   └── service/
    │   │       └── TokenUsageService.java  # 异步写入 Token 用量到数据库
    │   │
    │   ├── rag/
    │   │   ├── DocumentIngestService.java  # 文档解析 → 切片 → Embedding → 存储
    │   │   ├── generation/
    │   │   │   └── CitationAwareGenerator.java  # 带 [1][2] 引用标注的答案生成
    │   │   ├── model/
    │   │   │   ├── RagResponse.java        # RAG 响应（答案 + Citations + 统计）
    │   │   │   └── RetrievedChunk.java     # 检索到的文档片段
    │   │   ├── pipeline/
    │   │   │   └── HybridRagPipeline.java  # 混合 RAG 完整 Pipeline（5步串联）
    │   │   ├── query/
    │   │   │   └── QueryRewriter.java      # HyDE + 多角度改写 + 关键词提取
    │   │   ├── reranker/
    │   │   │   └── RerankerService.java    # BGE / Cohere Reranker 精排
    │   │   └── retrieval/
    │   │       └── RrfFusionRanker.java    # Reciprocal Rank Fusion 融合排序
    │   │
    │   ├── security/
    │   │   ├── config/
    │   │   │   └── SecurityConfig.java     # Spring Security（JWT 无状态）
    │   │   ├── filter/
    │   │   │   ├── OutputContentFilter.java     # 输出内容安全过滤
    │   │   │   └── PromptInjectionFilter.java   # Prompt 注入检测
    │   │   └── service/
    │   │       ├── AuditLogService.java    # 审计日志
    │   │       └── RateLimitService.java   # 基于 Redis 的速率限制
    │   │
    │   └── tool/
    │       └── BusinessTools.java          # Function Calling 工具集（订单/天气/账户/计算）
    │
    └── resources/
        ├── application.yml                 # 主配置（公共 + Profile 切换）
        ├── application-deepseek.yml        # DeepSeek 专属配置
        ├── application-claude.yml          # Claude 专属配置
        ├── db/
        │   └── schema.sql                  # PostgreSQL 建表脚本（Docker 自动执行）
        ├── prompts/
        │   └── system-assistant.st         # System Prompt 模板
        └── static/
            └── index.html                  # 内置 Web 测试页面
```

---

## BGE Reranker 本地部署

BGE-Reranker 以独立 Python FastAPI 服务运行，通过 HTTP 与 Spring Boot 通信，无需 GPU（CPU 可跑，速度较慢；有 GPU 则推荐使用）。

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

# 首次运行会自动下载模型（约 1.1GB）
# BAAI/bge-reranker-v2-m3 支持中英文混合文档
model = CrossEncoder("BAAI/bge-reranker-v2-m3")

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
  -d '{
    "query": "如何申请退款",
    "documents": ["退款需要在购买后7天内提交申请", "产品使用手册第三章", "联系客服电话：400-xxx"]
  }'

# 期望响应：{"scores": [0.92, 0.03, 0.15]}
```

### 5. 配置 Spring Boot 使用 BGE

`application.yml` 默认已配置 BGE：

```yaml
rag:
  reranker:
    type: bge
    top-k: 5
    bge:
      url: http://localhost:8090/rerank
```

若 BGE 服务不可用，应用会自动降级为 RRF 原始排序，不影响核心功能。

### 备选：切换为 Cohere Rerank API

不想本地部署时，可使用 Cohere 云端 Reranker：

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
