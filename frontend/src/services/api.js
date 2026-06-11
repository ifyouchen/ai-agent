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

export async function chatSync(sessionId, message) {
  const { data } = await http.post('/api/v1/chat', { sessionId, message });
  return data;
}

export function chatStream(sessionId, message) {
  const token = getToken();
  const params = new URLSearchParams({ sessionId, message, ...(token ? { token } : {}) });
  return new EventSource(`${BASE}/api/v1/chat/stream?${params.toString()}`);
}

export async function chatReact(sessionId, message) {
  const { data } = await http.post('/api/v1/chat/react', { sessionId, message });
  return data;
}

export async function clearMemory(sessionId) {
  await http.delete(`/api/v1/chat/memory/${sessionId}`);
}

export async function listKnowledgeBases() {
  const { data } = await http.get('/api/v1/kb');
  return data;
}

export async function createKnowledgeBase(name, description) {
  const { data } = await http.post('/api/v1/kb', { name, description });
  return data;
}

export async function deleteKnowledgeBase(kbId) {
  const { data } = await http.delete(`/api/v1/kb/${kbId}`);
  return data;
}

export async function uploadDocument(kbId, file) {
  const formData = new FormData();
  formData.append('file', file);
  const { data } = await http.post(`/api/v1/kb/${kbId}/documents`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  });
  return data;
}

export async function listDocuments(kbId) {
  const { data } = await http.get(`/api/v1/kb/${kbId}/documents`);
  return data;
}

export async function deleteDocument(kbId, docId) {
  const { data } = await http.delete(`/api/v1/kb/${kbId}/documents/${docId}`);
  return data;
}

export async function queryKnowledgeBase(kbId, question) {
  const { data } = await http.post(`/api/v1/kb/${kbId}/query`, { question });
  return data;
}

export async function listKbMembers(kbId) {
  const { data } = await http.get(`/api/v1/kb/${kbId}/members`);
  return data;
}

export async function addKbMember(kbId, userId, role) {
  const { data } = await http.post(`/api/v1/kb/${kbId}/members`, { userId, role });
  return data;
}

export async function removeKbMember(kbId, userId) {
  const { data } = await http.delete(`/api/v1/kb/${kbId}/members/${userId}`);
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
