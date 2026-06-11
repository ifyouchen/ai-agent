/**
 * 认证模块 —— 统一管理 JWT Token 的存取与 API 请求注入
 *
 * 使用 localStorage 持久化 Token，页面刷新后自动恢复登录态。
 * 提供：
 *   - getToken()           读取当前 Token
 *   - getUser()            读取当前用户信息
 *   - isLoggedIn()         是否已登录
 *   - logout()             登出并跳转登录页
 *   - authHeaders()        返回带 Authorization Header 的对象
 *   - authFetch(url, opts) 自动注入 Token 的 fetch 包装
 */

const TOKEN_KEY = 'ai_agent_token';
const USER_KEY  = 'ai_agent_user';

/** 获取当前 JWT Token（未登录返回 null） */
export function getToken() {
    return localStorage.getItem(TOKEN_KEY);
}

/** 获取当前用户信息对象 {userId, username, roles}（未登录返回 null） */
export function getUser() {
    try {
        const raw = localStorage.getItem(USER_KEY);
        return raw ? JSON.parse(raw) : null;
    } catch {
        return null;
    }
}

/** 是否已登录 */
export function isLoggedIn() {
    return !!getToken();
}

/** 登出：清除本地存储并跳转登录页 */
export function logout() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    location.replace('/login.html');
}

/**
 * 返回带 Authorization 的请求头对象
 * @returns {{ Authorization: string, 'Content-Type': string }}
 */
export function authHeaders(extra = {}) {
    const token = getToken();
    return {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...extra
    };
}

/**
 * 自动注入 Token 的 fetch 包装
 * - 自动在 Header 中附加 Bearer Token
 * - 401 时自动登出跳转
 */
export async function authFetch(url, options = {}) {
    const token = getToken();
    const headers = {
        ...(options.headers || {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {})
    };

    const res = await fetch(url, { ...options, headers });

    if (res.status === 401) {
        // Token 过期或无效，强制登出
        logout();
        throw new Error('登录已过期，请重新登录');
    }

    return res;
}

/**
 * 在页面加载时调用：未登录则跳转登录页
 * 在 index.html 的 app.js 入口调用
 */
export function requireAuth() {
    if (!isLoggedIn()) {
        location.replace('/login.html');
        return false;
    }
    return true;
}

