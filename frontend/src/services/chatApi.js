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

export async function chatStream(sessionId, message, kbId, model, orgId) {
  const token = getToken();
  const { data } = await http.post('/api/v1/chat/stream', {
    sessionId,
    message,
    kbId: kbId ? String(kbId) : null,
    model,
    orgId: orgId || null,
  });
  const params = new URLSearchParams(token ? { token } : {});
  const query = params.toString();
  return new EventSource(`${BASE}/api/v1/chat/stream/${encodeURIComponent(data.streamId)}${query ? `?${query}` : ''}`);
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

export async function chatReactStream(sessionId, message, kbId, model, orgId) {
  const token = getToken();
  const { data } = await http.post('/api/v1/chat/react/stream', {
    sessionId,
    message,
    kbId: kbId ? String(kbId) : null,
    model,
    orgId: orgId || null,
  });
  const params = new URLSearchParams(token ? { token } : {});
  const query = params.toString();
  return new EventSource(`${BASE}/api/v1/chat/react/stream/${encodeURIComponent(data.streamId)}${query ? `?${query}` : ''}`);
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

export async function rewriteChatMessages(sessionId, messages) {
  await http.put(`/api/v1/chat/sessions/${sessionId}/messages`, { messages });
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

export async function createChatShare(sessionId, payload) {
  const { data } = await http.post(`/api/v1/chat/sessions/${sessionId}/share`, payload || {});
  return data;
}

export async function getChatShare(shareId) {
  const { data } = await http.get(`/api/v1/chat/share/${shareId}`);
  return data;
}

export async function revokeChatShare(shareId) {
  await http.delete(`/api/v1/chat/share/${shareId}`);
}
