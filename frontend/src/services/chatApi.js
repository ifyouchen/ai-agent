import { BASE, getToken, http } from './http.js';

export async function chatSync(sessionId, message, kbId, model, orgId) {
  const { data } = await http.post('/api/v1/chat', {
    sessionId,
    message,
    kbId: kbId || null,
    model,
    orgId: orgId || null,
  });
  return data;
}

export function chatStream(sessionId, message, kbId, model, orgId) {
  const token = getToken();
  const params = new URLSearchParams({ sessionId, message, ...(token ? { token } : {}) });
  if (kbId) params.set('kbId', String(kbId));
  if (model) params.set('model', model);
  if (orgId) params.set('orgId', orgId);
  return new EventSource(`${BASE}/api/v1/chat/stream?${params.toString()}`);
}

export async function chatReact(sessionId, message, kbId, model, orgId) {
  const { data } = await http.post('/api/v1/chat/react', {
    sessionId,
    message,
    kbId: kbId ? String(kbId) : null,
    model,
    orgId: orgId || null,
  });
  return data;
}

export function chatReactStream(sessionId, message, kbId, model, orgId) {
  const token = getToken();
  const params = new URLSearchParams({ sessionId, message, ...(token ? { token } : {}) });
  if (kbId) params.set('kbId', String(kbId));
  if (model) params.set('model', model);
  if (orgId) params.set('orgId', orgId);
  return new EventSource(`${BASE}/api/v1/chat/react/stream?${params.toString()}`);
}

export async function clearMemory(sessionId) {
  await http.delete(`/api/v1/chat/memory/${sessionId}`);
}

export async function listChatSessions(keyword = '') {
  const params = keyword?.trim() ? { keyword: keyword.trim() } : {};
  const { data } = await http.get('/api/v1/chat/sessions', { params });
  return data;
}

export async function getChatMessages(sessionId) {
  const { data } = await http.get(`/api/v1/chat/sessions/${sessionId}/messages`);
  return data;
}

export async function deleteChatSession(sessionId) {
  await http.delete(`/api/v1/chat/sessions/${sessionId}`);
}

export async function deleteChatSessions(sessionIds) {
  await http.post('/api/v1/chat/sessions/batch-delete', { sessionIds });
}

export async function deleteAllChatSessions() {
  await http.delete('/api/v1/chat/sessions');
}

export async function updateSessionTitle(sessionId, title) {
  await http.patch(`/api/v1/chat/sessions/${sessionId}/title`, { title });
}

export async function syncChatSessions(sessions) {
  await http.post('/api/v1/chat/sessions/sync', sessions);
}

export async function saveMessageFeedback(messageId, feedback) {
  await http.patch(`/api/v1/chat/messages/${messageId}/feedback`, { feedback: feedback ?? null });
}
