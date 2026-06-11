# AI Agent

基于 **Spring Boot 3.3.5 + LangChain4j 0.36.2** 构建的企业级 AI Agent。

支持同步对话、SSE 流式输出、ReAct 多步推理、多轮记忆、混合 RAG 知识库、Function Calling、JWT 认证、Token 成本追踪与智能告警。项目已前后端分离：后端提供 Spring Boot API，前端在 `frontend/` 中使用 Node.js + Vite 独立启动。

---

## 技术栈

| 分类 | 技术 | 版本 |
|------|------|------|
| 后端框架 | Spring Boot | 3.3.5 |
| 前端工程 | Node.js + Vite | 20+ / 5.x |
| 语言 | Java | 21 |
| AI 框架 | LangChain4j | 0.36.2 |
| 对话模型 | DeepSeek Chat（默认）/ Claude Opus 4.8 | — |
| Embedding | DeepSeek Embedding（无需下载本地模型） | — |
| 向量数据库 | PostgreSQL + pgvector | pg16 |
| ORM | MyBatis | 3.0.3 |
| 数据迁移 | Flyway（首次启动自动建表） | — |
| 缓存 / 记忆 | Redis | 7 |
| 全文检索 | Apache Lucene（内置）/ Elasticsearch（可选） | 9.10.0 / 8.11.4 |
| 安全认证 | Spring Security + JWT (JJWT) + BCrypt | 0.12.6 |
| 可观测性 | Micrometer + Prometheus + Zipkin | — |
| 告警通知 | 钉钉 / 企微 / 邮件 / 自定义 Webhook | — |
| 表达式引擎 | Aviator（沙箱数学计算） | 5.4.3 |

---

## 核心功能

### 三种对话模式

| 模式 | 接口 | 说明 |
|------|------|------|
| 同步对话 | `POST /api/v1/chat` | 等待完整回复，适合后端调用 |
| 流式对话 | `GET /api/v1/chat/stream` | SSE 逐 token 推送，实时打字效果 |
| ReAct 推理 | `POST /api/v1/chat/react` | 复杂任务自动拆解，多步调用工具，推理过程可见 |

### 知识库（RAG）

- 上传 **PDF / Word / TXT** 等多种格式，上传即可问答，无需编码
- **混合 RAG 5 步流水线**：查询改写（HyDE）→ 向量检索 → BM25 检索 → RRF 融合 → Reranker 精排
- **4 种 Reranker**：LLM / TF-IDF / BGE 本地 / Cohere API，环境变量切换
- **引用溯源**：答案自动标注来源文档和段落
- **置信度评估**：低置信度时明确返回"未找到"，不胡编
- **RAG 效果评估**：Faithfulness / AnswerRelevance / ContextPrecision 三项指标

### 安全防护

- **JWT 无状态认证**，支持 `Authorization: Bearer` Header 和 `?token=` URL 参数（SSE 场景）
- **Prompt 注入检测**：覆盖中英文 7 种攻击模式（越狱 / 角色扮演 / 指令覆盖 / 提示词泄露等）
- **输出内容脱敏**：手机号、身份证、银行卡号、密码/密钥
- **用户级限流**：每分钟 + 每日双维度，Redis 令牌桶，超限返回 429
- **操作审计**：登录、对话、安全拦截事件异步记录

### 可观测性

- **Token 成本追踪**：每次 LLM 调用的 Token 数和 USD 费用实时入库
- **成本报表 API**：按用户、按模型、按天统计，管理员直接查询
- **Prometheus 指标**：调用次数、P99 延迟、错误率、当前并发（7 类指标）
- **链路追踪**：TraceId 全链路贯穿，每行日志自动携带，支持 Zipkin 上报
- **智能告警**：错误率 / P99 延迟 / 日费用超阈值，自动推送通知

### 内置工具（Function Calling）

AI 在对话时**自动决策**何时调用，无需用户触发：

| 工具 | 说明 |
|------|------|
| `queryOrderStatus` | 查询订单状态和物流 |
| `queryUserOrders` | 查询用户最近 10 条订单 |
| `queryOrderSummary` | 统计各状态订单数量 |
| `getWeather` | 查城市天气（30 分钟缓存 + 三级降级）|
| `queryUserAccount` | 查账户余额和会员等级 |
| `queryUserPoints` | 查积分余额和升级进度 |
| `calculate` | 安全数学计算（Aviator 沙箱）|
| `getCurrentDateTime` | 获取当前日期时间（支持时区）|

---

## 快速开始

### 前置要求

| 工具 | 要求 |
|------|------|
| JDK | **21+**（必须，项目使用 Java 21 特性） |
| Maven | 3.8+ |
| Node.js | **20+**（前端独立启动） |
| Docker | 24+ |
| DeepSeek API Key | [platform.deepseek.com](https://platform.deepseek.com) 注册申请 |

### 第一步：启动依赖服务

```bash
git clone <your-repo-url>
cd ai-agent

# 启动 PostgreSQL（含 pgvector）+ Redis
docker-compose up -d postgres redis

# 确认服务就绪
docker-compose ps   # Status 显示 healthy
```

### 第二步：配置环境变量

```bash
# 必填
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
export SPRING_PROFILES_ACTIVE=deepseek
export JWT_SECRET=your-secret-key-min-32-characters-long

# 可选（不填则用默认值 localhost）
export PG_HOST=localhost
export PG_PASSWORD=postgres
export REDIS_HOST=localhost
```

### 第三步：启动后端

```bash
# 确认 Java 版本
java -version   # 必须显示 21.x.x

# 启动（Flyway 自动建 8 张表，无需手动执行 SQL）
mvn spring-boot:run
```

### 第四步：启动前端

```bash
cd frontend
npm install
npm run dev
```

前端默认运行在 `http://localhost:5173`，开发环境会把 `/api` 和 `/actuator` 代理到 `http://localhost:8080`。

如需修改代理目标：

```bash
cd frontend
cp .env.example .env
# 修改 VITE_BACKEND_TARGET=http://your-backend:8080
npm run dev
```

### 第五步：验证

```bash
# 健康检查
curl http://localhost:8080/actuator/health

# 打开独立前端（含登录/注册页 + 对话 + 知识库管理）
open http://localhost:5173

# 或用命令行注册并登录
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin123!"}'

TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin123!"}' | jq -r '.token')

curl -X POST http://localhost:8080/api/v1/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"s001","message":"你好，介绍一下你能做什么"}'
```

---

## API 文档

> 除认证接口外，所有接口需携带 `Authorization: Bearer <token>`

### 认证

```
POST /api/v1/auth/register   注册（返回 Token）
POST /api/v1/auth/login      登录（返回 Token）
```

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin123!"}'
# → {"token":"eyJ...","tokenType":"Bearer","expiresIn":86400}
```

---

### 对话

#### 同步对话

```
POST /api/v1/chat
```

```json
// 请求
{"sessionId": "s001", "message": "帮我查一下订单 #12345 的物流"}

// 响应
{"sessionId": "s001", "reply": "您的订单 #12345 已发货...", "durationMs": 1823}
```

#### 流式对话（SSE）

```
GET /api/v1/chat/stream?sessionId=s001&message=你好
```

```javascript
const es = new EventSource(
  `/api/v1/chat/stream?sessionId=s001&message=${encodeURIComponent(msg)}`,
  { headers: { 'Authorization': 'Bearer ' + token } }
);
es.onmessage = e => { if (e.data !== '[DONE]') output.textContent += e.data; };
es.addEventListener('done', () => es.close());
```

#### ReAct 多步推理

```
POST /api/v1/chat/react
```

```json
// 请求
{"sessionId": "s001", "message": "查询用户 U001 的所有订单并统计总金额"}

// 响应（包含完整推理过程）
{
  "answer": "用户 U001 共有 3 笔订单，总金额 ¥1,580.00",
  "iterations": 2,
  "durationMs": 4521,
  "steps": [
    {
      "iteration": 1,
      "thought": "需要先查询 U001 的订单列表",
      "toolName": "queryUserOrders",
      "toolArgs": "{\"userId\":\"U001\"}",
      "observation": "找到 3 笔订单：¥580、¥600、¥400"
    },
    {
      "iteration": 2,
      "thought": "已有数据，计算总金额",
      "toolName": "calculate",
      "toolArgs": "{\"expression\":\"580+600+400\"}",
      "observation": "计算结果：1580"
    }
  ]
}
```

#### 清除会话记忆

```
DELETE /api/v1/chat/memory/{sessionId}
```

---

### 知识库

```
POST   /api/v1/kb                              创建知识库
GET    /api/v1/kb                              列出我的知识库
DELETE /api/v1/kb/{kbId}                       删除知识库（级联删除所有文档）
POST   /api/v1/kb/{kbId}/documents            上传文档（PDF/Word/TXT）
GET    /api/v1/kb/{kbId}/documents            列出文档
DELETE /api/v1/kb/{kbId}/documents/{docId}    删除文档
POST   /api/v1/kb/{kbId}/query                知识库问答
GET    /api/v1/kb/{kbId}/stats                统计信息
```

**上传文档**：

```bash
curl -X POST http://localhost:8080/api/v1/kb/1/documents \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@员工手册.pdf"
```

**知识库问答响应示例**：

```json
{
  "answer": "根据员工手册第三章，正式员工享有 10 天带薪年假...",
  "answerFound": true,
  "confidence": "0.870",
  "citations": [
    {
      "source": "员工手册.pdf",
      "score": "0.950",
      "snippet": "正式员工享有 10 天带薪年假，工龄满 5 年后增加至 15 天..."
    }
  ]
}
```

---

### 管理接口（需要 ADMIN 角色）

```
GET  /api/v1/token-usage/my/today                     我今日费用
GET  /api/v1/admin/token-usage/today                  全局今日总费用（USD）
GET  /api/v1/admin/token-usage/report/model?days=7    按模型成本报表
GET  /api/v1/admin/token-usage/report/user?days=7     按用户成本 Top N
GET  /api/v1/admin/token-usage/error-rate?minutes=5   近 N 分钟错误率
POST /api/v1/rag/eval/single                           单条 RAG 评估
POST /api/v1/rag/eval/batch                            批量 RAG 评估（最多 100 条）
GET  /actuator/health                                  健康检查（公开）
GET  /actuator/prometheus                              Prometheus 指标
```

---

## 配置说明

### 核心配置项

```yaml
# application.yml

agent:
  memory:
    max-messages: 20        # 每会话保留最近 N 条消息
    ttl-hours: 24           # 记忆过期时间（从最后活跃时间起算）

rag:
  retrieval:
    vector:
      top-k: 20             # 向量检索初始召回数
      threshold: 0.5        # 相似度阈值，低于丢弃
    rrf:
      top-k: 10             # RRF 融合后保留数
  reranker:
    type: llm               # llm / tfidf / bge / cohere（环境变量 RERANKER_TYPE）
    top-k: 5                # 最终送给 LLM 的段落数
  elasticsearch:
    enabled: false          # true 启用 ES BM25，需先启动 ES

security:
  jwt:
    secret: ${JWT_SECRET}   # 至少 32 字符
    expiration-seconds: 86400
  rate-limit:
    per-minute: 10
    per-day: 500

kb:
  confidence-threshold: 0.6   # 低于此值返回"未找到"
```

### 生产覆盖（application-prod.yml）

- SQL 日志关闭，日志 INFO 级别，JSON 格式（ELK/Loki 兼容）
- 数据库连接池：最大 20，最小空闲 5
- 告警阈值：错误率 3%，P99 延迟 20s，日预算 $50
- Actuator 只暴露 `health`、`prometheus`、`info`
- 优雅停机：30 秒等待在途请求

### 告警通知

```yaml
llm:
  observability:
    alert:
      dingtalk:
        enabled: ${ALERT_DINGTALK_ENABLED:false}
        webhook: ${ALERT_DINGTALK_WEBHOOK:}
      wecom:
        enabled: ${ALERT_WECOM_ENABLED:false}
        webhook: ${ALERT_WECOM_WEBHOOK:}
      custom:
        enabled: ${ALERT_CUSTOM_ENABLED:false}
        webhook: ${ALERT_CUSTOM_WEBHOOK:}
```

---

## 切换对话模型

### DeepSeek（默认）

```bash
export SPRING_PROFILES_ACTIVE=deepseek
export DEEPSEEK_API_KEY=sk-xxx
```

### Claude

```bash
export SPRING_PROFILES_ACTIVE=claude
export ANTHROPIC_API_KEY=sk-ant-xxx
export DEEPSEEK_API_KEY=sk-xxx   # Embedding 仍使用 DeepSeek，必须设置
```

> Embedding 统一使用 DeepSeek（向量维度 1536），切换对话模型无需重建知识库。

---

## 启用 BM25 混合检索

默认使用内置 Apache Lucene 做 BM25。可选接入 Elasticsearch 以支持中文 IK 分词：

```bash
# 启动 Elasticsearch
docker-compose --profile bm25 up -d elasticsearch

export ES_ENABLED=true
export ES_HOST=localhost
```

启用后向量检索与 BM25 并行执行，RRF 融合，Recall@5 约提升 30%。

---

## Reranker 选择

| 配置值 | 方式 | 特点 |
|--------|------|------|
| `llm`（默认）| 调 DeepSeek 打分 | 效果最好，消耗 Token |
| `tfidf` | 本地 TF-IDF | 零成本，适合快速验证 |
| `bge` | 本地 BGE 模型 | 效果好，不耗 Token，需部署 Python 服务 |
| `cohere` | Cohere Rerank API | 无需 GPU，按量付费 |

### 部署 BGE Reranker

```bash
pip install sentence-transformers fastapi uvicorn

cat > reranker_server.py << 'EOF'
from fastapi import FastAPI
from sentence_transformers import CrossEncoder
from pydantic import BaseModel
from typing import List

app = FastAPI()
model = CrossEncoder("BAAI/bge-reranker-v2-m3")

class RerankRequest(BaseModel):
    query: str
    documents: List[str]

@app.post("/rerank")
def rerank(req: RerankRequest):
    pairs = [[req.query, doc] for doc in req.documents]
    return {"scores": model.predict(pairs).tolist()}
EOF

uvicorn reranker_server:app --host 0.0.0.0 --port 8090
```

配置：`RERANKER_TYPE=bge`

---

## 项目结构

```
ai-agent/
├── src/main/java/com/example/aiagent/
│   ├── AiAgentApplication.java
│   │
│   ├── agent/
│   │   ├── AgentFactory.java          # 组装 LLM + 记忆 + RAG + 工具
│   │   └── ReActAgent.java            # ReAct 多步推理（最多 8 轮迭代）
│   │
│   ├── config/
│   │   ├── AppConfig.java             # RestTemplate / 异步 / 定时
│   │   ├── LlmConfig.java             # 对话模型 Bean（按 Profile 激活）
│   │   ├── RagConfig.java             # Embedding + PgVector
│   │   ├── ElasticsearchConfig.java   # ES 客户端（@ConditionalOnProperty）
│   │   └── DeepSeekProperties.java
│   │
│   ├── controller/
│   │   ├── ChatController.java            # POST /api/v1/chat
│   │   ├── StreamingChatController.java   # GET  /api/v1/chat/stream（SSE）
│   │   └── ReActChatController.java       # POST /api/v1/chat/react
│   │
│   ├── kb/                            # 知识库模块
│   │   ├── controller/                # CRUD + 问答接口
│   │   ├── entity/                    # KnowledgeBase / Document / Chunk / RetrievalLog
│   │   ├── mapper/                    # MyBatis Mapper（4 个）
│   │   └── service/                   # KnowledgeBaseService / KnowledgeBaseQueryService
│   │
│   ├── rag/                           # RAG 核心
│   │   ├── pipeline/HybridRagPipeline # 5 步混合检索流水线
│   │   ├── query/QueryRewriter        # HyDE + 多角度查询改写
│   │   ├── retrieval/                 # 向量检索 / BM25 / RRF 融合 / 索引恢复
│   │   ├── reranker/RerankerService   # 4 种 Reranker 统一接口
│   │   ├── generation/                # 带引用标注的答案生成
│   │   ├── evaluation/                # RAG 质量评估（Controller + Service）
│   │   ├── model/                     # RetrievedChunk / RagResponse
│   │   └── DocumentIngestService.java # 文档解析 → 切片 → 向量化 → 存储
│   │
│   ├── security/                      # 安全模块
│   │   ├── config/SecurityConfig      # Spring Security 配置
│   │   ├── controller/AuthController  # 登录 / 注册
│   │   ├── filter/                    # JWT 验证 / Prompt 注入检测 / 输出脱敏
│   │   ├── entity/SysUser.java
│   │   ├── mapper/SysUserMapper.java
│   │   └── service/                   # JWT / 限流 / 审计 / 用户详情
│   │
│   ├── observability/                 # 可观测性
│   │   ├── aop/LlmObservabilityAspect # AOP 拦截所有 LLM 调用
│   │   ├── aop/TraceIdMdcFilter       # 每个请求注入唯一 TraceId
│   │   ├── metrics/LlmMetricsRecorder # 7 类 Prometheus 指标
│   │   ├── alert/                     # 定时检查 + 多渠道告警
│   │   ├── controller/                # Token 用量 / 成本报表接口
│   │   ├── entity/TokenUsageRecord
│   │   ├── mapper/TokenUsageMapper
│   │   ├── model/                     # LlmCallContext / TokenPricing 枚举
│   │   └── service/TokenUsageService  # 成本统计 / 报表
│   │
│   ├── tool/                          # Function Calling 工具
│   │   ├── BusinessTools.java         # 8 个工具方法（@Tool 注解）
│   │   ├── client/WeatherApiClient    # OpenWeatherMap + 三级降级
│   │   ├── entity/                    # Order / UserAccount / WeatherCache
│   │   └── mapper/                    # MyBatis Mapper（3 个）
│   │
│   └── memory/
│       └── RedisChatMemoryStore       # Redis 持久化对话记忆（滑动 TTL）
│
├── src/main/resources/
│   ├── application.yml                # 主配置
│   ├── application-deepseek.yml       # DeepSeek 模型配置
│   ├── application-claude.yml         # Claude 模型配置
│   ├── application-prod.yml           # 生产覆盖（日志/连接池/告警阈值）
│   ├── mapper/                        # MyBatis XML（9 个文件）
│   ├── db/migration/
│   │   └── V1__init_schema.sql        # Flyway 初始化脚本（8 张表）
│   ├── prompts/
│   │   └── system-assistant.st        # 系统提示词模板
│
├── frontend/                          # 独立 Node.js 前端工程
│   ├── index.html                     # 主页（对话 + 知识库管理）
│   ├── login.html                     # 登录 / 注册页（响应式，移动端适配）
│   ├── src/css/main.css
│   ├── src/js/                        # app / auth / api / chat / knowledge-base 等
│   ├── package.json
│   └── vite.config.js                 # 本地代理 / 多页面构建配置
│
└── docker-compose.yml                 # PostgreSQL + Redis + Elasticsearch
```

---

## 数据库表结构（8 张表）

| 表名 | 说明 |
|------|------|
| `biz_user_account` | 用户表（含 password_hash、roles、会员等级、积分）|
| `kb_knowledge_base` | 知识库（多租户隔离，tenant_id 字段）|
| `kb_document` | 上传文档（含 parse_status、file_hash 增量检测）|
| `kb_chunk` | 文档切片（含 content_hash，支持增量更新）|
| `kb_retrieval_log` | 检索日志（含各阶段耗时，用于 RAG 效果分析）|
| `llm_token_usage` | LLM 调用 Token 数和 USD 费用（含 TraceId）|
| `biz_order` | 示例业务：订单 |
| `biz_weather_cache` | 天气缓存（30 分钟有效期）|

Flyway 在首次启动时自动执行建表脚本，无需手动操作。

---

## 生产部署

### 前端部署

```bash
cd frontend
npm install
VITE_API_BASE_URL=https://api.example.com npm run build
npm run preview
```

如果前端和后端同域反代，`VITE_API_BASE_URL` 可以留空，让请求继续走相对路径 `/api/...`。

### 环境变量清单

```bash
# LLM（必填）
DEEPSEEK_API_KEY=sk-xxx
ANTHROPIC_API_KEY=sk-ant-xxx   # claude profile 时必填

# 安全（必填）
JWT_SECRET=<至少 32 字符的随机字符串>

# 数据库
PG_HOST=your-db-host
PG_PORT=5432
PG_DB=aiagent
PG_USER=postgres
PG_PASSWORD=your-password

# Redis
REDIS_HOST=your-redis-host
REDIS_PASSWORD=your-password

# 可选
ES_ENABLED=true
ES_HOST=your-es-host
WEATHER_API_KEY=your-openweathermap-key
ALERT_DINGTALK_ENABLED=true
ALERT_DINGTALK_WEBHOOK=https://oapi.dingtalk.com/robot/send?access_token=xxx
```

### 启动命令

```bash
export SPRING_PROFILES_ACTIVE=deepseek,prod

java -Xmx2g -jar ai-agent-1.0.0.jar
```

---

## 常见问题

**Q：启动报 `WeakKeyException`**

JWT_SECRET 长度不足 32 字符：
```bash
export JWT_SECRET=$(openssl rand -base64 32)
```

**Q：mvn compile 报 Text Block / record 语法错误**

需要 Java 21：
```bash
java -version           # 确认显示 21.x.x
export JAVA_HOME=/path/to/jdk21
```

**Q：知识库上传后查询不到内容**

确认 pgvector 扩展已安装（Flyway 脚本会自动执行，需要 superuser 权限）：
```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

**Q：切换 Claude 后需要重建知识库吗？**

不需要。Embedding 统一使用 DeepSeek，与对话模型无关。

**Q：文档问答 vs Function Calling，该用哪个？**

| 场景 | 方案 |
|------|------|
| 企业制度、产品手册、FAQ 等静态文档 | 上传知识库（RAG），无需写代码 |
| 查询实时数据库、做增删改操作 | Function Calling，在 `BusinessTools.java` 中实现对应方法 |
