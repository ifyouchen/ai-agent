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

<script src="./pages/LoginPage.js"></script>

