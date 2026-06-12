<template>
  <div class="app-layout">
    <Sidebar :collapsed="sidebarCollapsed" @toggle="sidebarCollapsed = !sidebarCollapsed" />
    <main class="main" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
      <!-- 顶栏 -->
      <div class="topbar">
        <div class="topbar-title">{{ sess.currentSessionTitle }}</div>
        <div class="topbar-actions">
          <router-link class="topbar-btn" to="/chat">对话</router-link>
          <router-link class="topbar-btn" to="/kb">知识库</router-link>
          <router-link class="topbar-btn" to="/org">组织</router-link>
          <router-link v-if="auth.isAdmin" class="topbar-btn" to="/monitor">监控</router-link>
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
import { onMounted, ref } from 'vue';
import { RouterView } from 'vue-router';
import Sidebar from './Sidebar.vue';
import Toast from '../ui/Toast.vue';
import Dialog from '../ui/Dialog.vue';
import { useAuthStore } from '../../stores/auth.js';
import { useSessionStore } from '../../stores/sessions.js';
import { useKbStore } from '../../stores/kb.js';
import { useOrgStore } from '../../stores/org.js';
import { setupCopyCodeHandler } from '../../js/utils.js';

const auth = useAuthStore();
const sess = useSessionStore();
const kb   = useKbStore();
const org  = useOrgStore();

const sidebarCollapsed = ref(false);

onMounted(async () => {
  // 初始化数据
  await auth.refreshProfile();
  await sess.init();
  await org.loadOrgs();
  await kb.loadKbs(org.currentOrgId);

  // 全局代码块复制处理
  setupCopyCodeHandler();
});
</script>
