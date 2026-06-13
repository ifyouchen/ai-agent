/**
 * Auth Store — 用户认证状态
 *
 * 封装 JWT token 管理、用户信息、登录/登出操作。
 */
import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import * as api from '../services/api.js';

export const useAuthStore = defineStore('auth', () => {
  const user  = ref(api.getUser());
  const token = ref(api.getToken());

  /** 是否具有管理员角色 */
  const isAdmin = computed(() =>
    user.value?.roles?.includes('ROLE_ADMIN') ?? false
  );

  /** 显示名：优先昵称，其次用户名 */
  const displayName = computed(() =>
    user.value?.nickname || user.value?.username || ''
  );

  /** 头像首字母（用于 Avatar 组件） */
  const avatarLetter = computed(() =>
    (displayName.value[0] || 'U').toUpperCase()
  );

  function setAuth(data) {
    api.saveAuth(data);
    token.value = data.token;
    user.value  = {
      userId:   data.userId,
      username: data.username,
      roles:    data.roles,
      nickname: data.nickname || '',
      email:    data.email    || '',
    };
  }

  function mergeUserProfile(profile) {
    if (!user.value || !profile) return;
    user.value = { ...user.value, ...profile };
    const stored = api.getUser();
    if (stored) {
      localStorage.setItem('ai_agent_user', JSON.stringify({
        ...stored,
        ...profile,
      }));
    }
  }

  async function login(payload) {
    const data = await api.login(payload);
    setAuth(data);
    return data;
  }

  function logout() {
    api.logout();
  }

  /** 更新 Profile（仅昵称可修改，后端成功后刷新本地 user 对象） */
  async function updateProfile(nickname) {
    const updated = await api.updateProfile(nickname);
    mergeUserProfile(updated);
    return updated;
  }

  /** 启动时从服务端补全 profile（nickname/email） */
  async function refreshProfile() {
    if (!token.value) return;
    try {
      const profile = await api.getProfile();
      mergeUserProfile(profile);
    } catch { /* 静默失败 */ }
  }

  return {
    user, token, isAdmin, displayName, avatarLetter,
    setAuth, login, logout, updateProfile, refreshProfile,
  };
});
