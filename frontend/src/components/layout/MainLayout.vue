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
          <!-- 对话页专属操作按钮 -->
          <template v-if="route.path === '/chat'">
            <button class="topbar-btn danger" type="button" @click="handleClearMemory">清除记忆</button>
            <button class="topbar-btn" type="button" @click="sess.exportCurrentSession" title="导出对话为 Markdown">
              <svg viewBox="0 0 24 24" fill="none" width="13" height="13">
                <path d="M12 9V21m0-12-4 4m4-4 4 4M2 7l.621-2.485A2 2 0 0 1 4.561 3h14.878a2 2 0 0 1 1.94 1.515L22 7"
                      stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              导出
            </button>
          </template>
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
import { useUiStore } from '../../stores/ui.js';
import { setupCopyCodeHandler } from '../../js/utils.js';
import * as api from '../../services/api.js';

const auth  = useAuthStore();
const sess  = useSessionStore();
const kb    = useKbStore();
const org   = useOrgStore();
const ui    = useUiStore();
const route = useRoute();

const sidebarCollapsed = ref(false);

// P3-18：侧边栏收起时显示当前页标题
const currentPageTitle = computed(() => route.meta?.title || sess.currentSessionTitle || '');

onMounted(async () => {
  // 初始化数据
  await auth.refreshProfile();
  await org.loadOrgs();
  await sess.init();

  // 全局代码块复制处理
  setupCopyCodeHandler();
});

// P2-13：组织切换时自动刷新知识库
watch(() => org.currentOrgId, (newOrgId) => {
  sess.currentKbId = null;
  if (newOrgId) kb.loadKbs(newOrgId, { reset: true });
  else kb.resetSelection();
});

async function handleClearMemory() {
  const confirmed = await ui.showConfirm({
    title: '清除记忆',
    message: `确认清除当前会话的所有记忆？\n清除后对话将重新开始。`,
    confirmText: '清除',
    variant: 'danger',
  });
  if (!confirmed) return;
  try {
    sess.stopSessionGeneration(sess.sessionId, false);
    await api.clearMemory(sess.sessionId);
    sess.sessionMessages[sess.sessionId] = [];
    sess.messages = sess.sessionMessages[sess.sessionId];
    ui.showToast('success', '记忆已清除，对话重新开始');
  } catch {
    ui.showToast('error', '清除失败，请重试');
  }
}
</script>
