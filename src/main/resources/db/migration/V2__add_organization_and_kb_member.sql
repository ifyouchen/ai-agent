-- ============================================================
-- V2: 企业级多租户 —— 组织 + 知识库成员授权
-- ============================================================
-- 解决问题：
--   当前 tenantId = userId，每个用户独立隔离，企业内无法共享知识库。
--   引入"组织"概念后：
--   - 个人用户：注册时自动创建"个人组织"（tenantId = 个人组织ID），行为不变
--   - 企业用户：管理员创建"企业组织"，邀请员工加入，共享知识库
--   - 知识库访问通过 kb_member 表细粒度控制（OWNER/EDITOR/VIEWER）
-- ============================================================

-- ============================================================
-- 1. 组织表
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_organization (
    id              BIGSERIAL     PRIMARY KEY,
    org_id          VARCHAR(64)   NOT NULL UNIQUE,         -- 组织唯一标识（UUID）
    name            VARCHAR(256)  NOT NULL,                 -- 组织名称
    org_type        VARCHAR(32)   NOT NULL DEFAULT 'PERSONAL',
    -- PERSONAL=个人组织（注册时自动创建），ENTERPRISE=企业组织
    owner_id        VARCHAR(64)   NOT NULL,                 -- 创建者 userId
    description     TEXT,
    -- 组织配置（JSON）
    settings        JSONB         NOT NULL DEFAULT '{}',
    status          SMALLINT     NOT NULL DEFAULT 1,        -- 1=正常 0=已禁用
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_org_owner_id ON sys_organization(owner_id);
CREATE INDEX IF NOT EXISTS idx_org_type     ON sys_organization(org_type);

-- ============================================================
-- 2. 组织成员表
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_org_member (
    id              BIGSERIAL     PRIMARY KEY,
    org_id          VARCHAR(64)   NOT NULL,                 -- 组织 ID
    user_id         VARCHAR(64)   NOT NULL,                 -- 用户 ID
    role            VARCHAR(32)   NOT NULL DEFAULT 'MEMBER',
    -- OWNER=组织拥有者(可管理成员) ADMIN=管理员 MEMBER=普通成员
    joined_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    UNIQUE (org_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_org_member_org_id  ON sys_org_member(org_id);
CREATE INDEX IF NOT EXISTS idx_org_member_user_id ON sys_org_member(user_id);

-- ============================================================
-- 3. 知识库成员授权表
-- ============================================================
-- 让知识库不再只看 tenantId（组织级）过滤，而是支持更细粒度的成员级权限
-- 权限级别：OWNER=拥有者(全部权限) EDITOR=可编辑 VIEWER=只读
CREATE TABLE IF NOT EXISTS kb_member (
    id              BIGSERIAL     PRIMARY KEY,
    kb_id           BIGINT        NOT NULL REFERENCES kb_knowledge_base(id) ON DELETE CASCADE,
    user_id         VARCHAR(64)   NOT NULL,
    role            VARCHAR(32)   NOT NULL DEFAULT 'VIEWER',
    -- OWNER=拥有者(删库/管理成员) EDITOR=上传/删除文档 VIEWER=检索/问答
    granted_by      VARCHAR(64),                              -- 授权人 userId
    granted_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    UNIQUE (kb_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_kb_member_kb_id   ON kb_member(kb_id);
CREATE INDEX IF NOT EXISTS idx_kb_member_user_id ON kb_member(user_id);

-- ============================================================
-- 4. 用户表增加默认组织字段
-- ============================================================
ALTER TABLE biz_user_account ADD COLUMN IF NOT EXISTS default_org_id VARCHAR(64);
-- 每个用户注册时自动创建一个 PERSONAL 类型的组织，此字段记录该组织 ID

-- ============================================================
-- 5. 数据迁移：为已有用户自动创建个人组织
-- ============================================================
-- 遍历现有用户，为每个用户创建一个个人组织，并将 tenantId 指向该组织
-- 注意：这是一个一次性迁移脚本，pg 里可以用 DO 块执行

DO $$
DECLARE
    usr RECORD;
    new_org_id VARCHAR(64);
BEGIN
    FOR usr IN SELECT user_id FROM biz_user_account WHERE default_org_id IS NULL LOOP
        -- 生成组织 ID
        new_org_id := 'org_' || usr.user_id;

        -- 创建个人组织
        INSERT INTO sys_organization (org_id, name, org_type, owner_id, description)
        VALUES (new_org_id, '个人组织', 'PERSONAL', usr.user_id,
                '自动创建的个人组织')
        ON CONFLICT (org_id) DO NOTHING;

        -- 将用户加入组织
        INSERT INTO sys_org_member (org_id, user_id, role)
        VALUES (new_org_id, usr.user_id, 'OWNER')
        ON CONFLICT (org_id, user_id) DO NOTHING;

        -- 更新用户的默认组织
        UPDATE biz_user_account SET default_org_id = new_org_id
        WHERE user_id = usr.user_id;

        -- 更新该用户已有的知识库 tenantId（原来是 userId，改为 org_id）
        UPDATE kb_knowledge_base SET tenant_id = new_org_id
        WHERE tenant_id = usr.user_id;

        -- 更新该用户已有的文档 tenantId
        UPDATE kb_document SET tenant_id = new_org_id
        WHERE tenant_id = usr.user_id;

        -- 更新该用户已有的切片 tenantId
        UPDATE kb_chunk SET tenant_id = new_org_id
        WHERE tenant_id = usr.user_id;

        -- 更新该用户已有的检索日志 tenantId
        UPDATE kb_retrieval_log SET tenant_id = new_org_id
        WHERE tenant_id = usr.user_id;

        -- 为该用户已有的知识库创建成员记录（作为 OWNER）
        INSERT INTO kb_member (kb_id, user_id, role, granted_by)
        SELECT id, usr.user_id, 'OWNER', usr.user_id
        FROM kb_knowledge_base
        WHERE tenant_id = new_org_id
        ON CONFLICT (kb_id, user_id) DO NOTHING;
    END LOOP;
END $$;

