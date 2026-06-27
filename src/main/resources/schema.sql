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
CREATE INDEX IF NOT EXISTS idx_kb_tenant_created ON kb_knowledge_base(tenant_id, created_at DESC);

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
CREATE INDEX IF NOT EXISTS idx_doc_kb_created ON kb_document(kb_id, created_at DESC);
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
CREATE INDEX IF NOT EXISTS idx_log_tenant_kb_time ON kb_retrieval_log(tenant_id, kb_id, created_at DESC);

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
CREATE INDEX IF NOT EXISTS idx_usage_user_time  ON llm_token_usage(user_id, called_at DESC);
CREATE INDEX IF NOT EXISTS idx_usage_time_model ON llm_token_usage(called_at DESC, model_name);

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
-- 11. 组织邀请表
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_org_invitation (
    id              BIGSERIAL     PRIMARY KEY,
    org_id          VARCHAR(64)   NOT NULL,
    invited_email   VARCHAR(100)  NOT NULL,
    invited_user_id VARCHAR(64),
    inviter_id      VARCHAR(64)   NOT NULL,
    role            VARCHAR(32)   NOT NULL DEFAULT 'MEMBER',
    token           VARCHAR(64)   NOT NULL UNIQUE,
    status          VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    expires_at      TIMESTAMPTZ   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_org_invitation_org_id   ON sys_org_invitation(org_id);
CREATE INDEX IF NOT EXISTS idx_org_invitation_token    ON sys_org_invitation(token);
CREATE INDEX IF NOT EXISTS idx_org_invitation_email    ON sys_org_invitation(invited_email);
CREATE INDEX IF NOT EXISTS idx_org_invitation_status   ON sys_org_invitation(org_id, status);

-- ============================================================
-- 12. 组织加入申请表
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_org_join_request (
    id          BIGSERIAL     PRIMARY KEY,
    org_id      VARCHAR(64)   NOT NULL,
    user_id     VARCHAR(64)   NOT NULL,
    message     TEXT,
    status      VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    UNIQUE (org_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_org_join_request_org_id   ON sys_org_join_request(org_id);
CREATE INDEX IF NOT EXISTS idx_org_join_request_user_id  ON sys_org_join_request(user_id);
CREATE INDEX IF NOT EXISTS idx_org_join_request_status   ON sys_org_join_request(org_id, status);

-- ============================================================
-- Billing：AI 预付费钱包、充值订单、用量冻结
-- ============================================================
CREATE TABLE IF NOT EXISTS billing_wallet (
    id                      BIGSERIAL      PRIMARY KEY,
    user_id                 VARCHAR(64)    NOT NULL UNIQUE,
    available_balance_cny   DECIMAL(18,6)  NOT NULL DEFAULT 0,
    frozen_balance_cny      DECIMAL(18,6)  NOT NULL DEFAULT 0,
    total_recharged_cny     DECIMAL(18,6)  NOT NULL DEFAULT 0,
    total_consumed_cny      DECIMAL(18,6)  NOT NULL DEFAULT 0,
    status                  VARCHAR(32)    NOT NULL DEFAULT 'ACTIVE',
    version                 BIGINT         NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_billing_wallet_user_id ON billing_wallet(user_id);

CREATE TABLE IF NOT EXISTS billing_ledger (
    id                  BIGSERIAL      PRIMARY KEY,
    ledger_no           VARCHAR(64)    NOT NULL UNIQUE,
    user_id             VARCHAR(64)    NOT NULL,
    type                VARCHAR(32)    NOT NULL,
    amount_cny          DECIMAL(18,6)  NOT NULL,
    balance_after_cny   DECIMAL(18,6)  NOT NULL,
    ref_type            VARCHAR(32),
    ref_id              VARCHAR(128),
    idempotency_key     VARCHAR(160)   NOT NULL UNIQUE,
    remark              VARCHAR(512),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_billing_ledger_user_time ON billing_ledger(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_billing_ledger_ref ON billing_ledger(ref_type, ref_id);

CREATE TABLE IF NOT EXISTS recharge_order (
    id                  BIGSERIAL      PRIMARY KEY,
    order_no            VARCHAR(64)    NOT NULL UNIQUE,
    user_id             VARCHAR(64)    NOT NULL,
    package_code        VARCHAR(32)    NOT NULL,
    amount_cents        BIGINT         NOT NULL,
    pay_channel         VARCHAR(32)    NOT NULL,
    status              VARCHAR(32)    NOT NULL DEFAULT 'CREATED',
    provider_trade_no   VARCHAR(128),
    pay_qr_content      TEXT,
    paid_at             TIMESTAMPTZ,
    expire_at           TIMESTAMPTZ    NOT NULL,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_recharge_order_user_time ON recharge_order(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_recharge_order_status_expire ON recharge_order(status, expire_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_recharge_order_provider_trade
    ON recharge_order(pay_channel, provider_trade_no)
    WHERE provider_trade_no IS NOT NULL;

CREATE TABLE IF NOT EXISTS usage_reservation (
    id                  BIGSERIAL      PRIMARY KEY,
    reservation_no      VARCHAR(64)    NOT NULL UNIQUE,
    trace_id            VARCHAR(64),
    session_id          VARCHAR(64),
    user_id             VARCHAR(64)    NOT NULL,
    model_name          VARCHAR(128)   NOT NULL,
    input_tokens_est    INT            NOT NULL DEFAULT 0,
    output_tokens_est   INT            NOT NULL DEFAULT 0,
    reserved_cny        DECIMAL(18,6)  NOT NULL,
    actual_cny          DECIMAL(18,6),
    status              VARCHAR(32)    NOT NULL DEFAULT 'RESERVED',
    expires_at          TIMESTAMPTZ    NOT NULL,
    settled_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_usage_reservation_user_time ON usage_reservation(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_usage_reservation_status_expire ON usage_reservation(status, expires_at);

-- ============================================================
-- 13. 知识库成员授权表
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

-- ============================================================
-- 14. 聊天会话表（对话历史持久化）
-- ============================================================
CREATE TABLE IF NOT EXISTS chat_session (
    id          BIGSERIAL    PRIMARY KEY,
    session_id  VARCHAR(128) NOT NULL UNIQUE,
    user_id     VARCHAR(64)  NOT NULL,
    title       VARCHAR(256) NOT NULL DEFAULT '新对话',
    kb_id       BIGINT,                           -- 关联知识库（可选）
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chat_session_user_id    ON chat_session(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_session_session_id ON chat_session(session_id);

-- ============================================================
-- 15. 聊天消息表（对话历史持久化）
-- ============================================================
CREATE TABLE IF NOT EXISTS chat_message (
    id          BIGSERIAL    PRIMARY KEY,
    session_id  VARCHAR(128) NOT NULL,
    user_id     VARCHAR(64)  NOT NULL,
    role        VARCHAR(16)  NOT NULL,             -- 'user' | 'ai'
    content     TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chat_message_session_id ON chat_message(session_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_chat_message_user_id    ON chat_message(user_id);



-- ============================================================
-- 用户 Profile 扩展字段（nickname / email）
-- continue-on-error: true 保证幂等，列已存在时跳过
-- ============================================================
ALTER TABLE biz_user_account ADD COLUMN IF NOT EXISTS nickname VARCHAR(50);
ALTER TABLE biz_user_account ADD COLUMN IF NOT EXISTS email    VARCHAR(100);
CREATE UNIQUE INDEX IF NOT EXISTS idx_biz_user_account_email_unique
    ON biz_user_account (LOWER(email))
    WHERE email IS NOT NULL AND email <> '';

-- ============================================================
-- 10. 小说创作域：作品项目
-- ============================================================
CREATE TABLE IF NOT EXISTS story_project (
    id                 BIGSERIAL    PRIMARY KEY,
    tenant_id          VARCHAR(64)  NOT NULL DEFAULT 'default',
    title              VARCHAR(256) NOT NULL,
    type               VARCHAR(32)  NOT NULL,
    status             VARCHAR(32)  NOT NULL DEFAULT 'writing',
    description        TEXT,
    linked_kb_id       BIGINT,
    metadata           JSONB        NOT NULL DEFAULT '{}',
    created_by         VARCHAR(64),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_story_project_tenant ON story_project(tenant_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_story_project_type ON story_project(type);

-- ============================================================
-- 11. 小说创作域：章节
-- ============================================================
CREATE TABLE IF NOT EXISTS story_chapter (
    id                 BIGSERIAL    PRIMARY KEY,
    project_id         BIGINT       NOT NULL REFERENCES story_project(id) ON DELETE CASCADE,
    title              VARCHAR(256) NOT NULL,
    chapter_no         INT          NOT NULL,
    content            TEXT         NOT NULL DEFAULT '',
    word_count         INT          NOT NULL DEFAULT 0,
    version_no         INT          NOT NULL DEFAULT 1,
    status             VARCHAR(32)  NOT NULL DEFAULT 'draft',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, chapter_no)
);
CREATE INDEX IF NOT EXISTS idx_story_chapter_project ON story_chapter(project_id, chapter_no);

CREATE TABLE IF NOT EXISTS story_chapter_version (
    id                 BIGSERIAL PRIMARY KEY,
    chapter_id         BIGINT       NOT NULL REFERENCES story_chapter(id) ON DELETE CASCADE,
    project_id         BIGINT       NOT NULL REFERENCES story_project(id) ON DELETE CASCADE,
    title              VARCHAR(255) NOT NULL,
    content            TEXT         NOT NULL DEFAULT '',
    word_count         INT          NOT NULL DEFAULT 0,
    version_no         INT          NOT NULL,
    source             VARCHAR(64)  NOT NULL DEFAULT 'manual',
    note               TEXT         NOT NULL DEFAULT '',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_story_chapter_version_chapter ON story_chapter_version(chapter_id, version_no DESC);

-- ============================================================
-- 12. 小说创作域：改写任务
-- ============================================================
CREATE TABLE IF NOT EXISTS story_rewrite_task (
    id                 BIGSERIAL    PRIMARY KEY,
    project_id         BIGINT       NOT NULL REFERENCES story_project(id) ON DELETE CASCADE,
    chapter_id         BIGINT       REFERENCES story_chapter(id) ON DELETE SET NULL,
    source_type        VARCHAR(32)  NOT NULL DEFAULT 'chapter',
    source_text        TEXT         NOT NULL DEFAULT '',
    rewrite_mode       VARCHAR(64)  NOT NULL,
    instruction        TEXT,
    status             VARCHAR(32)  NOT NULL DEFAULT 'pending',
    segments_json      JSONB        NOT NULL DEFAULT '[]',
    result_text        TEXT,
    diff_payload       JSONB        NOT NULL DEFAULT '{}',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at       TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_story_rewrite_project ON story_rewrite_task(project_id, created_at DESC);

-- ============================================================
-- 13. 小说创作域：短剧草稿、分集、场次
-- ============================================================
CREATE TABLE IF NOT EXISTS story_script_draft (
    id                 BIGSERIAL    PRIMARY KEY,
    project_id         BIGINT       NOT NULL REFERENCES story_project(id) ON DELETE CASCADE,
    title              VARCHAR(256) NOT NULL,
    source_ref         TEXT,
    episode_count      INT          NOT NULL DEFAULT 0,
    status             VARCHAR(32)  NOT NULL DEFAULT 'draft',
    quality_score      INT          NOT NULL DEFAULT 0,
    adaptation_plan    JSONB        NOT NULL DEFAULT '{}',
    quality_report     JSONB        NOT NULL DEFAULT '{}',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_story_script_draft_project ON story_script_draft(project_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS story_script_episode (
    id                 BIGSERIAL    PRIMARY KEY,
    draft_id           BIGINT       NOT NULL REFERENCES story_script_draft(id) ON DELETE CASCADE,
    episode_no         INT          NOT NULL,
    title              VARCHAR(256) NOT NULL,
    estimated_duration VARCHAR(64),
    core_hook          TEXT,
    main_conflict      TEXT,
    ending_hook        TEXT,
    summary            TEXT,
    UNIQUE (draft_id, episode_no)
);
CREATE INDEX IF NOT EXISTS idx_story_script_episode_draft ON story_script_episode(draft_id, episode_no);

CREATE TABLE IF NOT EXISTS story_script_scene (
    id                      BIGSERIAL    PRIMARY KEY,
    episode_id              BIGINT       NOT NULL REFERENCES story_script_episode(id) ON DELETE CASCADE,
    scene_no                INT          NOT NULL,
    scene_title             VARCHAR(256),
    location                VARCHAR(256),
    time_of_day             VARCHAR(64),
    characters              TEXT,
    scene_function          TEXT,
    estimated_duration      VARCHAR(64),
    visual_action           TEXT,
    narration               TEXT,
    dialogue                TEXT,
    performance_camera_note TEXT,
    hook                    TEXT,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (episode_id, scene_no)
);
CREATE INDEX IF NOT EXISTS idx_story_script_scene_episode ON story_script_scene(episode_id, scene_no);

-- ============================================================
-- 14. 小说创作域：长任务
-- ============================================================
CREATE TABLE IF NOT EXISTS story_generation_task (
    id                 BIGSERIAL    PRIMARY KEY,
    task_type          VARCHAR(64)  NOT NULL,
    project_id         BIGINT       REFERENCES story_project(id) ON DELETE CASCADE,
    status             VARCHAR(32)  NOT NULL DEFAULT 'pending',
    progress           INT          NOT NULL DEFAULT 0,
    current_step       VARCHAR(256),
    error_message      TEXT,
    token_usage        JSONB        NOT NULL DEFAULT '{}',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_story_generation_project ON story_generation_task(project_id, created_at DESC);

-- ============================================================
-- 注册邮箱验证码表
-- ============================================================
CREATE TABLE IF NOT EXISTS email_verification_code (
    id         BIGSERIAL    PRIMARY KEY,
    email      VARCHAR(100) NOT NULL,
    purpose    VARCHAR(32)  NOT NULL,
    code_hash  VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    used_at    TIMESTAMPTZ,
    attempts   INT          NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_email_code_email_purpose
    ON email_verification_code(email, purpose, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_email_code_expires_at
    ON email_verification_code(expires_at);

-- 会话标题检索加速
CREATE INDEX IF NOT EXISTS idx_chat_session_title ON chat_session USING gin(to_tsvector('simple', title));

-- 消息反馈字段（'up' | 'down' | null，前端点赞/点踩后持久化）
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS feedback VARCHAR(10);

-- ============================================================
-- 14. 会话分享快照表（公开只读快照，不包含知识库/租户/内部消息 ID）
-- ============================================================
CREATE TABLE IF NOT EXISTS chat_share (
    id            BIGSERIAL    PRIMARY KEY,
    share_id      VARCHAR(128) NOT NULL UNIQUE,
    session_id    VARCHAR(128) NOT NULL,
    user_id       VARCHAR(64)  NOT NULL,
    title         VARCHAR(256) NOT NULL DEFAULT '对话分享',
    snapshot_json TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ  NOT NULL,
    revoked_at    TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_chat_share_share_id   ON chat_share(share_id);
CREATE INDEX IF NOT EXISTS idx_chat_share_owner      ON chat_share(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_share_session_id ON chat_share(session_id);

-- ============================================================
-- 15. 用户长期记忆表（跨会话提取的事实/偏好，注入 system prompt 个性化）
-- ============================================================
CREATE TABLE IF NOT EXISTS user_memory (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL UNIQUE,
    facts_text  TEXT         NOT NULL DEFAULT '',
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_user_memory_user_id ON user_memory(user_id);
