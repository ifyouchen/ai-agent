/**
 * API 层 —— 封装所有后端接口调用
 *
 * 所有请求通过 authFetch 自动附带 JWT Token。
 * SSE 流式接口（EventSource 不支持自定义 Header）通过 ?token= URL 参数传递。
 */

import {authFetch, getToken} from './auth.js';

const BASE = '';

/**
 * 发送普通（同步）聊天请求
 */
export async function chatSync(sessionId, message) {
    const res = await authFetch(`${BASE}/api/v1/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId, message })
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.error || `请求失败: ${res.status}`);
    }
    return res.json();
}

/**
 * 返回 EventSource（SSE 流式对话）
 *
 * 注意：EventSource 原生不支持自定义 Header，因此将 JWT Token 作为 URL 参数传递。
 * 后端 JwtAuthFilter 已支持从 ?token= 参数中提取 Token。
 */
export function chatStream(sessionId, message) {
    const token = getToken();
    const params = new URLSearchParams({
        sessionId,
        message,
        ...(token ? { token } : {})
    });
    return new EventSource(`${BASE}/api/v1/chat/stream?${params.toString()}`);
}

/**
 * 清除指定会话的记忆
 */
export async function clearMemory(sessionId) {
    const res = await authFetch(`${BASE}/api/v1/chat/memory/${sessionId}`, {
        method: 'DELETE'
    });
    if (!res.ok) throw new Error(`清除失败: ${res.status}`);
}

/**
 * 上传文档到知识库
 * @param {File} file
 * @returns {Promise<{chunks: number}>}
 */
export async function uploadDocument(file) {
    const token = getToken();
    const formData = new FormData();
    formData.append('file', file);

    const headers = token ? { Authorization: `Bearer ${token}` } : {};
    const res = await fetch(`${BASE}/api/v1/kb/ingest`, {
        method: 'POST',
        headers,
        body: formData
    });
    if (!res.ok) {
        if (res.status === 401) {
            import('./auth.js').then(m => m.logout());
            throw new Error('登录已过期，请重新登录');
        }
        throw new Error(`上传失败 (${res.status})`);
    }
    return res.json();
}

/**
 * 查询知识库文档列表
 */
export async function listDocuments() {
    const res = await authFetch(`${BASE}/api/v1/kb/documents`);
    if (!res.ok) return [];
    return res.json();
}

/**
 * 删除知识库中的指定文档
 */
export async function deleteDocument(docId) {
    const res = await authFetch(`${BASE}/api/v1/kb/documents/${docId}`, {
        method: 'DELETE'
    });
    if (!res.ok) throw new Error(`删除失败: ${res.status}`);
}

