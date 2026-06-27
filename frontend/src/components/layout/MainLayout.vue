<template>
  <div class="app-layout">
    <Sidebar :collapsed="sidebarCollapsed" @toggle="toggleSidebar" @close="closeSidebar" />
    <button
      v-if="isMobile && !sidebarCollapsed"
      class="sidebar-drawer-backdrop"
      type="button"
      aria-label="关闭历史会话"
      @click="closeSidebar"
    ></button>
    <main class="main" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
      <!-- 顶栏：P3-18 侧边栏收起时显示当前页名称 -->
      <div class="topbar">
        <div class="topbar-left">
          <button
            v-if="isMobile"
            class="mobile-sidebar-btn"
            type="button"
            title="打开历史会话"
            aria-label="打开历史会话"
            @click="openSidebar"
          >
            <svg viewBox="0 0 24 24" fill="none" width="22" height="22">
              <path d="M4 7h16M4 12h16M4 17h16" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            </svg>
          </button>
          <span v-if="isMobile || sidebarCollapsed" class="topbar-page-title">{{ currentPageTitle }}</span>
          <span v-else class="topbar-title">{{ sess.currentSessionTitle }}</span>
        </div>
        <div class="topbar-actions">
          <WorkspaceSwitcher />
          <!-- 对话页专属操作按钮 -->
          <template v-if="route.path === '/chat'">
            <button class="topbar-btn topbar-share-btn" type="button" @click="openShareDialog" title="分享当前会话快照">
              <svg viewBox="0 0 24 24" fill="none" width="13" height="13">
                <path d="M4 12v7a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-7" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                <path d="M16 6 12 2 8 6M12 2v13" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              分享
            </button>
          </template>
          <router-link class="topbar-btn topbar-chat-link" to="/chat">对话</router-link>
          <router-link class="topbar-btn" to="/creation">创作</router-link>
          <!-- Fix 14: 有待处理通知时显示红点角标 -->
          <router-link class="topbar-btn topbar-org-link topbar-btn-notice" to="/org">
            组织设置
            <span v-if="org.pendingNoticeCount > 0" class="topbar-notice-badge">{{ org.pendingNoticeCount }}</span>
          </router-link>
          <router-link class="topbar-btn topbar-kb-link" to="/kb">知识库</router-link>
          <template v-if="auth.isAdmin">
            <router-link class="topbar-btn topbar-admin-link" to="/admin/dashboard">管理后台</router-link>
          </template>
        </div>
      </div>

      <!-- 页面内容区 -->
      <RouterView />
    </main>

    <!-- 全局 Toast + Dialog -->
    <Toast />
    <Dialog />

    <Teleport to="body">
      <div v-if="shareDialogVisible" class="modal-overlay share-dialog-overlay" @click.self="closeShareDialog">
        <div class="share-dialog">
          <div class="share-dialog-header">
            <div>
              <h3>分享会话</h3>
              <p>创建当前内容的只读快照，不会同步后续消息。</p>
            </div>
            <button class="modal-close" type="button" title="关闭" @click="closeShareDialog">×</button>
          </div>

          <div class="share-boundary">
            <span>仅包含当前对话文本</span>
            <span>默认 7 天后失效</span>
            <span>不分享知识库、组织或附件信息</span>
          </div>

          <button
            v-if="!shareInfo"
            class="share-primary-btn"
            type="button"
            :disabled="shareLoading"
            @click="createShare"
          >
            {{ shareLoading ? '创建中…' : '创建分享链接' }}
          </button>

          <div v-else class="share-link-panel">
            <label>分享链接</label>
            <div class="share-link-row">
              <input :value="shareInfo.url" readonly />
              <button type="button" @click="copyShareLink">复制</button>
            </div>
            <p v-if="shareInfo.expiresAt" class="share-expire">
              有效期至 {{ formatShareTime(shareInfo.expiresAt) }}
            </p>
            <button class="share-revoke-btn" type="button" @click="revokeShare">撤销这个链接</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { RouterView, useRoute } from 'vue-router';
import Sidebar from './Sidebar.vue';
import WorkspaceSwitcher from './WorkspaceSwitcher.vue';
import Toast from '../ui/Toast.vue';
import Dialog from '../ui/Dialog.vue';
import { useAuthStore } from '../../stores/auth.js';
import { useSessionStore } from '../../stores/sessions.js';
import { useKbStore } from '../../stores/kb.js';
import { useOrgStore } from '../../stores/org.js';
import { useUiStore } from '../../stores/ui.js';
import { copyText, setupCopyCodeHandler } from '../../js/utils.js';

const auth  = useAuthStore();
const sess  = useSessionStore();
const kb    = useKbStore();
const org   = useOrgStore();
const ui    = useUiStore();
const route = useRoute();

const initialMobile = window.matchMedia('(max-width: 860px)').matches;
const isMobile = ref(initialMobile);
const sidebarCollapsed = ref(initialMobile);
const shareDialogVisible = ref(false);
const shareLoading = ref(false);
const shareInfo = ref(null);
let mobileMediaQuery = null;

// P3-18：侧边栏收起时显示当前页标题
const currentPageTitle = computed(() => route.meta?.title || sess.currentSessionTitle || '');

let _noticeTimer = null;

onMounted(async () => {
  setupResponsiveSidebar();

  // 初始化数据
  await auth.refreshProfile();
  await org.loadOrgs();
  await sess.init();
  await syncKnowledgeBasesForOrg(org.currentOrgId);

  // 全局代码块复制处理
  setupCopyCodeHandler(() => ui.showToast('warning', '复制失败，请手动复制'));

  // Fix 14: 每 60 秒刷新一次通知计数（loadOrgs 内已调用过一次，这里是后续轮询）
  _noticeTimer = setInterval(() => org.refreshNoticeCount(), 60_000);
});

onBeforeUnmount(() => {
  if (_noticeTimer) clearInterval(_noticeTimer);
  if (mobileMediaQuery?.removeEventListener) {
    mobileMediaQuery.removeEventListener('change', handleViewportChange);
  } else {
    mobileMediaQuery?.removeListener?.(handleViewportChange);
  }
});

// P2-13：组织切换时自动刷新知识库
watch(() => org.currentOrgId, (newOrgId) => {
  syncKnowledgeBasesForOrg(newOrgId);
});

async function syncKnowledgeBasesForOrg(orgId) {
  if (!orgId) {
    kb.resetSelection();
    sess.clearCurrentKb();
    return;
  }

  await kb.loadKbs(orgId, { reset: true });
  if (org.currentOrgId !== orgId) return;

  if (!sess.currentKbId) return;
  if (sess.currentKbOrgId !== orgId) {
    sess.clearCurrentKb();
    return;
  }

  const activeKbExists = kb.knowledgeBases.some(item => item.id === sess.currentKbId);
  if (!activeKbExists) {
    sess.clearCurrentKb();
    return;
  }

  if (kb.currentKbId !== sess.currentKbId) {
    await kb.selectKb(sess.currentKbId, orgId);
  }
}

watch(() => route.fullPath, (path) => {
  applyRouteSidebarPreference(path);
});

function setupResponsiveSidebar() {
  mobileMediaQuery = window.matchMedia('(max-width: 860px)');
  handleViewportChange(mobileMediaQuery);
  if (mobileMediaQuery.addEventListener) {
    mobileMediaQuery.addEventListener('change', handleViewportChange);
  } else {
    mobileMediaQuery.addListener?.(handleViewportChange);
  }
}

function handleViewportChange(event) {
  isMobile.value = event.matches;
  sidebarCollapsed.value = event.matches;
  applyRouteSidebarPreference(route.fullPath);
}

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value;
}

function openSidebar() {
  sidebarCollapsed.value = false;
}

function closeSidebar() {
  if (isMobile.value) sidebarCollapsed.value = true;
}

function applyRouteSidebarPreference(path) {
  if (String(path || '').startsWith('/creation')) {
    sidebarCollapsed.value = true;
    return;
  }
  if (isMobile.value) closeSidebar();
}

function openShareDialog() {
  shareInfo.value = null;
  shareDialogVisible.value = true;
}

function closeShareDialog() {
  shareDialogVisible.value = false;
}

async function createShare() {
  if (shareLoading.value) return;
  shareLoading.value = true;
  try {
    shareInfo.value = await sess.createShareLink();
    if (shareInfo.value?.url) {
      const copied = await copyText(shareInfo.value.url);
      ui.showToast(copied ? 'success' : 'warning', copied ? '分享链接已创建并复制' : '分享链接已创建，复制失败，请手动复制');
    }
  } catch (err) {
    ui.showToast('error', err.message || '创建分享失败');
  } finally {
    shareLoading.value = false;
  }
}

async function copyShareLink() {
  if (!shareInfo.value?.url) return;
  const copied = await copyText(shareInfo.value.url);
  ui.showToast(copied ? 'success' : 'warning', copied ? '分享链接已复制' : '复制失败，请手动复制');
}

async function revokeShare() {
  if (!shareInfo.value?.shareId) return;
  try {
    await sess.revokeShare(shareInfo.value.shareId);
    shareInfo.value = null;
    shareDialogVisible.value = false;
  } catch (err) {
    ui.showToast('error', err.message || '撤销失败');
  }
}

function formatShareTime(value) {
  const d = new Date(value);
  return d.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}
</script>
