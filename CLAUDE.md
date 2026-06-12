# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

### Backend (Spring Boot, Java 21)
```bash
# Start infrastructure (PostgreSQL + Redis, Flyway auto-creates all 8 tables on first run)
docker-compose up -d postgres redis

# Run backend (set required env vars first)
export DEEPSEEK_API_KEY=sk-xxx
export SPRING_PROFILES_ACTIVE=deepseek
export JWT_SECRET=$(openssl rand -base64 32)
mvn spring-boot:run

# Build JAR
mvn clean package -DskipTests

# Production startup
export SPRING_PROFILES_ACTIVE=deepseek,prod
java -Xmx2g -jar target/ai-agent-1.0.0.jar
```

### Frontend (Node.js 20+, Vite)
```bash
cd frontend
npm install
npm run dev        # Dev server at http://localhost:5173 (proxies /api → :8080)
npm run build      # Build to frontend/dist/
```

### Testing
```bash
# All tests
mvn test

# Single test class
mvn test -Dtest=HybridRagPipelineTest

# Integration tests (require Docker for Testcontainers)
mvn test -Dtest=DocumentIngestAndRetrievalIntegrationTest
mvn test -Dtest=KnowledgeBaseServiceIntegrationTest
```

### Optional services
```bash
# Enable Elasticsearch BM25 (optional, improves Chinese full-text search)
docker-compose --profile bm25 up -d elasticsearch
export ES_ENABLED=true
```

## Architecture Overview

This is a **Spring Boot 3.3.5 + LangChain4j 0.36.2** enterprise AI agent with a Vue 3 + Vite frontend.

### Request Flow

```
HTTP Request
  → TraceIdMdcFilter (inject TraceId into MDC)
  → PromptInjectionFilter (7 attack-pattern detection)
  → JwtAuthFilter (parse Bearer token or ?token= param)
  → RateLimitService (per-minute + per-day Redis token bucket)
  → Controller
  → ChatAssistant / StreamingChatAssistant (LangChain4j AiServices)
       ├─ RedisChatMemoryStore (sliding-TTL multi-turn memory)
       ├─ HybridRagContentRetriever (RAG retrieval, see below)
       └─ BusinessTools (@Tool annotated methods, function calling)
  → LlmObservabilityAspect (AOP around ChatLanguageModel.generate())
       ├─ TokenUsageService (persist cost to PostgreSQL)
       └─ LlmMetricsRecorder (7 Prometheus metrics)
  → OutputContentFilter (redact phone/ID/bankcard/password in response)
```

### Three Chat Controllers
- `ChatController` → `POST /api/v1/chat` — synchronous, returns full reply
- `StreamingChatController` → `GET /api/v1/chat/stream` — SSE, token-by-token push
- `ReActChatController` → `POST /api/v1/chat/react` — ReActAgent (max 8 iterations), returns steps

### Hybrid RAG Pipeline (5 steps)
`HybridRagPipeline` (used by `KnowledgeBaseQueryService`) and `HybridRagContentRetriever` (used by the chat agent) both execute:
1. **QueryRewriter** — HyDE (hypothetical document expansion) + optional multi-perspective rewrite
2. **Vector search** — PgVector via LangChain4j `EmbeddingStore` with `tenantId+kbId` metadata filter
3. **BM25 search** — embedded Apache Lucene (`Bm25Retriever`), optionally dual-path with Elasticsearch
4. **RRF fusion** — `RrfFusionRanker` merges both result lists
5. **Reranker** — `RerankerService` dispatches to one of: `llm` / `tfidf` / `bge` / `cohere` (configured by `rag.reranker.type`)

For the chat agent, step 5 produces `TextSegment`s injected into the LLM context. For standalone KB queries, a 6th step runs `CitationAwareGenerator` to produce a `RagResponse` with inline citations.

### Multi-tenant Isolation
Knowledge bases are isolated by `tenantId` (linked to `Organization`) and `kbId`. All vector searches and BM25 retrievals pass these as metadata filters. The `KbMemberService` controls who can access cross-org knowledge bases.

### LLM Profiles
Switch the active model via `SPRING_PROFILES_ACTIVE`:
- `deepseek` — DeepSeek Chat via OpenAI-compatible API (`LlmConfig` + `application-deepseek.yml`)
- `claude` — Anthropic Claude via `langchain4j-anthropic` (`application-claude.yml`)
- Embedding always uses DeepSeek/Baidu Qianfan (`bge-large-zh`, 1024-dim) regardless of chat model profile

### AgentFactory (`agent/AgentFactory.java`)
The central wiring point. Assembles `ChatAssistant` and `StreamingChatAssistant` beans using `AiServices.builder()`, attaching:
- `chatLanguageModel` or `streamingChatLanguageModel` (profile-selected)
- `RedisChatMemoryStore` via `chatMemoryProvider` (keyed by `sessionId`)
- `HybridRagContentRetriever` as `contentRetriever`
- `BusinessTools` for function calling

### Data Layer
- **MyBatis** with XML mappers in `src/main/resources/mapper/`
- **Flyway** auto-migrates on startup; initial schema in `src/main/resources/schema.sql` (8 tables)
- **PgVector** for embeddings (1024-dim via pgvector extension, auto-created by Flyway)
- **Redis** for chat memory (`RedisChatMemoryStore`) and rate limiting

### Key Configuration
- `application.yml` — base config; `application-deepseek.yml` / `application-claude.yml` — model profiles; `application-prod.yml` — production overrides
- `JWT_SECRET` env var must be ≥32 chars; startup validates this and throws `WeakKeyException` if too short
- Reranker type: `rag.reranker.type` (`tfidf` is default for low latency; `llm` for best quality)
- `rag.query.rewrite.variants: 0` skips multi-perspective LLM rewrite (HyDE alone is usually sufficient)

### Adding a New Tool (Function Calling)
Add a `@Tool`-annotated method to `BusinessTools.java`. The agent automatically discovers it at startup via `AiServices` reflection — no registration needed.

### Observability
- `LlmObservabilityAspect` intercepts `ChatLanguageModel.generate()` via AOP — zero changes to business code needed
- `TokenPricing` enum maps model names to USD per-token rates
- Alert thresholds and notification channels (DingTalk, WeCom, email, webhook) are in `application.yml` under `llm.observability.alert`
- Prometheus metrics exposed at `/actuator/prometheus`
