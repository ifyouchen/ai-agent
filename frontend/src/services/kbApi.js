import { http } from './http.js';

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

export async function getDocumentStatus(kbId, docId, orgId) {
  const params = orgId ? { params: { orgId } } : {};
  const { data } = await http.get(`/api/v1/kb/${kbId}/documents/${docId}/status`, params);
  return data;
}

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
