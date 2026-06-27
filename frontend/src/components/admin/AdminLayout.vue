<template>
  <div class="admin-shell">
    <aside class="admin-sidebar">
      <div class="admin-brand">
        <div class="admin-brand-mark">AI</div>
        <div>
          <strong>Admin Console</strong>
          <span>平台管理</span>
        </div>
      </div>

      <nav class="admin-nav">
        <router-link v-for="item in navItems" :key="item.to" :to="item.to" class="admin-nav-item">
          <span class="admin-nav-icon">{{ item.icon }}</span>
          <span>{{ item.label }}</span>
        </router-link>
      </nav>
    </aside>

    <section class="admin-main">
      <header class="admin-topbar">
        <div>
          <h1>{{ route.meta.title || '管理后台' }}</h1>
          <p>{{ pageHint }}</p>
        </div>
        <div class="admin-topbar-actions">
          <router-link class="admin-outline-btn" to="/chat">返回业务端</router-link>
          <div class="admin-user-chip">{{ auth.displayName || '管理员' }}</div>
        </div>
      </header>

      <main class="admin-content">
        <RouterView />
      </main>
    </section>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { RouterView, useRoute } from 'vue-router';
import { useAuthStore } from '../../stores/auth.js';

const route = useRoute();
const auth = useAuthStore();

const navItems = [
  { to: '/admin/dashboard', label: '总览', icon: '□' },
  { to: '/admin/users', label: '用户', icon: '◎' },
  { to: '/admin/kbs', label: '知识库', icon: '▣' },
  { to: '/admin/documents', label: '文档', icon: '≡' },
  { to: '/admin/usage', label: '用量', icon: '◇' },
];

const hints = {
  '/admin/dashboard': '成本、错误、文档和任务的全局健康概览',
  '/admin/users': '账号状态、角色和用户级用量',
  '/admin/kbs': '跨组织查看知识库资产',
  '/admin/documents': '追踪文档解析状态和失败原因',
  '/admin/usage': '模型、用户和每日成本报表',
};

const pageHint = computed(() => hints[route.path] || '平台级只读治理视图');
</script>
