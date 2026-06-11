/**
 * API 层 —— 封装所有后端接口调用
 */

const BASE = '';

/**
 * 发送普通（同步）聊天请求
 */
export async function chatSync(sessionId, message) {
    const res = await fetch(`${BASE}/api/v1/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId, message })
    });
    if (!res.ok) throw new Error(`请求失败: ${res.status}`);
    return res.json();
}

/**
 * 返回 EventSource（SSE 流式对话）
 */
export function chatStream(sessionId, message) {
    const url = `${BASE}/api/v1/chat/stream?sessionId=${encodeURIComponent(sessionId)}&message=${encodeURIComponent(message)}`;
    return new EventSource(url);
}

/**
 * 清除指定会话的记忆
 */
export async function clearMemory(sessionId) {
    const res = await fetch(`${BASE}/api/v1/chat/memory/${sessionId}`, { method: 'DELETE' });
    if (!res.ok) throw new Error(`清除失败: ${res.status}`);
}

/**
 * 上传文档到知识库
 * @param {File} file
 * @returns {Promise<{chunks: number}>}
 */
export async function uploadDocument(file) {
    const formData = new FormData();
    formData.append('file', file);
    const res = await fetch(`${BASE}/api/v1/kb/ingest`, { method: 'POST', body: formData });
    if (!res.ok) throw new Error(`上传失败 (${res.status})`);
    return res.json();
}

/**
 * 查询知识库文档列表
 */
export async function listDocuments() {
    const res = await fetch(`${BASE}/api/v1/kb/documents`);
    if (!res.ok) return [];
    return res.json();
}

/**
 * 删除知识库中的指定文档
 */
export async function deleteDocument(docId) {
    const res = await fetch(`${BASE}/api/v1/kb/documents/${docId}`, { method: 'DELETE' });
    if (!res.ok) throw new Error(`删除失败: ${res.status}`);
}

