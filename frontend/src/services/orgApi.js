import { http } from './http.js';

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
