# AI Agent

基于 Spring Boot 3.3 + LangChain4j 0.36 构建的企业级 AI Agent，支持多模型热切换、混合 RAG 知识库、多轮对话记忆、Function Calling、JWT 安全认证、Token 成本追踪等完整能力。

---

## 技术栈

| 层次 | 技术选型 |
|------|---------|
| 框架 | Spring Boot 3.3.5 · Java 21 |
| AI 框架 | LangChain4j 0.36.2 |
| 对话模型 | DeepSeek Chat（默认）· Claude Opus 4.8（可选） |
| Embedding | DeepSeek Embedding（统一，无需下载本地模型） |
| 向量数据库 | PostgreSQL + pgvector |
| ORM | MyBatis 3.0.3（SQL 完全可控） |
| 数据迁移 | Flyway（自动建表，启动即用） |
| 缓存 / 记忆 | Redis 7 |
| 全文检索 | Elasticsearch 8.11（可选，BM25 混合检索） |
| 安全 | Spring Security + JWT（JJWT 0.12.6）+ BCrypt |
| 可观测性 | Micrometer + Prometheus + Zipkin |
| 告警通知 | 钉钉 / 企微 / 邮件 / 自定义 Webhook |
| 表达式引擎 | Aviator 5.4.3（沙箱数学计算） |

---

## 核心功能

### 对话能力
- **同步 + SSE 流式**两种输出模式，支持实时打字效果
- **多轮对话记忆**，基于 Redis 持久化，服务重启不丢失，滑动 TTL 过期
- **Function Calling**，AI 自动决策何时调用业务工具（订单/天气/账户/计算）

### 知识库（企业文档问答）
- 支持上传 **PDF / Word / TXT** 等多种格式，上传即可问答，无需写代码
- **混合 RAG 5步流水线**：查询改写（HyDE）→ 向量检索 → BM25 检索 → RRF 融合 → Reranker 精排
- **引用溯源**：答案中自动标注来源文档和段落
- **置信度评估**：低置信度时明确告知"知识库中未找到相关信息"，而非胡编
- **RAG 效果评估**：内置 Faithfulness / AnswerRelevance 等指标，可量化优化效果

### 安全防护
- **JWT 无状态认证**，Token 有效期可配置，BCrypt 密码加密
- **Prompt 注入检测**：三层防护（规则 + 关键词 + 长度限制），覆盖中英文攻击模式
- **输出内容脱敏**：自动识别并脱敏手机号、身份证、银行卡、密码/密钥等
- **用户级限流**：每分钟 + 每日双维度，基于 Redis 令牌桶，超限返回 429
- **审计日志**：所有 AI 调用异步记录，满足合规要求

### 可观测性
- **Token 成本追踪**：每次 LLM 调用的 Token 数和费用实时写入 PostgreSQL
- **成本报表**：按用户、按模型、按天统计（REST 接口 + 原生 SQL，自由扩展）
- **Prometheus 指标**：调用次数、P99 延迟、错误率、当前并发数，7 类指标
- **链路追踪**：TraceId 全链路贯穿，日志自动携带，支持上报 Zipkin
- **智能告警**：错误率超阈值 / P99 延迟过高 / 日费用超预算，自动推送钉钉/企微

### 多模型支持
- **DeepSeek**（默认）和 **Claude** 通过 Spring Profile 一行切换，业务代码零修改
- Embedding 统一使用 DeepSeek API，无需下载本地模型，节省磁盘和启动时间

---

## 快速开始

### 前置要求

| 工具 | 版本要求 |
|------|---------|
| JDK | **21+**（必须，项目使用 Java 21 特性） |
| Maven | 3.8+ |
| Docker | 24+ |
| DeepSeek API Key | 在 [platform.deepseek.com](https://platform.deepseek.com) 免费注册申请 |

### 第一步：启动依赖服务

```bash
git clone <your-repo>
cd ai-agent

# 启动 PostgreSQL（含 pgvector）+ Redis
docker-compose up -d postgres redis

# 等待服务就绪（约 15 秒）
docker-compose ps   # Status 显示 healthy 即可
```

### 第二步：配置环境变量

```bash
# 必填
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
export SPRING_PROFILES_ACTIVE=deepseek

# 生产环境必填（JWT 签名密钥，至少 32 字符）
export JWT_SECRET=your-secret-key-at-least-32-characters-long

# 可选（不填则使用默认值）
export PG_HOST=localhost
export PG_PASSWORD=postgres
export REDIS_HOST=localhost
```

### 第三步：启动应用

```bash
# 确认使用 Java 21
java -version   # 应显示 openjdk version "21.x.x"

# 启动（Flyway 会自动建表，无需手动执行 SQL）
mvn spring-boot:run

# 或打包后启动
mvn clean package -q
java -jar target/ai-agent-1.0.0.jar
```

> 首次启动时 Flyway 自动执行 `V1__init_schema.sql`，创建全部 9 张数据表。

### 第四步：验证运行

```bash
# 1. 健康检查
curl http://localhost:8080/actuator/health
# 预期输出：{"status":"UP"}

# 2. 注册用户
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin123!","email":"admin@example.com"}'

# 3. 登录获取 Token
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin123!"}' | jq -r '.token')

# 4. 发起对话
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"s001","message":"你好，你能做什么？"}'

# 5. 打开内置 Web 界面
open http://localhost:8080
```

---

## API 文档

### 认证接口（无需 Token）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/register` | 注册用户 |
| POST | `/api/v1/auth/login` | 登录，返回 JWT Token |

```bash
# 登录示例
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin123!"}'
# 响应：{"token":"eyJ...","userId":"U001","username":"admin"}
```

后续所有请求 Header 带上：`Authorization: Bearer <token>`

---

### 对话接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/chat` | 同步对话（等待完整回复） |
| GET | `/api/v1/chat/stream` | 流式对话（SSE，字符逐步输出） |
| DELETE | `/api/v1/chat/memory/{sessionId}` | 清除会话记忆（开启新话题） |

**同步对话**：
```json
// 请求
{"sessionId": "s001", "message": "帮我查一下订单 #12345 的物流"}

// 响应
{"sessionId": "s001", "reply": "您的订单 #12345 已发货，快递单号...", "durationMs": 1823}
```

**流式对话（前端 EventSource）**：
```javascript
const es = new EventSource(
  `/api/v1/chat/stream?sessionId=s001&message=${encodeURIComponent(msg)}`,
  { headers: { 'Authorization': 'Bearer ' + token } }
);
es.onmessage = e => output.textContent += e.data;
es.addEventListener('done', () => es.close());
```

---

### 知识库接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/kb` | 创建知识库 |
| GET | `/api/v1/kb` | 列出我的知识库 |
| DELETE | `/api/v1/kb/{kbId}` | 删除知识库 |
| POST | `/api/v1/kb/{kbId}/documents` | 上传文档（PDF/Word/TXT） |
| GET | `/api/v1/kb/{kbId}/documents` | 列出已上传文档 |
| DELETE | `/api/v1/kb/{kbId}/documents/{docId}` | 删除文档 |
| POST | `/api/v1/kb/{kbId}/query` | 基于知识库问答 |
| GET | `/api/v1/kb/{kbId}/stats` | 知识库统计信息 |

**上传文档**：
```bash
curl -X POST http://localhost:8080/api/v1/kb/1/documents \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@员工手册.pdf"
# 响应：{"docId": 42, "status": "PROCESSING", "message": "文档上传成功，正在处理中"}
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
    {"number": 1, "documentName": "员工手册.pdf", "pageNumber": 15, "excerpt": "正式员工享有..."}
  ],
  "answerFound": true,
  "confidence": 0.87
}
```

---

### 管理接口（需要 ADMIN 角色）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/token-usage/today` | 今日全局总费用（USD） |
| GET | `/api/v1/admin/token-usage/report/model?days=7` | 近 N 天按模型成本报表 |
| GET | `/api/v1/admin/token-usage/report/user?days=7` | 近 N 天 Top 消费用户 |
| GET | `/api/v1/admin/token-usage/error-rate?minutes=5` | 近 N 分钟 LLM 错误率 |
| POST | `/api/v1/rag/eval/single` | 单条 RAG 评估（测试检索质量） |
| POST | `/api/v1/rag/eval/batch` | 批量 RAG 评估 |
| GET | `/actuator/prometheus` | Prometheus 指标抓取端点 |

---

## 配置说明

### 核心配置项（application.yml）

```yaml
agent:
  memory:
    max-messages: 20        # 每会话保留最近 N 条消息
    ttl-hours: 24           # 记忆 TTL（小时），最后活跃时间起算

rag:
  retrieval:
    vector:
      top-k: 20             # 向量检索初始召回数
      threshold: 0.5        # 相似度阈值，低于此值丢弃
    rrf:
      top-k: 10             # RRF 融合后保留数
  reranker:
    type: bge               # Reranker 类型：llm / bge / cohere
    top-k: 5                # 最终送给 LLM 的上下文段数
  elasticsearch:
    enabled: false          # 改为 true 启用 BM25 混合检索（需先启动 ES）

security:
  jwt:
    secret: ${JWT_SECRET}   # 签名密钥，生产必须设置，至少 32 字符
    expiration-seconds: 86400  # Token 有效期（秒）
  rate-limit:
    per-minute: 10          # 每用户每分钟最大请求数
    per-day: 500            # 每用户每天最大请求数

kb:
  confidence-threshold: 0.6  # 置信度阈值，低于此值返回"未找到"而非猜测
```

### 告警通知配置

```yaml
llm:
  observability:
    alert:
      dingtalk:
        enabled: ${ALERT_DINGTALK_ENABLED:false}
        webhook: ${ALERT_DINGTALK_WEBHOOK:}   # 钉钉机器人 Webhook
      wecom:
        enabled: ${ALERT_WECOM_ENABLED:false}
        webhook: ${ALERT_WECOM_WEBHOOK:}      # 企业微信机器人 Webhook
      custom:
        enabled: ${ALERT_CUSTOM_ENABLED:false}
        webhook: ${ALERT_CUSTOM_WEBHOOK:}     # 自定义 HTTP 回调
```

---

## 切换对话模型

### 使用 DeepSeek（默认）

```bash
export SPRING_PROFILES_ACTIVE=deepseek
export DEEPSEEK_API_KEY=sk-xxx
```

### 使用 Claude

```bash
export SPRING_PROFILES_ACTIVE=claude
export ANTHROPIC_API_KEY=sk-ant-xxx
export DEEPSEEK_API_KEY=sk-xxx   # Embedding 仍使用 DeepSeek，必须设置
```

> Embedding 统一使用 DeepSeek，不随对话模型切换。切换对话模型**不需要重建**知识库向量数据。

---

## 启用 BM25 混合检索

默认只用向量检索，启用 Elasticsearch 后自动开启混合检索，Recall@5 可提升约 30%：

```bash
# 启动 Elasticsearch
docker-compose up -d elasticsearch

# 设置环境变量，重启应用
export ES_ENABLED=true
export ES_HOST=localhost
```

---

## 启用 BGE Reranker（本地精排）

默认使用 LLM 做 Reranker。改用本地 BGE 模型可降低 Token 消耗：

**部署 Python 服务**（约 2GB 内存）：

```bash
pip install sentence-transformers fastapi uvicorn

cat > reranker_server.py << 'EOF'
from fastapi import FastAPI
from sentence_transformers import CrossEncoder
from pydantic import BaseModel
from typing import List

app = FastAPI()
model = CrossEncoder("BAAI/bge-reranker-v2-m3")   # 支持中英文

class RerankRequest(BaseModel):
    query: str
    documents: List[str]

@app.post("/rerank")
def rerank(req: RerankRequest):
    pairs = [[req.query, doc] for doc in req.documents]
    scores = model.predict(pairs).tolist()
    return {"scores": scores}
EOF

uvicorn reranker_server:app --host 0.0.0.0 --port 8090
```

**修改配置**：

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
│   ├── AiAgentApplication.java              # 启动类
│   ├── config/                              # 配置层（模型/向量库/ES/公共Bean）
│   ├── agent/AgentFactory.java             # Agent 组装（LLM+记忆+RAG+工具）
│   ├── controller/                          # 对话 API（同步+流式）
│   ├── memory/RedisChatMemoryStore.java    # Redis 持久化对话记忆
│   │
│   ├── kb/                                  # 知识库模块
│   │   ├── controller/                      # CRUD + 问答接口
│   │   ├── entity/                          # KnowledgeBase/Document/Chunk/RetrievalLog
│   │   ├── mapper/                          # MyBatis Mapper 接口
│   │   └── service/                         # 业务逻辑（管理+问答）
│   │
│   ├── rag/                                 # RAG 核心
│   │   ├── pipeline/HybridRagPipeline      # 5步混合检索流水线
│   │   ├── query/QueryRewriter             # HyDE 查询改写
│   │   ├── retrieval/                       # 向量检索 + BM25 + RRF 融合
│   │   ├── reranker/RerankerService        # 精排（LLM/BGE/Cohere）
│   │   ├── generation/                      # 带引用的答案生成
│   │   ├── evaluation/                      # RAG 效果评估
│   │   ├── model/                           # RetrievedChunk / RagResponse
│   │   └── DocumentIngestService.java      # 文档入库流水线
│   │
│   ├── security/                            # 安全模块
│   │   ├── config/SecurityConfig           # Spring Security 配置
│   │   ├── controller/AuthController       # 登录/注册
│   │   ├── filter/                          # JWT + Prompt注入检测 + 输出脱敏
│   │   ├── entity/SysUser.java             # 用户表
│   │   ├── mapper/SysUserMapper.java       # MyBatis Mapper
│   │   └── service/                         # JWT/限流/审计/用户详情
│   │
│   ├── observability/                       # 可观测性
│   │   ├── aop/LlmObservabilityAspect      # AOP 拦截所有 LLM 调用
│   │   ├── aop/TraceIdMdcFilter            # TraceId 注入日志
│   │   ├── metrics/LlmMetricsRecorder      # Prometheus 7类指标
│   │   ├── alert/                           # 定时告警 + 多渠道通知
│   │   ├── controller/TokenUsageController # 成本报表接口
│   │   ├── entity/TokenUsageRecord         # Token 用量表
│   │   ├── mapper/TokenUsageMapper         # MyBatis Mapper
│   │   └── service/TokenUsageService       # 成本统计/报表
│   │
│   └── tool/                                # Function Calling 工具
│       ├── BusinessTools.java              # 8个工具（订单/天气/账户/计算/时间等）
│       ├── client/WeatherApiClient         # 天气 API 客户端
│       ├── entity/                          # Order/UserAccount/WeatherCache
│       └── mapper/                          # MyBatis Mapper
│
├── src/main/resources/
│   ├── application.yml                      # 主配置
│   ├── application-deepseek.yml            # DeepSeek 模型配置
│   ├── application-claude.yml              # Claude 模型配置
│   ├── application-prod.yml                # 生产环境覆盖
│   ├── mapper/*.xml                         # MyBatis XML 映射（9个）
│   ├── db/migration/V1__init_schema.sql   # Flyway 自动建表脚本
│   ├── prompts/system-assistant.st         # 系统提示词模板
│   └── static/                              # 内置 Web 测试界面
│
└── docker-compose.yml                       # 一键启动：PostgreSQL + Redis + ES
```

---

## 数据库表结构

| 表名 | 说明 |
|------|------|
| `sys_user` | 系统用户（登录认证） |
| `kb_knowledge_base` | 知识库（支持多租户） |
| `kb_document` | 上传的文档 |
| `kb_chunk` | 文档切片（向量化存储） |
| `kb_retrieval_log` | 检索日志（用于效果分析） |
| `llm_token_usage` | LLM 调用 Token 用量和费用 |
| `biz_order` | 示例：订单表（Function Calling 演示） |
| `biz_user_account` | 示例：用户账户表 |
| `biz_weather_cache` | 天气缓存表 |

> Flyway 在首次启动时自动建表，无需手动执行 SQL。

---

## 生产部署

### 环境变量清单

```bash
# 必填
DEEPSEEK_API_KEY=sk-xxx               # DeepSeek API Key
JWT_SECRET=<32字符以上的随机字符串>    # JWT 签名密钥

# 数据库
PG_HOST=your-db-host
PG_PORT=5432
PG_DB=aiagent
PG_USER=postgres
PG_PASSWORD=your-password

# Redis
REDIS_HOST=your-redis-host
REDIS_PASSWORD=your-redis-password

# 可选：Claude 模型
ANTHROPIC_API_KEY=sk-ant-xxx

# 可选：告警通知
ALERT_DINGTALK_ENABLED=true
ALERT_DINGTALK_WEBHOOK=https://oapi.dingtalk.com/robot/send?access_token=xxx
```

### 启动命令

```bash
# 激活生产配置（禁用 SQL 日志、优化连接池、开启优雅停机）
export SPRING_PROFILES_ACTIVE=deepseek,prod

java -Xmx2g -jar ai-agent-1.0.0.jar
```

---

## 常见问题

**Q：启动报 `WeakKeyException`**

`JWT_SECRET` 不足 32 字符。设置一个更长的密钥：
```bash
export JWT_SECRET=$(openssl rand -base64 32)
```

**Q：mvn compile 报语法错误（Text Block / record 等）**

项目需要 Java 21，请确认编译器版本：
```bash
java -version   # 必须显示 21.x.x
# 如果是旧版本，设置 JAVA_HOME
export JAVA_HOME=/path/to/jdk21
```

**Q：知识库上传文档后查询不到内容**

确认 PostgreSQL 已安装 pgvector 扩展（Flyway 脚本会自动执行，但需要 superuser 权限）：
```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

**Q：切换到 Claude 后 Embedding 是否需要重建？**

不需要。Embedding 始终使用 DeepSeek，不随对话模型切换。
