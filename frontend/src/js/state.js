/**
 * 全局状态管理（响应式简版 Store）
 *
 * 模块间通过 state 对象共享状态，避免全局变量污染
 */

function generateId() {
    return 'session-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7);
}

export const state = {
    /** 当前活动会话 ID */
    sessionId: generateId(),

    /** 所有会话列表 [{id, title, createdAt}] */
    sessions: [],

    /** 已导入文档列表 [{id, filename, chunks, uploadedAt}] (当前选中知识库) */
    docs: [],

    /** SSE 是否正在流式输出中 */
    isStreaming: false,

    /** 是否开启流式输出 */
    streamEnabled: true,

    /** 是否开启 ReAct 多步推理模式 */
    reactEnabled: false,

    // ── 知识库状态 ──────────────────────────────────────────

    /** 知识库列表 [{id, name, description, docCount, tenantId}] */
    knowledgeBases: [],

    /** 当前选中的知识库 ID (null 表示未选中) */
    currentKbId: null,

    /** 当前选中知识库的详情 */
    currentKb: null,

    // ── 组织状态 ──────────────────────────────────────────

    /** 我加入的组织列表 [{orgId, role, name}] */
    organizations: [],

    /** 当前选中的组织 ID */
    currentOrgId: null,
};

export { generateId };

