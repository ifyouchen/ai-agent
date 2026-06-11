# AI Agent

基于 **Spring Boot 3.3.5 + LangChain4j 0.36.2** 构建的企业级 AI Agent，支持多模型热切换、混合 RAG 知识库、ReAct 多步推理、多轮对话记忆、Function Calling、JWT 认证、Token 成本追踪等完整能力。

---

## 技术栈

| 层次 | 技术选型 | 版本 |
|------|---------|------|
| 后端框架 | Spring Boot | 3.3.5 |
| 语言 | Java | 21 |
| AI 框架 | LangChain4j | 0.36.2 |
| 对话模型 | DeepSeek Chat（默认）/ Claude Opus 4.8（可选） | — |
| Embedding | DeepSeek Embedding（统一，无需下载本地模型） | — |
| 向量数据库 | PostgreSQL + pgvector | pg16 |
| ORM | MyBatis | 3.0.3 |
| 数据迁移 | Flyway（自动建表） | — |
| 缓存 / 记忆 | Redis | 7 |
| 全文检索 | Apache Lucene（内置）/ Elasticsearch（可选） | 9.10.0 / 8.11.4 |
| 安全 | Spring Security + JWT（JJWT）+ BCrypt | 0.12.6 |
| 可观测性 | Micrometer + Prometheus + Zipkin | — |
| 告警通知 | 钉钉 / 企微 / 邮件 / 自定义 Webhook | — |
| 表达式引擎 | Aviator（沙箱数学计算） | 5.4.3 |

---

## 核心功能

### 对话能力
- **同步对话**：等待完整回复，适合后端集成
- **SSE 流式对话**：字符逐步推送，实时打字效果
- **ReAct 多步推理**：复杂任务自动拆解，分步调用工具，支持中间步骤可见
- **多轮对话记忆**：Redis 持久化，服务重启不丢失，滑动 TTL 自动过期
- **Function Calling**：AI 自动决策调用时机，内置订单/天气/账户/计算/时间 8 个工具

### 知识库（RAG）
- 支持上传 **PDF / Word / TXT** 等多种格式，上传即用，无需写代码
- **混合 RAG 5步流水线**：查询改写（HyDE）→ 向量检索 → BM25 检索 → RRF 融合 → Reranker 精排
- **4种 Reranker**：LLM / TF-IDF / BGE 本地 / Cohere API，按需选择
- **引用溯源**：答案自动标注来源文档和段落编号
- **置信度评估**：低置信度明确返回"未找到"，不胡编
- **RAG 效果评估**：内置 Faithfulness / AnswerRelevance 等指标接口

### 安全防护
- **JWT 无状态认证**，支持 Header 和 URL 参数两种携带方式
- **Prompt 注入检测**：覆盖中英文 7 种攻击模式（规则 + 关键词 + 长度限制）
- **输出内容脱敏**：自动识别手机号、身份证、银行卡、密码/密钥
- **用户级限流**：每分钟 + 每日双维度，Redis 令牌桶，超限返回 429
- **审计日志**：登录、对话、安全拦截事件异步记录

### 可观测性
- **Token 成本追踪**：每次 LLM 调用的 Token 数和费用实时写入数据库
- **成本报表**：按用户、按模型、按天统计，REST 接口直接查询
- **Prometheus 指标**：调用次数、P99 延迟、错误率、当前并发数（7 类指标）
- **链路追踪**：TraceId 全链路贯穿，每行日志自动携带，支持上报 Zipkin
- **智能告警**：错误率 / P99 延迟 / 日费用超阈值，自动推送钉钉/企微/邮件

---

## 快速开始

### 前置要求

| 工具 | 版本要求 | 说明 |
|------|---------|------|
| JDK | **21+** | 项目使用 Java 21 特性，必须 |
| Maven | 3.8+ | |
| Docker | 24+ | 启动依赖服务 |
| DeepSeek API Key | — | [platform.deepseek.com](https://platform.deepseek.com) 免费注册 |

### 第一步：克隆并启动依赖

```bash
git clone <your-repo-url>
cd ai-agent

# 启动 PostgreSQL（含 pgvector 扩展）+ Redis
docker-compose up -d postgres redis

# 等待服务就绪（约 15 秒后确认）
docker-compose ps   # Status 显示 healthy 即可
```

### 第二步：配置环境变量

```bash
# 必填
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
export SPRING_PROFILES_ACTIVE=deepseek

# 安全（生产必填，至少 32 字符）
export JWT_SECRET=your-secret-key-at-least-32-characters-long

# 可选（不填则使用默认值 localhost）
export PG_HOST=localhost
export PG_PASSWORD=postgres
export REDIS_HOST=localhost
```

### 第三步：启动应用

```bash
# 确认 Java 版本（必须 21+）
java -version

# 启动（Flyway 首次运行自动建表，无需手动执行 SQL）
mvn spring-boot:run

# 或打包后运行
mvn clean package -q
java -jar target/ai-agent-1.0.0.jar
```

### 第四步：验证

```bash
# 健康检查
curl http://localhost:8080/actuator/health
# → {"status":"UP"}

# 注册账号
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin123!","email":"admin@example.com"}'

# 登录获取 Token
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin123!"}' | jq -r '.token')

# 发起对话
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"s001","message":"你好，介绍一下你能做什么"}'

# 打开内置 Web 界面（含登录页 + 对话界面 + 知识库管理）
open http://localhost:8080
```

---

## API 文档

所有需要认证的接口，请求 Header 携带：`Authorization: Bearer <token>`

### 认证接口（无需 Token）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/register` | 注册用户，返回 JWT Token |
| POST | `/api/v1/auth/login` | 登录，返回 JWT Token |

```bash
# 登录示例
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin123!"}'
# → {"token":"eyJ...","userId":"U001","username":"admin","roles":["USER"]}
```

---

### 对话接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/chat` | 同步对话（等待完整回复） |
| GET | `/api/v1/chat/stream` | 流式对话（SSE，字符逐步推送） |
| POST | `/api/v1/chat/react` | ReAct 多步推理（复杂任务自动拆解） |
| DELETE | `/api/v1/chat/memory/{sessionId}` | 清除会话记忆 |

**同步对话**：
```json
// POST /api/v1/chat
// 请求
{"sessionId": "s001", "message": "帮我查一下订单 #12345 的物流状态"}

// 响应
{"sessionId": "s001", "reply": "您的订单 #12345 已发货...", "durationMs": 1823}
```

**流式对话（前端接收）**：
```javascript
const es = new EventSource(
  `/api/v1/chat/stream?sessionId=s001&message=${encodeURIComponent(msg)}`,
  { headers: { 'Authorization': 'Bearer ' + token } }
);
es.onmessage = e => { if (e.data !== '[DONE]') output.textContent += e.data; };
es.addEventListener('done', () => es.close());
```

**ReAct 多步推理**（适合需要多次工具调用的复杂任务）：
```json
// POST /api/v1/chat/react
// 请求
{"sessionId": "s001", "message": "帮我查一下北京天气，以及用户 U001 最近有没有未完成的订单"}

// 响应包含中间步骤
{
  "answer": "北京今天晴天 26°C。用户 U001 有 2 笔待付款订单...",
  "steps": [
    {"tool": "getWeather", "input": "北京", "output": "晴天，26°C"},
    {"tool": "queryOrderSummary", "input": "U001", "output": "待付款 2 笔..."}
  ],
  "durationMs": 3240
}
```

---

### 知识库接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/kb` | 创建知识库 |
| GET | `/api/v1/kb` | 列出我的知识库 |
| DELETE | `/api/v1/kb/{kbId}` | 删除知识库（含所有文档） |
| POST | `/api/v1/kb/{kbId}/documents` | 上传文档（multipart/form-data） |
| GET | `/api/v1/kb/{kbId}/documents` | 列出已上传文档 |
| DELETE | `/api/v1/kb/{kbId}/documents/{docId}` | 删除指定文档 |
| POST | `/api/v1/kb/{kbId}/query` | 基于知识库问答 |
| GET | `/api/v1/kb/{kbId}/stats` | 统计信息（文档数/切片数/近期查询） |

**上传文档**：
```bash
curl -X POST http://localhost:8080/api/v1/kb/1/documents \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@员工手册.pdf"
# → {"docId":42,"status":"PROCESSING","message":"文档上传成功，正在处理中"}
```

**知识库问答**：
```bash
curl -X POST http://localhost:8080/api/v1/kb/1/query \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"s001","question":"年假政策是怎么规定的？"}'
```
```json
{
  "answer": "根据员工手册第三章，正式员工享有10天带薪年假...",
  "citations": [
    {
      "number": 1,
      "documentName": "员工手册.pdf",
      "pageNumber": 15,
      "excerpt": "正式员工享有10天带薪年假，工龄满5年后增加至15天..."
    }
  ],
  "answerFound": true,
  "confidence": 0.87,
  "stats": {
    "retrievalTimeMs": 320,
    "rerankingTimeMs": 180,
    "generationTimeMs": 1240
  }
}
```

---

### 管理接口（需要 ADMIN 角色）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/token-usage/my/today` | 查看自己今日费用 |
| GET | `/api/v1/admin/token-usage/today` | 今日全局总费用（USD） |
| GET | `/api/v1/admin/token-usage/report/model?days=7` | 近 N 天按模型成本报表 |
| GET | `/api/v1/admin/token-usage/report/user?days=7` | 近 N 天 Top 消费用户 |
| GET | `/api/v1/admin/token-usage/error-rate?minutes=5` | 近 N 分钟 LLM 错误率 |
| POST | `/api/v1/rag/eval/single` | 单条 RAG 质量评估 |
| POST | `/api/v1/rag/eval/batch` | 批量 RAG 质量评估 |
| GET | `/actuator/health` | 应用健康检查（公开） |
| GET | `/actuator/prometheus` | Prometheus 指标抓取（需 ADMIN） |

---

## 配置说明

### 核心配置项

```yaml
# application.yml

agent:
  memory:
    max-messages: 20       # 每会话保留最近 N 条消息（超出自动裁剪）
    ttl-hours: 24          # 记忆过期时间，从最后一次活跃时间起算

rag:
  retrieval:
    vector:
      top-k: 20            # 向量检索初始召回数
      threshold: 0.5       # 相似度阈值，低于此值丢弃
    rrf:
      top-k: 10            # RRF 融合后保留数量
  reranker:
    type: llm              # Reranker 类型：llm / tfidf / bge / cohere
    top-k: 5               # 最终送给 LLM 的上下文段落数
  elasticsearch:
    enabled: false         # 改为 true 启用 BM25 混合检索（需先启动 ES）

security:
  jwt:
    secret: ${JWT_SECRET}  # 签名密钥，生产必须设置，至少 32 字符
    expiration-seconds: 86400  # Token 有效期（秒），默认 24 小时
  rate-limit:
    per-minute: 10         # 每用户每分钟最大请求数
    per-day: 500           # 每用户每天最大请求数

kb:
  confidence-threshold: 0.6  # 知识库置信度阈值，低于此值返回"未找到"

# 生产告警阈值（application-prod.yml 中设置）
# error-rate-threshold: 0.03      （错误率 3%）
# p99-latency-threshold-ms: 20000 （P99 延迟 20s）
# daily-budget-usd: 50.0          （日预算 50 USD）
```

### 告警通知配置

```yaml
llm:
  observability:
    alert:
      dingtalk:
        enabled: ${ALERT_DINGTALK_ENABLED:false}
        webhook: ${ALERT_DINGTALK_WEBHOOK:}    # 钉钉机器人 Webhook URL
      wecom:
        enabled: ${ALERT_WECOM_ENABLED:false}
        webhook: ${ALERT_WECOM_WEBHOOK:}       # 企业微信机器人 Webhook URL
      custom:
        enabled: ${ALERT_CUSTOM_ENABLED:false}
        webhook: ${ALERT_CUSTOM_WEBHOOK:}      # 自定义 HTTP 回调
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

> **注意**：无论哪种对话模型，Embedding 统一使用 DeepSeek API（向量维度 1536）。切换模型无需重建知识库。

---

## 启用 BM25 混合检索

默认使用 Apache Lucene 做轻量 BM25，也可接入 Elasticsearch 获得更强的中文分词（IK 分词器）和分布式能力。

```bash
# 启动 Elasticsearch
docker-compose up -d elasticsearch

# 设置环境变量（重启应用生效）
export ES_ENABLED=true
export ES_HOST=localhost
```

启用后，RAG Pipeline 自动同时进行向量检索和 BM25 检索，通过 RRF 融合排序，Recall@5 约提升 30%。

---

## Reranker 选择

| 类型 | 配置值 | 说明 | 适用场景 |
|------|--------|------|---------|
| LLM | `type: llm` | 用对话模型打分，效果最好 | 质量优先，费用不敏感 |
| TF-IDF | `type: tfidf` | 本地计算，零成本 | 成本优先，快速验证 |
| BGE | `type: bge` | 本地 BGE 模型，效果好 | 质量与成本平衡 |
| Cohere | `type: cohere` | Cohere Rerank API | 无 GPU 但要好效果 |

### 部署 BGE Reranker（推荐生产使用）

```bash
pip install sentence-transformers fastapi uvicorn

# 创建 reranker_server.py
cat > reranker_server.py << 'EOF'
from fastapi import FastAPI
from sentence_transformers import CrossEncoder
from pydantic import BaseModel
from typing import List

app = FastAPI()
model = CrossEncoder("BAAI/bge-reranker-v2-m3")  # 支持中英文

class RerankRequest(BaseModel):
    query: str
    documents: List[str]

@app.post("/rerank")
def rerank(req: RerankRequest):
    pairs = [[req.query, doc] for doc in req.documents]
    return {"scores": model.predict(pairs).tolist()}
EOF

# 启动（约 2GB 内存）
uvicorn reranker_server:app --host 0.0.0.0 --port 8090
```

修改配置：

```yaml
rag:
  reranker:
    type: bge
    bge:
      url: http://localhost:8090/rerank
```

---

## 项目结构

```
ai-agent/
├── src/main/java/com/example/aiagent/
│   ├── AiAgentApplication.java
│   │
│   ├── agent/
│   │   ├── AgentFactory.java            # 组装 LLM + 记忆 + RAG + 工具（标准对话）
│   │   └── ReActAgent.java             # ReAct 多步推理 Agent
│   │
│   ├── config/
│   │   ├── AppConfig.java              # RestTemplate / 异步 / 定时
│   │   ├── LlmConfig.java             # 对话模型 Bean（按 Profile）
│   │   ├── RagConfig.java             # Embedding + PgVector
│   │   ├── ElasticsearchConfig.java   # ES 客户端（可选）
│   │   └── DeepSeekProperties.java    # DeepSeek 配置属性
│   │
│   ├── controller/
│   │   ├── ChatController.java            # POST /api/v1/chat
│   │   ├── StreamingChatController.java   # GET  /api/v1/chat/stream
│   │   └── ReActChatController.java       # POST /api/v1/chat/react
│   │
│   ├── kb/                              # 知识库模块
│   │   ├── controller/                  # CRUD + 问答接口
│   │   ├── entity/                      # KnowledgeBase / Document / Chunk / RetrievalLog
│   │   ├── mapper/                      # MyBatis Mapper 接口（4个）
│   │   └── service/                     # KnowledgeBaseService / KnowledgeBaseQueryService
│   │
│   ├── rag/                             # RAG 核心
│   │   ├── pipeline/HybridRagPipeline  # 5步混合检索流水线
│   │   ├── query/QueryRewriter         # HyDE + 多角度查询改写
│   │   ├── retrieval/                   # 向量检索 / BM25 / RRF 融合
│   │   ├── reranker/RerankerService    # LLM / TF-IDF / BGE / Cohere
│   │   ├── generation/                  # 带引用的答案生成
│   │   ├── evaluation/                  # RAG 评估（Faithfulness 等指标）
│   │   ├── model/                       # RetrievedChunk / RagResponse
│   │   └── DocumentIngestService       # 文档入库：解析 → 切片 → 向量化
│   │
│   ├── security/                        # 安全模块
│   │   ├── config/SecurityConfig       # Spring Security 配置
│   │   ├── controller/AuthController   # 登录 / 注册
│   │   ├── filter/                      # JWT 验证 / Prompt 注入检测 / 输出脱敏
│   │   ├── entity/SysUser.java         # 用户表
│   │   ├── mapper/SysUserMapper.java
│   │   └── service/                     # JWT / 限流 / 审计 / 用户详情
│   │
│   ├── observability/                   # 可观测性
│   │   ├── aop/LlmObservabilityAspect  # AOP 拦截所有 LLM 调用
│   │   ├── aop/TraceIdMdcFilter        # TraceId 注入日志
│   │   ├── metrics/LlmMetricsRecorder  # Prometheus 7 类指标
│   │   ├── alert/                       # 定时告警 + 多渠道通知
│   │   ├── controller/                  # Token 用量 / 成本报表接口
│   │   ├── entity/TokenUsageRecord     # Token 用量表
│   │   ├── mapper/TokenUsageMapper.java
│   │   ├── model/                       # LlmCallContext / TokenPricing
│   │   └── service/TokenUsageService   # 成本统计 / 报表
│   │
│   ├── tool/                            # Function Calling 工具
│   │   ├── BusinessTools.java          # 8个业务工具（订单/天气/账户/计算等）
│   │   ├── client/WeatherApiClient     # OpenWeatherMap API 调用
│   │   ├── entity/                      # Order / UserAccount / WeatherCache
│   │   └── mapper/                      # MyBatis Mapper（3个）
│   │
│   ├── memory/
│   │   └── RedisChatMemoryStore        # Redis 持久化对话记忆
│   │
│   └── exception/
│       └── GlobalExceptionHandler      # 全局异常处理
│
├── src/main/resources/
│   ├── application.yml                  # 主配置
│   ├── application-deepseek.yml        # DeepSeek 配置
│   ├── application-claude.yml          # Claude 配置
│   ├── application-prod.yml            # 生产覆盖（日志/连接池/告警阈值）
│   ├── mapper/*.xml                     # MyBatis XML（9个）
│   ├── db/migration/V1__init_schema.sql # Flyway 自动建表
│   ├── prompts/system-assistant.st     # 系统提示词模板
│   └── static/                          # 内置 Web 界面（login.html + index.html）
│
└── docker-compose.yml                   # PostgreSQL + Redis + Elasticsearch + App
```

---

## 数据库表结构

| 表名 | 说明 |
|------|------|
| `biz_user_account` | 用户表（含认证字段 password_hash / roles） |
| `kb_knowledge_base` | 知识库 |
| `kb_document` | 上传的文档（含处理状态、权限级别） |
| `kb_chunk` | 文档切片（向量化存储，含 content_hash 支持增量更新） |
| `kb_retrieval_log` | 检索日志（含各阶段耗时，用于 RAG 效果分析） |
| `llm_token_usage` | LLM 调用 Token 数和费用（含 TraceId、场景标签） |
| `biz_order` | 示例业务：订单（Function Calling 演示） |
| `biz_weather_cache` | 天气缓存（30分钟有效期） |

> Flyway 在首次启动时自动执行 `V1__init_schema.sql`，无需手动建表。

---

## 内置工具（Function Calling）

AI 在对话中会**自动决策**何时调用以下工具，无需用户显式触发：

| 工具 | 说明 |
|------|------|
| `queryOrderStatus` | 查询单个订单状态和物流信息 |
| `queryUserOrders` | 查询用户最近 10 条订单 |
| `queryOrderSummary` | 统计用户各状态订单数量 |
| `getWeather` | 查询城市天气（30分钟缓存 + API 三级降级） |
| `queryUserAccount` | 查询账户余额和会员等级 |
| `queryUserPoints` | 查询积分和升级进度 |
| `calculate` | 安全数学计算（Aviator 沙箱，禁止代码注入） |
| `getCurrentDateTime` | 获取当前日期时间（支持时区） |

> 这些工具是基于 `biz_order` / `biz_user_account` 等示例表的演示实现。接入你自己的业务系统时，在 `BusinessTools.java` 中替换对应方法的数据库查询逻辑即可，AI 的调用决策机制不需要修改。

---

## 生产部署

### 环境变量清单

```bash
# LLM（必填）
DEEPSEEK_API_KEY=sk-xxx                        # DeepSeek API Key（对话 + Embedding）
ANTHROPIC_API_KEY=sk-ant-xxx                   # Claude API Key（claude profile 时必填）

# 安全（必填）
JWT_SECRET=your-random-secret-at-least-32-chars

# 数据库
PG_HOST=your-db-host
PG_PORT=5432
PG_DB=aiagent
PG_USER=postgres
PG_PASSWORD=your-password

# Redis
REDIS_HOST=your-redis-host
REDIS_PASSWORD=your-redis-password

# Elasticsearch（可选）
ES_ENABLED=true
ES_HOST=your-es-host

# 告警通知（可选）
ALERT_DINGTALK_ENABLED=true
ALERT_DINGTALK_WEBHOOK=https://oapi.dingtalk.com/robot/send?access_token=xxx

# 天气 API（可选，留空则降级）
WEATHER_API_KEY=your-openweathermap-key
```

### 启动命令

```bash
# 激活生产配置（启用连接池优化 / JSON 日志 / 优雅停机）
export SPRING_PROFILES_ACTIVE=deepseek,prod

java -Xmx2g -jar ai-agent-1.0.0.jar
```

---

## 常见问题

**Q：启动报 `WeakKeyException`**

`JWT_SECRET` 长度不足 32 字符，生成一个随机密钥：
```bash
export JWT_SECRET=$(openssl rand -base64 32)
```

**Q：mvn compile 报语法错误（Text Block / record 等）**

项目需要 Java 21，检查当前版本并切换：
```bash
java -version   # 必须显示 21.x.x
export JAVA_HOME=/path/to/jdk21
```

**Q：知识库上传文档后查询不到**

确认 pgvector 扩展已安装（Flyway 脚本会自动执行，但需要 superuser 权限）：
```sql
-- 以 superuser 身份执行
CREATE EXTENSION IF NOT EXISTS vector;
```

**Q：切换到 Claude 后是否需要重建知识库？**

不需要。Embedding 统一使用 DeepSeek，与对话模型无关，切换不影响已有向量数据。

**Q：知识库场景和 Function Calling 工具如何选择？**

- **上传企业文档（制度/手册/FAQ）→ 用知识库（RAG）**，上传即可，无需写代码
- **查询实时数据库数据 / 做操作 → 用工具（Function Calling）**，需要在 `BusinessTools.java` 中实现对应方法
