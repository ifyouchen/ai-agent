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

export async function sendEmailCode(email, purpose = 'register') {
  const { data } = await http.post('/api/v1/auth/email-code', { email, purpose });
  return data;
}

export async function forgotPassword(email) {
  const { data } = await http.post('/api/v1/auth/forgot-password', { email });
  return data;
}

export async function resetPassword(email, emailCode, newPassword) {
  const { data } = await http.post('/api/v1/auth/reset-password', { email, emailCode, newPassword });
  return data;
}

export async function searchUsers(keyword) {
  if (!keyword?.trim()) return [];
  const { data } = await http.get('/api/v1/auth/users/search', { params: { keyword: keyword.trim() } });
  return data;
}

export async function getProfile(config = {}) {
  const { data } = await http.get('/api/v1/auth/profile', config);
  return data;
}

export async function changePassword(newPassword) {
  const { data } = await http.put('/api/v1/auth/profile/password', { newPassword });
  return data;
}

export async function updateProfile(nickname) {
  const { data } = await http.put('/api/v1/auth/profile', { nickname });
  return data;
}
