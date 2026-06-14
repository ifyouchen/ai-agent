import axios from 'axios';

const TOKEN_KEY = 'ai_agent_token';
const USER_KEY = 'ai_agent_user';
const PUBLIC_AUTH_PATHS = new Set([
  '/api/v1/auth/login',
  '/api/v1/auth/email-login',
  '/api/v1/auth/register',
  '/api/v1/auth/email-code',
  '/api/v1/auth/forgot-password',
  '/api/v1/auth/reset-password',
]);

export const BASE = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '');

export const http = axios.create({
  baseURL: BASE,
  headers: { 'Content-Type': 'application/json' }
});

http.interceptors.request.use((config) => {
  const token = getToken();
  if (token && !isPublicAuthRequest(config)) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      const data = error.response.data;
      const message = data?.error || data?.message || '用户名或密码错误';
      if (isPublicAuthRequest(error.config)) {
        return Promise.reject(new Error(message));
      }
      if (error.config?.skipAuthRedirect) {
        clearAuth();
      } else {
        logout();
      }
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

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

export function logout() {
  clearAuth();
  location.replace('/login.html');
}

function isPublicAuthRequest(config = {}) {
  return PUBLIC_AUTH_PATHS.has(requestPath(config));
}

function requestPath(config = {}) {
  const rawUrl = config.url || '';
  try {
    const url = rawUrl.startsWith('http')
      ? new URL(rawUrl)
      : new URL(rawUrl, 'http://local');
    return url.pathname;
  } catch {
    return rawUrl.split('?')[0];
  }
}
