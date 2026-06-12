import { http } from './http.js';

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
