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

export async function inviteOrgMember(orgId, emailOrUsername, role) {
  const { data } = await http.post(`/api/v1/org/${orgId}/members`, { emailOrUsername, role });
  return data;
}

export async function listOrgInvitations(orgId) {
  const { data } = await http.get(`/api/v1/org/${orgId}/invitations`);
  return data;
}

export async function cancelOrgInvitation(orgId, invitationId) {
  const { data } = await http.delete(`/api/v1/org/${orgId}/invitations/${invitationId}`);
  return data;
}

export async function acceptOrgInvitation(token) {
  const { data } = await http.post(`/api/v1/org/invitations/${token}/accept`);
  return data;
}

export async function rejectOrgInvitation(token) {
  const { data } = await http.post(`/api/v1/org/invitations/${token}/reject`);
  return data;
}

export async function listMyInvitations() {
  const { data } = await http.get('/api/v1/org/invitations/my');
  return data;
}

export async function applyJoinOrg(orgId, message) {
  const { data } = await http.post(`/api/v1/org/${orgId}/join-requests`, { message });
  return data;
}

export async function listOrgJoinRequests(orgId) {
  const { data } = await http.get(`/api/v1/org/${orgId}/join-requests`);
  return data;
}

export async function approveOrgJoinRequest(requestId) {
  const { data } = await http.post(`/api/v1/org/join-requests/${requestId}/approve`);
  return data;
}

export async function rejectOrgJoinRequest(requestId) {
  const { data } = await http.post(`/api/v1/org/join-requests/${requestId}/reject`);
  return data;
}

export async function listMyJoinRequests() {
  const { data } = await http.get('/api/v1/org/join-requests/my');
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
