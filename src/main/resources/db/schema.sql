-- ============================================================
-- 企业级知识库问答系统 - 数据库表设计
-- 支持多租户、文档版本管理、检索日志分析
-- ============================================================

-- 启用 pgvector 扩展（PostgreSQL）
-- CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================
-- 1. 知识库表
-- ============================================================
CREATE TABLE kb_knowledge_base (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,               -- 租户 ID（多租户隔离核心字段）
    name            VARCHAR(256) NOT NULL,
    description     TEXT,
    embed_model     VARCHAR(128) NOT NULL DEFAULT 'all-minilm-l6-v2',
    -- 切片配置（JSON）：{"chunk_size":500,"chunk_overlap":50}
    chunk_config    JSONB        NOT NULL DEFAULT '{}',
    status          SMALLINT     NOT NULL DEFAULT 1,      -- 1=正常 0=已归档
    doc_count       INT          NOT NULL DEFAULT 0,
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, name)
);

CREATE INDEX idx_kb_tenant ON kb_knowledge_base(tenant_id);

-- ============================================================
-- 2. 文档表（支持版本控制和增量更新）
-- ============================================================
CREATE TABLE kb_document (
    id              BIGSERIAL PRIMARY KEY,
    kb_id           BIGINT       NOT NULL REFERENCES kb_knowledge_base(id),
    tenant_id       VARCHAR(64)  NOT NULL,
    name            VARCHAR(512) NOT NULL,               -- 文件显示名称
    doc_type        VARCHAR(32)  NOT NULL,               -- PDF|WORD|EXCEL|HTML|TXT
    file_path       VARCHAR(1024),                       -- 存储路径（MinIO/OSS）
    file_size       BIGINT,
    file_hash       VARCHAR(64),                         -- MD5，用于检测文件变化（增量更新）
    -- 处理状态
    parse_status    VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    -- PENDING|PARSING|CHUNKING|EMBEDDING|DONE|FAILED
    parse_error     TEXT,
    chunk_count     INT          NOT NULL DEFAULT 0,
    -- 权限控制
    permission_level SMALLINT   NOT NULL DEFAULT 0,      -- 0=公开 1=内部 2=保密
    allowed_roles   TEXT[],                              -- 允许访问的角色列表
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    indexed_at      TIMESTAMPTZ                          -- 最后完成索引的时间
);

CREATE INDEX idx_doc_kb_id     ON kb_document(kb_id);
CREATE INDEX idx_doc_tenant    ON kb_document(tenant_id);
CREATE INDEX idx_doc_status    ON kb_document(parse_status);
CREATE INDEX idx_doc_hash      ON kb_document(file_hash);

-- ============================================================
-- 3. 文档切片表（核心向量存储）
-- ============================================================
CREATE TABLE kb_chunk (
    id              BIGSERIAL PRIMARY KEY,
    doc_id          BIGINT       NOT NULL REFERENCES kb_document(id) ON DELETE CASCADE,
    kb_id           BIGINT       NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    chunk_index     INT          NOT NULL,               -- 在文档中的顺序
    content         TEXT         NOT NULL,               -- 切片文本内容
    content_hash    VARCHAR(64)  NOT NULL,               -- 内容 Hash（增量更新用）
    -- 元数据（页码、章节等）
    metadata        JSONB        NOT NULL DEFAULT '{}',
    token_count     INT,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chunk_doc_id  ON kb_chunk(doc_id);
CREATE INDEX idx_chunk_kb_id   ON kb_chunk(kb_id, is_active) WHERE is_active = TRUE;

-- ============================================================
-- 4. 检索日志表（用于分析和 RAG 效果评估）
-- ============================================================
CREATE TABLE kb_retrieval_log (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    kb_id           BIGINT,
    session_id      VARCHAR(64),
    user_id         VARCHAR(64),
    query           TEXT         NOT NULL,               -- 用户原始问题
    rewritten_query TEXT,                                -- 改写后的查询
    -- 检索结果摘要（JSON：top 3 的 chunk_id 和得分）
    top_chunks      JSONB,
    top_score       DECIMAL(6,4),                       -- 最高相似度
    answer_type     VARCHAR(32),                         -- ANSWERED|NO_ANSWER|PARTIAL
    -- 性能数据
    retrieval_ms    INT,
    rerank_ms       INT,
    generate_ms     INT,
    total_ms        INT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_log_tenant_time ON kb_retrieval_log(tenant_id, created_at DESC);
CREATE INDEX idx_log_kb_id       ON kb_retrieval_log(kb_id);

-- ============================================================
-- Token 用量表（可观测性）
-- ============================================================
CREATE TABLE llm_token_usage (
    id              BIGSERIAL PRIMARY KEY,
    trace_id        VARCHAR(64),
    session_id      VARCHAR(64),
    user_id         VARCHAR(64),
    model_name      VARCHAR(64)  NOT NULL,
    scenario        VARCHAR(32),
    input_tokens    INT          NOT NULL DEFAULT 0,
    output_tokens   INT          NOT NULL DEFAULT 0,
    total_tokens    INT          NOT NULL DEFAULT 0,
    cost_usd        DECIMAL(10,8) NOT NULL DEFAULT 0,
    duration_ms     BIGINT,
    success         BOOLEAN      NOT NULL DEFAULT TRUE,
    error_message   VARCHAR(512),
    input_snippet   VARCHAR(512),
    output_snippet  VARCHAR(512),
    called_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_usage_user_id   ON llm_token_usage(user_id);
CREATE INDEX idx_usage_called_at ON llm_token_usage(called_at DESC);
CREATE INDEX idx_usage_model     ON llm_token_usage(model_name);

-- ============================================================
-- 业务工具表（BusinessTools 真实数据支撑）
-- ============================================================

-- 订单表
CREATE TABLE IF NOT EXISTS biz_order (
    id                BIGSERIAL    PRIMARY KEY,
    order_no          VARCHAR(64)  NOT NULL UNIQUE,         -- 订单编号，如 #12345
    status            VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    -- PENDING|PAID|SHIPPED|DELIVERED|CANCELLED|REFUNDED
    amount            DECIMAL(12,2) NOT NULL DEFAULT 0,     -- 订单金额
    product_name      VARCHAR(256) NOT NULL,                -- 商品名称
    shipping_no       VARCHAR(64),                          -- 快递单号
    shipping_company  VARCHAR(64),                          -- 快递公司
    expected_arrival  DATE,                                 -- 预计到达日期
    user_id           VARCHAR(64)  NOT NULL,                -- 下单用户 ID
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_biz_order_no      ON biz_order(order_no);
CREATE INDEX idx_biz_order_user_id ON biz_order(user_id);

-- 天气缓存表
CREATE TABLE IF NOT EXISTS biz_weather_cache (
    id           BIGSERIAL    PRIMARY KEY,
    city         VARCHAR(128) NOT NULL UNIQUE,              -- 城市名称
    weather_desc VARCHAR(128),                              -- 天气描述，如：晴天
    temperature  DECIMAL(5,2),                              -- 温度（°C）
    humidity     INT,                                       -- 湿度（%）
    wind         DECIMAL(6,2),                              -- 风速（m/s）
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()        -- 缓存时间
);

CREATE INDEX idx_biz_weather_city ON biz_weather_cache(city);

-- 用户账户表
CREATE TABLE IF NOT EXISTS biz_user_account (
    id               BIGSERIAL    PRIMARY KEY,
    user_id          VARCHAR(64)  NOT NULL UNIQUE,          -- 用户 ID
    username         VARCHAR(128) NOT NULL,                 -- 用户名
    balance          DECIMAL(12,2) NOT NULL DEFAULT 0,      -- 账户余额
    membership_level VARCHAR(32)  NOT NULL DEFAULT 'NORMAL',
    -- NORMAL|SILVER|GOLD|PLATINUM|DIAMOND
    points           INT          NOT NULL DEFAULT 0,       -- 积分
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_biz_user_account_user_id ON biz_user_account(user_id);
