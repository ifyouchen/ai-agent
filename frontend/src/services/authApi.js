import { http, saveAuth } from './http.js';

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

export async function searchUsers(keyword) {
  if (!keyword?.trim()) return [];
  const { data } = await http.get('/api/v1/auth/users/search', { params: { keyword: keyword.trim() } });
  return data;
}

export async function getProfile() {
  const { data } = await http.get('/api/v1/auth/profile');
  return data;
}

export async function changePassword(oldPassword, newPassword) {
  const { data } = await http.put('/api/v1/auth/profile/password', { oldPassword, newPassword });
  return data;
}

export async function updateProfile(nickname, email) {
  const { data } = await http.put('/api/v1/auth/profile', { nickname, email });
  return data;
}
