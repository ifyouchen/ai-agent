import axios from 'axios';

const TOKEN_KEY = 'ai_agent_token';
const USER_KEY = 'ai_agent_user';
const BASE = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '');

export const http = axios.create({
  baseURL: BASE,
  headers: { 'Content-Type': 'application/json' }
});

http.interceptors.request.use((config) => {
  const token = getToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      logout();
      return Promise.reject(new Error('登录已过期，请重新登录'));
    }

    if (error.response?.status === 429) {
      const retryAfter = parseInt(error.response.headers?.['retry-after'] || '60', 10);
      return Promise.reject(new Error(`请求频率超限，请 ${retryAfter} 秒后再试`));
    }

    if (!error.response) {
      return Promise.reject(new Error('无法连接服务器，请稍后再试'));
    }

    const data = error.response.data;
    const message = data?.error || data?.message || `请求失败 (${error.response.status})`;
    return Promise.reject(new Error(message));
  }
);

export function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

export function getUser() {
  try {
    const raw = localStorage.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export function saveAuth(data) {
  localStorage.setItem(TOKEN_KEY, data.token);
  localStorage.setItem(USER_KEY, JSON.stringify({
    userId: data.userId,
    username: data.username,
    roles: data.roles
  }));
}

export function logout() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
  location.replace('/login.html');
}

export async function login(payload) {
  const { data } = await http.post('/api/v1/auth/login', payload);
  saveAuth(data);
  return data;
}

export async function register(payload) {
  const { data } = await http.post('/api/v1/auth/register', payload);
  saveAuth(data);
  return data;
}

/**
 * 同步对话
 * @param {string} sessionId 会话 ID
 * @param {string} message 消息
 * @param {number|null} kbId 知识库 ID（可选，指定后基于该知识库内容回答）
 */
export async function chatSync(sessionId, message, kbId) {
  const { data } = await http.post('/api/v1/chat', { sessionId, message, kbId: kbId || null });
  return data;
}

/**
 * 流式对话（SSE）
 * @param {string} sessionId 会话 ID
 * @param {string} message 消息
 * @param {number|null} kbId 知识库 ID（可选）
 */
export function chatStream(sessionId, message, kbId) {
  const token = getToken();
  const params = new URLSearchParams({ sessionId, message, ...(token ? { token } : {}) });
  if (kbId) params.set('kbId', String(kbId));
  return new EventSource(`${BASE}/api/v1/chat/stream?${params.toString()}`);
}

/**
 * ReAct 同步推理
 * @param {string} sessionId 会话 ID
 * @param {string} message 消息
 * @param {number|null} kbId 知识库 ID（可选）
 */
export async function chatReact(sessionId, message, kbId) {
  const { data } = await http.post('/api/v1/chat/react', { sessionId, message, kbId: kbId ? String(kbId) : null });
  return data;
}

/**
 * ReAct 多步推理流式接口（SSE）
 * 每完成一个推理步骤立即推送，前端可实时看到思考过程
 *
 * SSE 事件：
 *   step         - 推理步骤  JSON: {iteration, thought, toolName, toolArgs, observation}
 *   answer       - 最终答案  JSON: {answer, iterations, durationMs}
 *   replace-answer - 脱敏替换 JSON: {answer}
 *   error        - 错误文本
 *   done         - 结束标识
 *
 * @param {string} sessionId 会话 ID
 * @param {string} message 消息
 * @param {number|null} kbId 知识库 ID（可选）
 */
export function chatReactStream(sessionId, message, kbId) {
  const token = getToken();
  const params = new URLSearchParams({ sessionId, message, ...(token ? { token } : {}) });
  if (kbId) params.set('kbId', String(kbId));
  return new EventSource(`${BASE}/api/v1/chat/react/stream?${params.toString()}`);
}

export async function clearMemory(sessionId) {
  await http.delete(`/api/v1/chat/memory/${sessionId}`);
}

// ── 聊天历史（服务端持久化） ─────────────────────────────────

/**
 * 查询当前用户的会话列表（从服务端，最近 50 个）
 * @param {string} keyword 可选关键词，传入时按标题模糊搜索
 */
export async function listChatSessions(keyword = '') {
  const params = keyword?.trim() ? { keyword: keyword.trim() } : {};
  const { data } = await http.get('/api/v1/chat/sessions', { params });
  return data;
}

/**
 * 查询某会话的历史消息
 */
export async function getChatMessages(sessionId) {
  const { data } = await http.get(`/api/v1/chat/sessions/${sessionId}/messages`);
  return data;
}

/**
 * 删除会话（同时删除服务端记录）
 */
export async function deleteChatSession(sessionId) {
  await http.delete(`/api/v1/chat/sessions/${sessionId}`);
}

/**
 * 更新会话标题（前端双击标题后保存到服务端）
 */
export async function updateSessionTitle(sessionId, title) {
  await http.patch(`/api/v1/chat/sessions/${sessionId}/title`, { title });
}

/**
 * 将前端 localStorage 历史同步到服务端（首次加载时调用）
 */
export async function syncChatSessions(sessions) {
  await http.post('/api/v1/chat/sessions/sync', sessions);
}

// ── 知识库 API（所有接口支持可选 orgId 参数，不传则使用默认个人组织） ──────────────

export async function listKnowledgeBases(orgId) {
  const params = orgId ? { params: { orgId } } : {};
  const { data } = await http.get('/api/v1/kb', params);
  return data;
}

export async function createKnowledgeBase(name, description, orgId) {
  const params = orgId ? { params: { orgId } } : {};
  const { data } = await http.post('/api/v1/kb', { name, description }, params);
  return data;
}

export async function deleteKnowledgeBase(kbId, orgId) {
  const params = orgId ? { params: { orgId } } : {};
  const { data } = await http.delete(`/api/v1/kb/${kbId}`, params);
  return data;
}

export async function updateKnowledgeBase(kbId, name, description, orgId) {
  const params = orgId ? { params: { orgId } } : {};
  const { data } = await http.put(`/api/v1/kb/${kbId}`, { name, description }, params);
  return data;
}

export async function uploadDocument(kbId, file, onProgress, orgId) {
  const formData = new FormData();
  formData.append('file', file);
  const params = orgId ? { orgId } : {};
  const { data } = await http.post(`/api/v1/kb/${kbId}/documents`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    params,
    onUploadProgress: onProgress
      ? (evt) => {
          const pct = evt.total ? Math.round((evt.loaded / evt.total) * 100) : 0;
          onProgress({ loaded: evt.loaded, total: evt.total, pct });
        }
      : undefined
  });
  return data;
}

export async function listDocuments(kbId, orgId) {
  const params = orgId ? { params: { orgId } } : {};
  const { data } = await http.get(`/api/v1/kb/${kbId}/documents`, params);
  return data;
}

export async function deleteDocument(kbId, docId, orgId) {
  const params = orgId ? { params: { orgId } } : {};
  const { data } = await http.delete(`/api/v1/kb/${kbId}/documents/${docId}`, params);
  return data;
}

/**
 * 查询单个文档解析状态（前端轮询专用，比拉全量列表更轻量）
 * GET /api/v1/kb/{kbId}/documents/{docId}/status
 */
export async function getDocumentStatus(kbId, docId, orgId) {
  const params = orgId ? { params: { orgId } } : {};
  const { data } = await http.get(`/api/v1/kb/${kbId}/documents/${docId}/status`, params);
  return data;
}

/**
 * 获取文档切片列表（P3-15：用于前端预览）
 */
export async function listDocumentChunks(kbId, docId, orgId, limit = 20) {
  const params = { ...(orgId ? { orgId } : {}), limit };
  const { data } = await http.get(`/api/v1/kb/${kbId}/documents/${docId}/chunks`, { params });
  return data;
}

export async function queryKnowledgeBase(kbId, question, orgId) {
  const params = orgId ? { params: { orgId } } : {};
  const { data } = await http.post(`/api/v1/kb/${kbId}/query`, { question }, params);
  return data;
}

export async function listKbMembers(kbId, orgId) {
  const params = orgId ? { params: { orgId } } : {};
  const { data } = await http.get(`/api/v1/kb/${kbId}/members`, params);
  return data;
}

export async function addKbMember(kbId, userId, role, orgId) {
  const params = orgId ? { params: { orgId } } : {};
  const { data } = await http.post(`/api/v1/kb/${kbId}/members`, { userId, role }, params);
  return data;
}

export async function removeKbMember(kbId, userId, orgId) {
  const params = orgId ? { params: { orgId } } : {};
  const { data } = await http.delete(`/api/v1/kb/${kbId}/members/${userId}`, params);
  return data;
}

export async function updateKbMemberRole(kbId, userId, role, orgId) {
  const params = orgId ? { params: { orgId } } : {};
  const { data } = await http.put(`/api/v1/kb/${kbId}/members/${userId}`, { role }, params);
  return data;
}

export async function listOrganizations() {
  const { data } = await http.get('/api/v1/org');
  return data;
}

export async function createOrganization(name, description) {
  const { data } = await http.post('/api/v1/org', { name, description });
  return data;
}

export async function getOrganization(orgId) {
  const { data } = await http.get(`/api/v1/org/${orgId}`);
  return data;
}

export async function inviteOrgMember(orgId, userId, role) {
  const { data } = await http.post(`/api/v1/org/${orgId}/members`, { userId, role });
  return data;
}

export async function removeOrgMember(orgId, userId) {
  const { data } = await http.delete(`/api/v1/org/${orgId}/members/${userId}`);
  return data;
}

export async function updateOrganization(orgId, name, description) {
  const { data } = await http.put(`/api/v1/org/${orgId}`, { name, description });
  return data;
}

export async function updateOrgMemberRole(orgId, userId, role) {
  const { data } = await http.put(`/api/v1/org/${orgId}/members/${userId}`, { role });
  return data;
}

export async function deleteOrganization(orgId) {
  const { data } = await http.delete(`/api/v1/org/${orgId}`);
  return data;
}

export async function leaveOrganization(orgId) {
  const { data } = await http.delete(`/api/v1/org/${orgId}/leave`);
  return data;
}

// ── 用户搜索（用于成员添加） ────────────────────────────────────

/**
 * 用户名模糊搜索，返回 [{userId, username}] 列表（最多 10 条）
 */
export async function searchUsers(keyword) {
  if (!keyword?.trim()) return [];
  const { data } = await http.get('/api/v1/auth/users/search', { params: { keyword: keyword.trim() } });
  return data;
}

// ── 用户个人资料 ──────────────────────────────────────────────────

export async function getProfile() {
  const { data } = await http.get('/api/v1/auth/profile');
  return data;
}

export async function changePassword(oldPassword, newPassword) {
  const { data } = await http.put('/api/v1/auth/profile/password', { oldPassword, newPassword });
  return data;
}

/**
 * 更新用户 Profile（昵称、邮箱）
 * PUT /api/v1/auth/profile
 */
export async function updateProfile(nickname, email) {
  const { data } = await http.put('/api/v1/auth/profile', { nickname, email });
  return data;
}

// ── 管理员：用户管理 ──────────────────────────────────────────────

export async function adminListUsers(page = 0, size = 20, keyword = '') {
  const params = { page, size };
  if (keyword?.trim()) params.keyword = keyword.trim();
  const { data } = await http.get('/api/v1/admin/users', { params });
  return data;
}

export async function adminEnableUser(userId) {
  const { data } = await http.put(`/api/v1/admin/users/${userId}/enable`);
  return data;
}

export async function adminDisableUser(userId) {
  const { data } = await http.put(`/api/v1/admin/users/${userId}/disable`);
  return data;
}

export async function adminSetRole(userId, role) {
  const { data } = await http.put(`/api/v1/admin/users/${userId}/role`, { role });
  return data;
}

// ── 可观测性：Token 用量 ────────────────────────────────────────

export async function getMyTodayCost() {
  const { data } = await http.get('/api/v1/token-usage/my/today');
  return data;
}

export async function adminGetTodayCost() {
  const { data } = await http.get('/api/v1/admin/token-usage/today');
  return data;
}

export async function adminGetModelReport(days = 7) {
  const { data } = await http.get('/api/v1/admin/token-usage/report/model', { params: { days } });
  return data;
}

export async function adminGetUserReport(days = 7) {
  const { data } = await http.get('/api/v1/admin/token-usage/report/user', { params: { days } });
  return data;
}

export async function adminGetErrorRate(minutes = 5) {
  const { data } = await http.get('/api/v1/admin/token-usage/error-rate', { params: { minutes } });
  return data;
}

export async function getMyDailyReport(days = 7) {
  const { data } = await http.get('/api/v1/token-usage/my/daily', { params: { days } });
  return data;
}

export async function adminGetDailyReport(days = 7) {
  const { data } = await http.get('/api/v1/admin/token-usage/report/daily', { params: { days } });
  return data;
}

// ── 消息反馈（点赞/点踩/撤销） ─────────────────────────────────
/**
 * 保存消息反馈
 * @param {number} messageId  数据库消息 ID
 * @param {string|null} feedback 'up' | 'down' | null（null = 撤销）
 */
export async function saveMessageFeedback(messageId, feedback) {
  await http.patch(`/api/v1/chat/messages/${messageId}/feedback`, { feedback: feedback ?? null });
}
