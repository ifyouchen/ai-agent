<template>
  <div class="page-wrapper">
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
        <div class="form-title">{{ activeTab === 'login' ? '欢迎回来' : '创建账号' }}</div>
        <div class="form-subtitle">{{ activeTab === 'login' ? '登录以继续使用 AI Agent' : '注册后即可开始使用' }}</div>

        <div class="auth-tabs">
          <button class="auth-tab" :class="{ active: activeTab === 'login' }" type="button" @click="switchTab('login')">登录</button>
          <button class="auth-tab" :class="{ active: activeTab === 'register' }" type="button" @click="switchTab('register')">注册</button>
        </div>

        <div v-if="globalError" class="global-error visible">{{ globalError }}</div>

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
            <label class="form-label" for="loginPassword">密码</label>
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
        </form>

        <form v-else class="auth-form active" novalidate @submit.prevent="submitRegister">
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
        </form>
      </section>
    </main>
  </div>
</template>

<script setup>
// P3-16：合并 LoginPage.js 逻辑，移除外部 script 引用
import { defineComponent, h, onMounted, reactive, ref } from 'vue';
import { getToken, login, register } from './services/api.js';

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
const fieldErrors = reactive({});
const loginForm   = reactive({ username: '', password: '' });
const registerForm = reactive({ username: '', password: '', confirm: '' });

onMounted(() => {
  if (getToken()) location.replace('/index.html');
});

function switchTab(tab) {
  activeTab.value = tab;
  clearErrors();
}

async function submitLogin() {
  clearErrors();
  if (!validateLogin()) return;
  loading.value = true;
  try {
    await login({ username: loginForm.username, password: loginForm.password });
    location.replace('/index.html');
  } catch (error) {
    globalError.value = error.message || '用户名或密码错误';
  } finally {
    loading.value = false;
  }
}

async function submitRegister() {
  clearErrors();
  if (!validateRegister()) return;
  loading.value = true;
  try {
    await register({ username: registerForm.username, password: registerForm.password });
    location.replace('/index.html');
  } catch (error) {
    globalError.value = error.message || '注册失败，用户名可能已存在';
  } finally {
    loading.value = false;
  }
}

function validateLogin() {
  if (!loginForm.username) fieldErrors.loginUsername = '请输入用户名';
  if (!loginForm.password) fieldErrors.loginPassword = '请输入密码';
  return !Object.keys(fieldErrors).length;
}

function validateRegister() {
  if (!registerForm.username || !/^[a-zA-Z0-9]{4,32}$/.test(registerForm.username)) {
    fieldErrors.regUsername = '用户名需为 4-32 位字母或数字';
  }
  if (!registerForm.password || registerForm.password.length < 6) {
    fieldErrors.regPassword = '密码不能少于 6 位';
  }
  if (registerForm.password !== registerForm.confirm) {
    fieldErrors.regConfirm = '两次密码不一致';
  }
  return !Object.keys(fieldErrors).length;
}

function clearErrors() {
  globalError.value = '';
  Object.keys(fieldErrors).forEach(k => delete fieldErrors[k]);
}
</script>

