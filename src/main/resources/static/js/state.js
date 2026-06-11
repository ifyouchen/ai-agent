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

    /** 已导入文档列表 [{id, filename, chunks, uploadedAt}] */
    docs: [],

    /** SSE 是否正在流式输出中 */
    isStreaming: false,

    /** 是否开启流式输出 */
    streamEnabled: true,
};

export { generateId };

