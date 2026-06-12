<template>
  <div class="app-layout">
    <Sidebar :collapsed="sidebarCollapsed" @toggle="sidebarCollapsed = !sidebarCollapsed" />
    <main class="main" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
      <!-- 顶栏：P3-18 侧边栏收起时显示当前页名称 -->
      <div class="topbar">
        <div class="topbar-left">
          <span v-if="sidebarCollapsed" class="topbar-page-title">{{ currentPageTitle }}</span>
          <span v-else class="topbar-title">{{ sess.currentSessionTitle }}</span>
        </div>
        <div class="topbar-actions">
          <router-link class="topbar-btn" to="/chat">对话</router-link>
          <router-link class="topbar-btn" to="/kb">知识库</router-link>
          <router-link class="topbar-btn" to="/org">组织</router-link>
          <template v-if="auth.isAdmin">
            <router-link class="topbar-btn" to="/monitor">监控</router-link>
            <router-link class="topbar-btn" to="/admin">用户管理</router-link>
          </template>
        </div>
      </div>

      <!-- 页面内容区 -->
      <RouterView />
    </main>

    <!-- 全局 Toast + Dialog -->
    <Toast />
    <Dialog />
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { RouterView, useRoute } from 'vue-router';
import Sidebar from './Sidebar.vue';
import Toast from '../ui/Toast.vue';
import Dialog from '../ui/Dialog.vue';
import { useAuthStore } from '../../stores/auth.js';
import { useSessionStore } from '../../stores/sessions.js';
import { useKbStore } from '../../stores/kb.js';
import { useOrgStore } from '../../stores/org.js';
import { setupCopyCodeHandler } from '../../js/utils.js';

const auth  = useAuthStore();
const sess  = useSessionStore();
const kb    = useKbStore();
const org   = useOrgStore();
const route = useRoute();

const sidebarCollapsed = ref(false);

// P3-18：侧边栏收起时显示当前页标题
const currentPageTitle = computed(() => route.meta?.title || sess.currentSessionTitle || '');

onMounted(async () => {
  // 初始化数据
  await auth.refreshProfile();
  await sess.init();
  await org.loadOrgs();
  await kb.loadKbs(org.currentOrgId);

  // 全局代码块复制处理
  setupCopyCodeHandler();
});

// P2-13：组织切换时自动刷新知识库
watch(() => org.currentOrgId, (newOrgId) => {
  if (newOrgId) kb.loadKbs(newOrgId);
});
</script>
