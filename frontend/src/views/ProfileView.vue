<template>
  <div class="profile-view">
    <div class="profile-container">
      <!-- 顶部头像区 -->
      <div class="profile-hero">
        <div class="profile-avatar-wrap">
          <Avatar class="profile-avatar-main" :name="auth.displayName" :size="72" />
          <div class="profile-names">
            <h2 class="profile-username">{{ auth.user?.username }}</h2>
            <p class="profile-subtitle">{{ form.nickname || auth.user?.email || '已登录' }}</p>
            <div class="profile-badges">
              <span class="profile-roles">{{ rolesLabel }}</span>
              <span class="profile-userid">ID：{{ auth.user?.userId }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 基本信息卡片 -->
      <div class="profile-card">
        <div class="profile-card-header">
          <h3 class="profile-card-title">
            <div class="profile-card-title-icon">
              <svg viewBox="0 0 24 24" fill="none" width="16" height="16">
                <circle cx="12" cy="8" r="4" stroke="currentColor" stroke-width="1.8"/>
                <path d="M4 20c0-4 3.6-7 8-7s8 3 8 7" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
              </svg>
            </div>
            基本信息
          </h3>
        </div>
        <div class="profile-form">
          <label class="profile-field">
            <span class="profile-label">用户名</span>
            <input type="text" class="profile-input" :value="auth.user?.username" disabled />
          </label>
          <label class="profile-field">
            <span class="profile-label">昵称</span>
            <input v-model.trim="form.nickname" type="text" class="profile-input" placeholder="设置一个昵称（可选）" maxlength="50" />
          </label>
          <label class="profile-field">
            <span class="profile-label">邮箱</span>
            <div class="profile-readonly-value" :class="{ muted: !auth.user?.email }">
              {{ emailDisplay }}
            </div>
            <span class="profile-hint">邮箱注册后不可修改</span>
          </label>
          <button class="profile-save-btn" type="button" :disabled="saving" @click="saveProfile">
            <svg v-if="saving" class="inline-spinner" viewBox="0 0 24 24" fill="none" width="14" height="14">
              <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
            </svg>
            {{ saving ? '保存中…' : '保存资料' }}
          </button>
        </div>
      </div>

      <!-- 安全设置卡片 -->
      <div class="profile-card">
        <div class="profile-card-header">
          <h3 class="profile-card-title">
            <div class="profile-card-title-icon">
              <svg viewBox="0 0 24 24" fill="none" width="16" height="16">
                <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z" stroke="currentColor" stroke-width="1.8"/>
                <path d="m9 12 2 2 4-4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
              </svg>
            </div>
            安全设置
          </h3>
          <button class="profile-toggle-btn" type="button" @click="pwdVisible = !pwdVisible">
            <svg v-if="!pwdVisible" viewBox="0 0 24 24" fill="none" width="14" height="14">
              <path d="m6 9 6 6 6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            </svg>
            <svg v-else viewBox="0 0 24 24" fill="none" width="14" height="14">
              <path d="m18 15-6-6-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            </svg>
            {{ pwdVisible ? '收起' : '修改密码' }}
          </button>
        </div>
        <div v-if="pwdVisible" class="password-section">
          <div class="profile-form">
            <label class="profile-field">
              <span class="profile-label">邮箱验证码</span>
              <div class="verification-row">
                <input
                  v-model.trim="pwd.emailCode"
                  type="text"
                  class="profile-input verification-input"
                  inputmode="numeric"
                  maxlength="6"
                  placeholder="6 位验证码"
                />
                <button
                  class="code-btn"
                  type="button"
                  :disabled="!canSendCode || codeSending || codeCountdown > 0"
                  @click="sendChangePasswordCode"
                >
                  <svg v-if="codeSending" class="inline-spinner" viewBox="0 0 24 24" fill="none" width="14" height="14">
                    <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
                  </svg>
                  {{ codeButtonText }}
                </button>
              </div>
            </label>
            <label class="profile-field">
              <span class="profile-label">新密码</span>
              <input v-model="pwd.new" type="password" class="profile-input" placeholder="至少 6 位" />
            </label>
            <label class="profile-field">
              <span class="profile-label">确认新密码</span>
              <input v-model="pwd.confirm" type="password" class="profile-input" placeholder="再次输入新密码" />
            </label>
            <button class="profile-save-btn" type="button" :disabled="pwdSaving" @click="changePassword">
              {{ pwdSaving ? '保存中…' : '修改密码' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, onUnmounted } from 'vue';
import Avatar from '../components/ui/Avatar.vue';
import { useAuthStore } from '../stores/auth.js';
import { useUiStore } from '../stores/ui.js';
import * as api from '../services/api.js';

const auth = useAuthStore();
const ui   = useUiStore();

const form    = reactive({ nickname: '' });
const saving  = ref(false);
const pwdVisible = ref(false);
const pwdSaving  = ref(false);
const pwd = reactive({ new: '', confirm: '', emailCode: '' });
const codeSending = ref(false);
const codeCountdown = ref(0);
let codeTimer = null;

const canSendCode = computed(() => isValidEmail(auth.user?.email));
const emailDisplay = computed(() => auth.user?.email || '未绑定邮箱');
const codeButtonText = computed(() => {
  if (codeSending.value) return '发送中';
  if (codeCountdown.value > 0) return `${codeCountdown.value}s`;
  return '发送验证码';
});

const rolesLabel = computed(() => {
  const roles = auth.user?.roles || [];
  if (roles.includes('ROLE_ADMIN')) return '管理员';
  return '普通用户';
});

onMounted(async () => {
  await auth.refreshProfile();
  form.nickname = auth.user?.nickname || '';
});

async function saveProfile() {
  saving.value = true;
  try {
    await auth.updateProfile(form.nickname);
    ui.showToast('success', '资料已保存');
  } catch (err) {
    ui.showToast('error', err.message || '保存失败');
  } finally {
    saving.value = false;
  }
}

async function changePassword() {
  if (!pwd.new) { ui.showToast('warning', '请填写新密码'); return; }
  if (!/^\d{6}$/.test(pwd.emailCode || '')) { ui.showToast('warning', '请输入 6 位邮箱验证码'); return; }
  if (pwd.new !== pwd.confirm) { ui.showToast('warning', '两次输入的新密码不一致'); return; }
  if (pwd.new.length < 6) { ui.showToast('warning', '新密码长度不能少于 6 位'); return; }
  pwdSaving.value = true;
  try {
    await api.changePassword(pwd.new, pwd.emailCode);
    ui.showToast('success', '密码修改成功，请重新登录');
    setTimeout(() => auth.logout(), 1500);
  } catch (err) {
    ui.showToast('error', err.message || '修改失败');
  } finally {
    pwdSaving.value = false;
  }
}

async function sendChangePasswordCode() {
  if (!isValidEmail(auth.user?.email)) {
    ui.showToast('warning', '当前账号未绑定有效邮箱');
    return;
  }
  codeSending.value = true;
  try {
    await api.sendEmailCode(auth.user.email, 'change_password');
    startCodeCountdown();
    ui.showToast('success', '验证码已发送');
  } catch (err) {
    ui.showToast('error', err.message || '验证码发送失败');
  } finally {
    codeSending.value = false;
  }
}

function startCodeCountdown() {
  if (codeTimer) clearInterval(codeTimer);
  codeCountdown.value = 60;
  codeTimer = setInterval(() => {
    codeCountdown.value -= 1;
    if (codeCountdown.value <= 0) {
      clearInterval(codeTimer);
      codeTimer = null;
      codeCountdown.value = 0;
    }
  }, 1000);
}

function isValidEmail(email) {
  return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email || '');
}

onUnmounted(() => {
  if (codeTimer) clearInterval(codeTimer);
});
</script>

<style scoped>
@import '../css/views/profile-view.css';
</style>
