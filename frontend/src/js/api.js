/**
 * API 层 —— 封装所有后端接口调用
 *
 * 所有请求通过 authFetch 自动附带 JWT Token。
 * SSE 流式接口（EventSource 不支持自定义 Header）通过 ?token= URL 参数传递。
 */

import {authFetch, getToken} from './auth.js';

const BASE = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '');

// ── 通用请求工具 ──────────────────────────────────────────────

async function api(method, url, body = null) {
    const opts = {
        method,
        headers: { 'Content-Type': 'application/json' }
    };
    if (body !== null) opts.body = JSON.stringify(body);
    const res = await authFetch(`${BASE}${url}`, opts);
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `请求失败: ${res.status}`);
    return data;
}

async function apiFormData(url, formData) {
    const token = getToken();
    const headers = token ? { Authorization: `Bearer ${token}` } : {};
    const res = await fetch(`${BASE}${url}`, { method: 'POST', headers, body: formData });
    if (res.status === 401) { import('./auth.js').then(m => m.logout()); throw new Error('登录已过期'); }
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `请求失败: ${res.status}`);
    return data;
}

// ── 聊天 ──────────────────────────────────────────────────────

export async function chatSync(sessionId, message) {
    return api('POST', '/api/v1/chat', { sessionId, message });
}

export function chatStream(sessionId, message) {
    const token = getToken();
    const params = new URLSearchParams({ sessionId, message, ...(token ? { token } : {}) });
    return new EventSource(`${BASE}/api/v1/chat/stream?${params.toString()}`);
}

export async function chatReact(sessionId, message) {
    return api('POST', '/api/v1/chat/react', { sessionId, message });
}

export async function clearMemory(sessionId) {
    const res = await authFetch(`${BASE}/api/v1/chat/memory/${sessionId}`, { method: 'DELETE' });
    if (!res.ok) throw new Error(`清除失败: ${res.status}`);
}

// ── 知识库 ──────────────────────────────────────────────────────

/** 列出我可访问的知识库 */
export async function listKnowledgeBases() {
    try {
        return await api('GET', '/api/v1/kb');
    } catch { return []; }
}

/** 创建知识库 */
export async function createKnowledgeBase(name, description) {
    return api('POST', '/api/v1/kb', { name, description });
}

/** 删除知识库 */
export async function deleteKnowledgeBase(kbId) {
    return api('DELETE', `/api/v1/kb/${kbId}`);
}

/** 上传文档到指定知识库 */
export async function uploadDocument(kbId, file) {
    const formData = new FormData();
    formData.append('file', file);
    return apiFormData(`/api/v1/kb/${kbId}/documents`, formData);
}

/** 列出知识库下的文档 */
export async function listDocuments(kbId) {
    try {
        return await api('GET', `/api/v1/kb/${kbId}/documents`);
    } catch { return []; }
}

/** 删除文档 */
export async function deleteDocument(kbId, docId) {
    return api('DELETE', `/api/v1/kb/${kbId}/documents/${docId}`);
}

/** 知识库问答 */
export async function queryKnowledgeBase(kbId, question) {
    return api('POST', `/api/v1/kb/${kbId}/query`, { question });
}

/** 获取知识库统计 */
export async function getKnowledgeBaseStats(kbId) {
    return api('GET', `/api/v1/kb/${kbId}/stats`);
}

// ── 知识库成员 ──────────────────────────────────────────────────

/** 列出知识库成员 */
export async function listKbMembers(kbId) {
    try {
        return await api('GET', `/api/v1/kb/${kbId}/members`);
    } catch { return []; }
}

/** 添加知识库成员 */
export async function addKbMember(kbId, userId, role) {
    return api('POST', `/api/v1/kb/${kbId}/members`, { userId, role });
}

/** 移除知识库成员 */
export async function removeKbMember(kbId, userId) {
    return api('DELETE', `/api/v1/kb/${kbId}/members/${userId}`);
}

// ── 组织 ──────────────────────────────────────────────────────

/** 列出我的组织 */
export async function listOrganizations() {
    try {
        return await api('GET', '/api/v1/org');
    } catch { return []; }
}

/** 创建企业组织 */
export async function createOrganization(name, description) {
    return api('POST', '/api/v1/org', { name, description });
}

/** 获取组织详情 */
export async function getOrganization(orgId) {
    return api('GET', `/api/v1/org/${orgId}`);
}

/** 邀请组织成员 */
export async function inviteOrgMember(orgId, userId, role) {
    return api('POST', `/api/v1/org/${orgId}/members`, { userId, role });
}

/** 列出组织成员 */
export async function listOrgMembers(orgId) {
    try {
        return await api('GET', `/api/v1/org/${orgId}/members`);
    } catch { return []; }
}

/** 移除组织成员 */
export async function removeOrgMember(orgId, userId) {
    return api('DELETE', `/api/v1/org/${orgId}/members/${userId}`);
}

