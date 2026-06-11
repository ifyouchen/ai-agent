-- ============================================================
-- 企业级知识库问答系统，支持多租户、文档版本管理、检索日志分析
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================
-- 1. 知识库表
-- ============================================================
CREATE TABLE IF NOT EXISTS kb_knowledge_base (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    name            VARCHAR(256) NOT NULL,
    description     TEXT,
    embed_model     VARCHAR(128) NOT NULL DEFAULT 'all-minilm-l6-v2',
    chunk_config    JSONB        NOT NULL DEFAULT '{}',
    status          SMALLINT     NOT NULL DEFAULT 1,
    doc_count       INT          NOT NULL DEFAULT 0,
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, name)
);
CREATE INDEX IF NOT EXISTS idx_kb_tenant ON kb_knowledge_base(tenant_id);

-- ============================================================
-- 2. 文档表
-- ============================================================
CREATE TABLE IF NOT EXISTS kb_document (
    id               BIGSERIAL    PRIMARY KEY,
    kb_id            BIGINT       NOT NULL REFERENCES kb_knowledge_base(id),
    tenant_id        VARCHAR(64)  NOT NULL,
    name             VARCHAR(512) NOT NULL,
    doc_type         VARCHAR(32)  NOT NULL,
    file_path        VARCHAR(1024),
    file_size        BIGINT,
    file_hash        VARCHAR(64),
    parse_status     VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    parse_error      TEXT,
    chunk_count      INT          NOT NULL DEFAULT 0,
    permission_level SMALLINT     NOT NULL DEFAULT 0,
    allowed_roles    TEXT[],
    created_by       VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    indexed_at       TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_doc_kb_id  ON kb_document(kb_id);
CREATE INDEX IF NOT EXISTS idx_doc_tenant ON kb_document(tenant_id);
CREATE INDEX IF NOT EXISTS idx_doc_status ON kb_document(parse_status);
CREATE INDEX IF NOT EXISTS idx_doc_hash   ON kb_document(file_hash);

-- ============================================================
-- 3. 文档切片表
-- ============================================================
CREATE TABLE IF NOT EXISTS kb_chunk (
    id           BIGSERIAL    PRIMARY KEY,
    doc_id       BIGINT       NOT NULL REFERENCES kb_document(id) ON DELETE CASCADE,
    kb_id        BIGINT       NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    chunk_index  INT          NOT NULL,
    content      TEXT         NOT NULL,
    content_hash VARCHAR(64)  NOT NULL,
    metadata     JSONB        NOT NULL DEFAULT '{}',
    token_count  INT,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chunk_doc_id ON kb_chunk(doc_id);
CREATE INDEX IF NOT EXISTS idx_chunk_kb_id  ON kb_chunk(kb_id, is_active) WHERE is_active = TRUE;

-- ============================================================
-- 4. 检索日志表
-- ============================================================
CREATE TABLE IF NOT EXISTS kb_retrieval_log (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    kb_id           BIGINT,
    session_id      VARCHAR(64),
    user_id         VARCHAR(64),
    query           TEXT         NOT NULL,
    rewritten_query TEXT,
    top_chunks      JSONB,
    top_score       DECIMAL(6,4),
    answer_type     VARCHAR(32),
    retrieval_ms    INT,
    rerank_ms       INT,
    generate_ms     INT,
    total_ms        INT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_log_tenant_time ON kb_retrieval_log(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_log_kb_id       ON kb_retrieval_log(kb_id);

-- ============================================================
-- 5. Token 用量表
-- ============================================================
CREATE TABLE IF NOT EXISTS llm_token_usage (
    id             BIGSERIAL     PRIMARY KEY,
    trace_id       VARCHAR(64),
    session_id     VARCHAR(64),
    user_id        VARCHAR(64),
    model_name     VARCHAR(64)   NOT NULL,
    scenario       VARCHAR(32),
    input_tokens   INT           NOT NULL DEFAULT 0,
    output_tokens  INT           NOT NULL DEFAULT 0,
    total_tokens   INT           NOT NULL DEFAULT 0,
    cost_usd       DECIMAL(10,8) NOT NULL DEFAULT 0,
    duration_ms    BIGINT,
    success        BOOLEAN       NOT NULL DEFAULT TRUE,
    error_message  VARCHAR(512),
    input_snippet  VARCHAR(512),
    output_snippet VARCHAR(512),
    called_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_usage_user_id   ON llm_token_usage(user_id);
CREATE INDEX IF NOT EXISTS idx_usage_called_at ON llm_token_usage(called_at DESC);
CREATE INDEX IF NOT EXISTS idx_usage_model     ON llm_token_usage(model_name);

-- ============================================================
-- 6. 用户账户表
-- ============================================================
CREATE TABLE IF NOT EXISTS biz_user_account (
    id               BIGSERIAL     PRIMARY KEY,
    user_id          VARCHAR(64)   NOT NULL UNIQUE,
    username         VARCHAR(128)  NOT NULL UNIQUE,
    password_hash    VARCHAR(256),
    roles            VARCHAR(256)  NOT NULL DEFAULT 'ROLE_USER',
    enabled          SMALLINT      NOT NULL DEFAULT 1,
    balance          DECIMAL(12,2) NOT NULL DEFAULT 0,
    membership_level VARCHAR(32)   NOT NULL DEFAULT 'NORMAL',
    points           INT           NOT NULL DEFAULT 0,
    default_org_id   VARCHAR(64),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_biz_user_account_user_id  ON biz_user_account(user_id);
CREATE INDEX IF NOT EXISTS idx_biz_user_account_username ON biz_user_account(username);

-- ============================================================
-- 7. 订单表
-- ============================================================
CREATE TABLE IF NOT EXISTS biz_order (
    id               BIGSERIAL     PRIMARY KEY,
    order_no         VARCHAR(64)   NOT NULL UNIQUE,
    status           VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    amount           DECIMAL(12,2) NOT NULL DEFAULT 0,
    product_name     VARCHAR(256)  NOT NULL,
    shipping_no      VARCHAR(64),
    shipping_company VARCHAR(64),
    expected_arrival DATE,
    user_id          VARCHAR(64)   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_biz_order_no      ON biz_order(order_no);
CREATE INDEX IF NOT EXISTS idx_biz_order_user_id ON biz_order(user_id);

-- ============================================================
-- 8. 天气缓存表
-- ============================================================
CREATE TABLE IF NOT EXISTS biz_weather_cache (
    id           BIGSERIAL     PRIMARY KEY,
    city         VARCHAR(128)  NOT NULL UNIQUE,
    weather_desc VARCHAR(128),
    temperature  DECIMAL(5,2),
    humidity     INT,
    wind         DECIMAL(6,2),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_biz_weather_city ON biz_weather_cache(city);

-- ============================================================
-- 9. 组织表
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_organization (
    id              BIGSERIAL     PRIMARY KEY,
    org_id          VARCHAR(64)   NOT NULL UNIQUE,
    name            VARCHAR(256)  NOT NULL,
    org_type        VARCHAR(32)   NOT NULL DEFAULT 'PERSONAL',
    owner_id        VARCHAR(64)   NOT NULL,
    description     TEXT,
    settings        JSONB         NOT NULL DEFAULT '{}',
    status          SMALLINT     NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_org_owner_id ON sys_organization(owner_id);
CREATE INDEX IF NOT EXISTS idx_org_type     ON sys_organization(org_type);

-- ============================================================
-- 10. 组织成员表
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_org_member (
    id              BIGSERIAL     PRIMARY KEY,
    org_id          VARCHAR(64)   NOT NULL,
    user_id         VARCHAR(64)   NOT NULL,
    role            VARCHAR(32)   NOT NULL DEFAULT 'MEMBER',
    joined_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    UNIQUE (org_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_org_member_org_id  ON sys_org_member(org_id);
CREATE INDEX IF NOT EXISTS idx_org_member_user_id ON sys_org_member(user_id);

-- ============================================================
-- 11. 知识库成员授权表
-- ============================================================
CREATE TABLE IF NOT EXISTS kb_member (
    id              BIGSERIAL     PRIMARY KEY,
    kb_id           BIGINT        NOT NULL REFERENCES kb_knowledge_base(id) ON DELETE CASCADE,
    user_id         VARCHAR(64)   NOT NULL,
    role            VARCHAR(32)   NOT NULL DEFAULT 'VIEWER',
    granted_by      VARCHAR(64),
    granted_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    UNIQUE (kb_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_kb_member_kb_id   ON kb_member(kb_id);
CREATE INDEX IF NOT EXISTS idx_kb_member_user_id ON kb_member(user_id);