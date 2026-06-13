<template>
  <div class="page-wrapper" :class="{ 'compact-auth': activeTab !== 'login' }">
    <aside class="brand-panel">
      <div class="brand-inner">
        <div class="brand-top">
          <div class="brand-logo">
            <div class="brand-logo-icon"><LogoMark /></div>
            <span class="brand-logo-text">AI Agent</span>
          </div>
          <div class="brand-headline">企业级<br><span>智能对话</span>平台</div>
          <p class="brand-desc">基于大语言模型的企业知识库与智能助手，支持文档问答、多步推理与业务系统集成。</p>
        </div>
        <div class="brand-features">
          <div v-for="item in features" :key="item" class="brand-feature">
            <div class="brand-feature-dot"></div>
            {{ item }}
          </div>
        </div>
      </div>
      <div class="brand-bottom">© 2026 AI Agent Platform</div>
    </aside>

    <main class="form-panel">
      <section class="mobile-brand">
        <div class="mobile-brand-logo">
          <div class="mobile-brand-logo-icon"><LogoMark /></div>
          <span class="mobile-brand-logo-text">AI Agent</span>
        </div>
        <div class="mobile-brand-headline">企业级<span>智能对话</span>平台</div>
        <p class="mobile-brand-desc">文档问答 · 多步推理 · 业务系统集成</p>
      </section>

      <section class="form-box">
        <div class="form-title">{{ formTitle }}</div>
        <div class="form-subtitle">{{ formSubtitle }}</div>

        <div v-if="globalSuccess" class="global-success visible">{{ globalSuccess }}</div>
        <div v-else-if="globalError" class="global-error visible">{{ globalError }}</div>

        <form v-if="activeTab === 'login'" class="auth-form active" novalidate @submit.prevent="submitLogin">
          <div class="form-group">
            <label class="form-label" for="loginUsername">用户名</label>
            <input
              id="loginUsername"
              v-model.trim="loginForm.username"
              class="form-input"
              :class="{ error: fieldErrors.loginUsername }"
              type="text"
              placeholder="输入用户名"
              autocomplete="username"
            >
            <div v-if="fieldErrors.loginUsername" class="form-error visible">{{ fieldErrors.loginUsername }}</div>
          </div>
          <div class="form-group">
            <div class="label-row">
              <label class="form-label" for="loginPassword">密码</label>
              <button class="forgot-link" type="button" @click="switchTab('forgot')">忘记密码？</button>
            </div>
            <input
              id="loginPassword"
              v-model="loginForm.password"
              class="form-input"
              :class="{ error: fieldErrors.loginPassword }"
              type="password"
              placeholder="输入密码"
              autocomplete="current-password"
            >
            <div v-if="fieldErrors.loginPassword" class="form-error visible">{{ fieldErrors.loginPassword }}</div>
          </div>
          <button class="submit-btn" type="submit" :disabled="loading">
            <span v-if="loading" class="spinner"></span>{{ loading ? '登录中' : '登录' }}
          </button>
          <div class="form-extra">
            <span class="muted-text">还没有账号？</span>
            <button class="text-link" type="button" @click="switchTab('register')">去注册</button>
          </div>
        </form>

        <form v-else-if="activeTab === 'register'" class="auth-form active" novalidate @submit.prevent="submitRegister">
          <div class="form-group">
            <label class="form-label" for="regUsername">用户名</label>
            <input
              id="regUsername"
              v-model.trim="registerForm.username"
              class="form-input"
              :class="{ error: fieldErrors.regUsername }"
              type="text"
              placeholder="4-32 位字母或数字"
              autocomplete="username"
            >
            <div v-if="fieldErrors.regUsername" class="form-error visible">{{ fieldErrors.regUsername }}</div>
          </div>
          <div class="form-group">
            <label class="form-label" for="regEmail">邮箱</label>
            <input
              id="regEmail"
              v-model.trim="registerForm.email"
              class="form-input"
              :class="{ error: fieldErrors.regEmail }"
              type="email"
              placeholder="接收验证码"
              autocomplete="email"
            >
            <div v-if="fieldErrors.regEmail" class="form-error visible">{{ fieldErrors.regEmail }}</div>
          </div>
          <div class="form-group">
            <label class="form-label" for="regEmailCode">邮箱验证码</label>
            <div class="verification-row">
              <input
                id="regEmailCode"
                v-model.trim="registerForm.emailCode"
                class="form-input verification-input"
                :class="{ error: fieldErrors.regEmailCode }"
                type="text"
                inputmode="numeric"
                maxlength="6"
                placeholder="6 位验证码"
                autocomplete="one-time-code"
              >
              <button class="code-btn" type="button" :disabled="!canSendCode || codeSending || codeCountdown > 0" @click="sendCode">
                <span v-if="codeSending" class="spinner code-spinner"></span>
                {{ codeButtonText }}
              </button>
            </div>
            <div v-if="fieldErrors.regEmailCode" class="form-error visible">{{ fieldErrors.regEmailCode }}</div>
          </div>
          <div class="form-group">
            <label class="form-label" for="regPassword">密码</label>
            <input
              id="regPassword"
              v-model="registerForm.password"
              class="form-input"
              :class="{ error: fieldErrors.regPassword }"
              type="password"
              placeholder="至少 6 位"
              autocomplete="new-password"
            >
            <div v-if="fieldErrors.regPassword" class="form-error visible">{{ fieldErrors.regPassword }}</div>
          </div>
          <div class="form-group">
            <label class="form-label" for="regConfirm">确认密码</label>
            <input
              id="regConfirm"
              v-model="registerForm.confirm"
              class="form-input"
              :class="{ error: fieldErrors.regConfirm }"
              type="password"
              placeholder="再次输入密码"
              autocomplete="new-password"
            >
            <div v-if="fieldErrors.regConfirm" class="form-error visible">{{ fieldErrors.regConfirm }}</div>
          </div>
          <button class="submit-btn" type="submit" :disabled="loading">
            <span v-if="loading" class="spinner"></span>{{ loading ? '注册中' : '注册并登录' }}
          </button>
          <div class="form-extra">
            <span class="muted-text">已有账号？</span>
            <button class="text-link" type="button" @click="switchTab('login')">返回登录</button>
          </div>
        </form>

        <form v-else class="auth-form active" novalidate @submit.prevent="submitForgot">
          <div class="form-group">
            <label class="form-label" for="forgotEmail">注册邮箱</label>
            <input
              id="forgotEmail"
              v-model.trim="forgotForm.email"
              class="form-input"
              :class="{ error: fieldErrors.forgotEmail }"
              type="email"
              placeholder="输入注册邮箱"
              autocomplete="email"
            >
            <div v-if="fieldErrors.forgotEmail" class="form-error visible">{{ fieldErrors.forgotEmail }}</div>
          </div>
          <div class="form-group">
            <label class="form-label" for="forgotEmailCode">邮箱验证码</label>
            <div class="verification-row">
              <input
                id="forgotEmailCode"
                v-model.trim="forgotForm.emailCode"
                class="form-input verification-input"
                :class="{ error: fieldErrors.forgotEmailCode }"
                type="text"
                inputmode="numeric"
                maxlength="6"
                placeholder="6 位验证码"
                autocomplete="one-time-code"
              >
              <button class="code-btn" type="button" :disabled="!canSendForgotCode || codeSending || codeCountdown > 0" @click="sendForgotCode">
                <span v-if="codeSending" class="spinner code-spinner"></span>
                {{ codeButtonText }}
              </button>
            </div>
            <div v-if="fieldErrors.forgotEmailCode" class="form-error visible">{{ fieldErrors.forgotEmailCode }}</div>
          </div>
          <div class="form-group">
            <label class="form-label" for="forgotPassword">新密码</label>
            <input
              id="forgotPassword"
              v-model="forgotForm.password"
              class="form-input"
              :class="{ error: fieldErrors.forgotPassword }"
              type="password"
              placeholder="至少 6 位"
              autocomplete="new-password"
            >
            <div v-if="fieldErrors.forgotPassword" class="form-error visible">{{ fieldErrors.forgotPassword }}</div>
          </div>
          <div class="form-group">
            <label class="form-label" for="forgotConfirm">确认新密码</label>
            <input
              id="forgotConfirm"
              v-model="forgotForm.confirm"
              class="form-input"
              :class="{ error: fieldErrors.forgotConfirm }"
              type="password"
              placeholder="再次输入新密码"
              autocomplete="new-password"
            >
            <div v-if="fieldErrors.forgotConfirm" class="form-error visible">{{ fieldErrors.forgotConfirm }}</div>
          </div>
          <button class="submit-btn" type="submit" :disabled="loading">
            <span v-if="loading" class="spinner"></span>{{ loading ? '重置中' : '重置密码' }}
          </button>
          <div class="form-extra">
            <button class="text-link" type="button" @click="switchTab('login')">想起密码？返回登录</button>
          </div>
        </form>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, onMounted, onUnmounted, reactive, ref } from 'vue';
import { getToken, login, register, sendEmailCode, forgotPassword, resetPassword } from './services/api.js';

const LogoMark = defineComponent({
  setup: () => () => h('svg', { viewBox: '0 0 32 32', fill: 'currentColor' }, [
    h('path', { d: 'M27.6 11.8c-1.8.2-3.4-.2-4.8-1.1-1.9-1.3-3-3.3-3.5-5.9-.1-.6-.8-.9-1.3-.5-2.5 1.7-4 4-4.4 6.9-2.2-1.2-4.9-1.5-8-.9-.6.1-.9.8-.6 1.3 1.4 2.6 3.3 4.6 5.7 5.9-1.2.8-2.5 1.1-3.9 1.1-.7 0-1.1.8-.7 1.4 2 3.3 5.4 5.2 9.7 5.2 6.1 0 10.7-3.8 11.6-9.2.6-.6 1.1-1.4 1.5-2.3.4-.9-.2-2-1.3-1.9Zm-8 6.6c-1.9 1.6-4.5 1.8-6.5.4 1.7-.4 3-1.2 4-2.5 1.4.7 3 .9 4.7.6-.5.6-1.2 1.1-2.2 1.5Z' }),
  ]),
});

const features = [
  '混合 RAG 知识库，精准引用溯源',
  'ReAct 多步推理，复杂任务自动拆解',
  'DeepSeek / Claude 多模型热切换',
  'Token 成本追踪与智能告警',
];

const activeTab   = ref('login');
const loading     = ref(false);
const globalError = ref('');
const globalSuccess = ref('');
const fieldErrors = reactive({});
const loginForm   = reactive({ username: '', password: '' });
const registerForm = reactive({ username: '', email: '', emailCode: '', password: '', confirm: '' });
const forgotForm   = reactive({ email: '', emailCode: '', password: '', confirm: '' });
const codeSending = ref(false);
const codeCountdown = ref(0);
let codeTimer = null;

const formTitle = computed(() => {
  switch (activeTab.value) {
    case 'register': return '创建账号';
    case 'forgot':   return '找回密码';
    default:         return '欢迎回来';
  }
});

const formSubtitle = computed(() => {
  switch (activeTab.value) {
    case 'register': return '注册后即可开始使用';
    case 'forgot':   return '通过邮箱验证码重置密码';
    default:         return '登录以继续使用 AI Agent';
  }
});

const canSendCode = computed(() => isValidEmail(registerForm.email));
const canSendForgotCode = computed(() => isValidEmail(forgotForm.email));
const codeButtonText = computed(() => {
  if (codeSending.value) return '发送中';
  if (codeCountdown.value > 0) return `${codeCountdown.value}s`;
  return '发送验证码';
});

onMounted(() => {
  if (getToken()) location.replace(getRedirectTarget());
});

onUnmounted(() => {
  if (codeTimer) clearInterval(codeTimer);
});

function switchTab(tab) {
  activeTab.value = tab;
  clearErrors();
  globalSuccess.value = '';
}

async function submitLogin() {
  clearErrors();
  if (!validateLogin()) return;
  loading.value = true;
  try {
    await login({ username: loginForm.username, password: loginForm.password });
    location.replace(getRedirectTarget());
  } catch (error) {
    globalError.value = error.message || '用户名或密码错误';
  } finally {
    loading.value = false;
  }
}

function getRedirectTarget() {
  const redirect = new URLSearchParams(location.search).get('redirect');
  if (redirect && redirect.startsWith('/') && !redirect.startsWith('//')) {
    return redirect;
  }
  return '/index.html';
}

async function submitRegister() {
  clearErrors();
  if (!validateRegister()) return;
  loading.value = true;
  try {
    await register({
      username: registerForm.username,
      password: registerForm.password,
      email: registerForm.email,
      emailCode: registerForm.emailCode,
    });
    location.replace('/index.html');
  } catch (error) {
    globalError.value = error.message || '注册失败，用户名可能已存在';
  } finally {
    loading.value = false;
  }
}

async function submitForgot() {
  clearErrors();
  if (!validateForgot()) return;
  loading.value = true;
  try {
    await resetPassword(forgotForm.email, forgotForm.emailCode, forgotForm.password);
    forgotForm.email = '';
    forgotForm.emailCode = '';
    forgotForm.password = '';
    forgotForm.confirm = '';
    switchTab('login');
    globalSuccess.value = '密码重置成功，请使用新密码登录';
  } catch (error) {
    globalError.value = error.message || '重置失败，请检查验证码';
  } finally {
    loading.value = false;
  }
}

async function sendCode() {
  globalError.value = '';
  globalSuccess.value = '';
  delete fieldErrors.regEmail;
  if (!isValidEmail(registerForm.email)) {
    fieldErrors.regEmail = '请输入正确的邮箱地址';
    return;
  }
  codeSending.value = true;
  try {
    await sendEmailCode(registerForm.email, 'register');
    globalSuccess.value = '验证码已发送至邮箱，请查收';
    startCodeCountdown();
  } catch (error) {
    globalError.value = error.message || '验证码发送失败，请稍后重试';
  } finally {
    codeSending.value = false;
  }
}

async function sendForgotCode() {
  globalError.value = '';
  globalSuccess.value = '';
  delete fieldErrors.forgotEmail;
  if (!isValidEmail(forgotForm.email)) {
    fieldErrors.forgotEmail = '请输入正确的邮箱地址';
    return;
  }
  codeSending.value = true;
  try {
    await forgotPassword(forgotForm.email);
    globalSuccess.value = '验证码已发送至邮箱，请查收';
    startCodeCountdown();
  } catch (error) {
    globalError.value = error.message || '验证码发送失败，请稍后重试';
  } finally {
    codeSending.value = false;
  }
}

function validateLogin() {
  if (!loginForm.username) fieldErrors.loginUsername = '请输入用户名';
  if (!loginForm.password) fieldErrors.loginPassword = '请输入密码';
  return !Object.keys(fieldErrors).length;
}

function validateRegister() {
  if (!registerForm.username || !/^[a-zA-Z0-9_-]{3,32}$/.test(registerForm.username)) {
    fieldErrors.regUsername = '用户名需为 3-32 位字母、数字、下划线或连字符';
  }
  if (!isValidEmail(registerForm.email)) {
    fieldErrors.regEmail = '请输入正确的邮箱地址';
  }
  if (!/^\d{6}$/.test(registerForm.emailCode || '')) {
    fieldErrors.regEmailCode = '请输入 6 位邮箱验证码';
  }
  if (!registerForm.password || registerForm.password.length < 6) {
    fieldErrors.regPassword = '密码不能少于 6 位';
  }
  if (registerForm.password !== registerForm.confirm) {
    fieldErrors.regConfirm = '两次密码不一致';
  }
  return !Object.keys(fieldErrors).length;
}

function validateForgot() {
  if (!isValidEmail(forgotForm.email)) {
    fieldErrors.forgotEmail = '请输入正确的邮箱地址';
  }
  if (!/^\d{6}$/.test(forgotForm.emailCode || '')) {
    fieldErrors.forgotEmailCode = '请输入 6 位邮箱验证码';
  }
  if (!forgotForm.password || forgotForm.password.length < 6) {
    fieldErrors.forgotPassword = '密码不能少于 6 位';
  }
  if (forgotForm.password !== forgotForm.confirm) {
    fieldErrors.forgotConfirm = '两次密码不一致';
  }
  return !Object.keys(fieldErrors).length;
}

function clearErrors() {
  globalError.value = '';
  globalSuccess.value = '';
  Object.keys(fieldErrors).forEach(k => delete fieldErrors[k]);
}

function isValidEmail(email) {
  return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email || '');
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
</script>
