<template>
  <aside class="sidebar" :class="{ collapsed: collapsed }">
    <!-- Logo + 工具栏 -->
    <div class="sidebar-logo">
      <h1><span class="logo-icon"><LogoMark /></span>AI Agent</h1>
      <div class="sidebar-tools">
        <button class="icon-btn" type="button" title="搜索" @click="openSearch">
          <svg viewBox="0 0 24 24" fill="none"><path d="m21 21-4.2-4.2m2.2-5.3a7.5 7.5 0 1 1-15 0 7.5 7.5 0 0 1 15 0Z" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
        </button>
        <button class="icon-btn" type="button" title="收起侧边栏" @click="$emit('toggle')">
          <svg viewBox="0 0 24 24" fill="none"><rect x="4" y="5" width="16" height="14" rx="3" stroke="currentColor" stroke-width="2"/><path d="M10 5v14M15 9l-3 3 3 3" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
      </div>
    </div>

    <!-- 新建对话 -->
    <div class="sidebar-section">
      <button class="new-chat-btn" type="button" @click="handleNewSession">
        <svg viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="8" stroke="currentColor" stroke-width="2"/><path d="M12 8v8M8 12h8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
        开启新对话
      </button>
      <div v-if="sess.sessions.length" class="session-bulk-toolbar" :class="{ managing: bulkMode }">
        <template v-if="bulkMode">
          <span class="session-bulk-count">已选 {{ selectedCount }}</span>
          <button class="session-bulk-btn" type="button" @click="toggleSelectAll">
            {{ allVisibleSelected ? '取消全选' : '全选' }}
          </button>
          <button
            class="session-bulk-btn danger"
            type="button"
            :disabled="selectedCount === 0"
            @click="handleDeleteSelected"
          >删除</button>
          <button class="session-bulk-btn danger" type="button" @click="handleDeleteAll">清空</button>
          <button class="session-bulk-close" type="button" title="退出管理" @click="exitBulkMode">
            <svg viewBox="0 0 24 24" fill="none">
              <path d="M6 6l12 12M18 6 6 18" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            </svg>
          </button>
        </template>
        <button v-else class="session-bulk-entry" type="button" @click="enterBulkMode">
          <svg viewBox="0 0 24 24" fill="none"><path d="M8 7h12M8 12h12M8 17h12M4 7h.01M4 12h.01M4 17h.01" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
          管理会话
        </button>
      </div>
    </div>

    <!-- 会话列表 -->
    <div class="session-list">
      <div v-if="sess.sessions.length === 0" class="session-empty">暂无历史对话</div>
      <template v-for="group in groupedSessions" :key="group.label">
        <div v-if="group.items.length" class="sidebar-section-title">{{ group.label }}</div>
        <div
          v-for="session in group.items"
          :key="session.id"
          class="session-item"
          :class="{
            active: session.id === sess.sessionId && !bulkMode,
            generating: sess.sessionRuntime[session.id]?.sending,
            selecting: bulkMode,
            selected: selectedSessionIds.has(session.id)
          }"
          :title="session.title"
          @click="handleSessionClick(session.id)"
        >
          <span v-if="bulkMode" class="session-check" :class="{ checked: selectedSessionIds.has(session.id) }">
            <svg v-if="selectedSessionIds.has(session.id)" viewBox="0 0 24 24" fill="none">
              <path d="m5 12 4 4L19 6" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </span>

          <!-- 标题（双击可编辑） -->
          <span
            v-if="editingSessionId !== session.id"
            class="session-title"
            @dblclick.stop="bulkMode ? null : startEditTitle(session)"
          >{{ session.title }}</span>
          <input
            v-else
            ref="titleInputEl"
            class="session-title-input"
            :value="editingTitle"
            @input="editingTitle = $event.target.value"
            @blur="saveTitle(session.id)"
            @keydown.enter.prevent="saveTitle(session.id)"
            @keydown.esc.prevent="cancelEditTitle"
            @click.stop
          />

          <!-- 生成中动态指示器 -->
          <span v-if="sess.sessionRuntime[session.id]?.sending" class="session-generating">
            <span></span><span></span><span></span>
          </span>
          <button
            v-else-if="editingSessionId !== session.id && !bulkMode"
            class="session-delete"
            type="button"
            title="删除会话"
            @click.stop="sess.removeSession(session.id)"
          >
            <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor">
              <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/>
            </svg>
          </button>
        </div>
      </template>
    </div>

    <!-- 底部：用户信息 + 模型选择 -->
    <div class="sidebar-bottom">
      <div v-if="auth.user" class="user-info" @click.stop="userMenuOpen = !userMenuOpen">
        <Avatar :name="auth.displayName" :size="32" />
        <div class="user-text">
          <div class="user-name">{{ auth.displayName }}</div>
          <div class="user-state">已登录</div>
        </div>
        <svg class="user-menu-arrow" viewBox="0 0 24 24" fill="none" width="14" height="14"
             :style="{ transform: userMenuOpen ? 'rotate(180deg)' : '', transition: 'transform .2s' }">
          <path d="m6 9 6 6 6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        </svg>
        <div v-if="userMenuOpen" class="user-dropdown" @click.stop>
          <router-link class="user-dropdown-item" to="/profile" @click="userMenuOpen = false">
            <svg viewBox="0 0 24 24" fill="none" width="14" height="14">
              <circle cx="12" cy="8" r="4" stroke="currentColor" stroke-width="1.8"/>
              <path d="M4 20c0-4 3.6-7 8-7s8 3 8 7" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
            </svg>
            个人资料
          </router-link>
          <button class="user-dropdown-item" type="button" @click="handleChangePassword">
            <svg viewBox="0 0 24 24" fill="none" width="14" height="14">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z" stroke="currentColor" stroke-width="1.8"/>
              <path d="m9 12 2 2 4-4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
            </svg>
            修改密码
          </button>
          <button class="user-dropdown-item danger" type="button" @click="auth.logout">
            <svg viewBox="0 0 24 24" fill="none" width="14" height="14">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"
                    stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            退出登录
          </button>
        </div>
      </div>

      <!-- 模型选择 -->
      <div class="sidebar-section-title model-title">当前模型</div>
      <div class="model-select" :class="{ open: modelMenuOpen }">
        <button class="model-select-trigger" type="button" @click="modelMenuOpen = !modelMenuOpen">
          <span class="model-dot"></span>
          <span>{{ currentModelLabel }}</span>
          <svg viewBox="0 0 24 24" fill="none"><path d="m6 9 6 6 6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
        <div v-if="modelMenuOpen" class="model-menu">
          <button
            v-for="opt in modelOptions"
            :key="opt.value"
            class="model-option"
            :class="{ active: sess.model === opt.value }"
            type="button"
            @click="selectModel(opt.value)"
          >
            <span class="model-option-main">{{ opt.label }}</span>
            <span class="model-option-desc">{{ opt.desc }}</span>
            <svg v-if="sess.model === opt.value" viewBox="0 0 24 24" fill="none">
              <path d="m5 12 4 4L19 6" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
        </div>
      </div>
    </div>
  </aside>

  <!-- 展开按钮（侧边栏收起时） -->
  <button v-if="collapsed" class="sidebar-expand-btn" type="button" title="展开侧边栏" @click="$emit('toggle')">
    <svg viewBox="0 0 24 24" fill="none"><rect x="4" y="5" width="16" height="14" rx="3" stroke="currentColor" stroke-width="2"/><path d="M10 5v14M13 9l3 3-3 3" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
  </button>

  <!-- 搜索弹窗 -->
  <div v-if="searchVisible" class="search-overlay" @click.self="closeSearch">
    <div class="search-modal">
      <div class="search-bar">
        <svg viewBox="0 0 24 24" fill="none"><path d="m21 21-4.2-4.2m2.2-5.3a7.5 7.5 0 1 1-15 0 7.5 7.5 0 0 1 15 0Z" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
        <input
          ref="searchInputEl"
          v-model.trim="searchQuery"
          type="text"
          placeholder="搜索历史对话"
          @keydown.esc.prevent="closeSearch"
        />
        <button class="search-close" type="button" @click="closeSearch">
          <svg viewBox="0 0 24 24" fill="none"><path d="M6 6l12 12M18 6 6 18" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
        </button>
      </div>
      <div class="search-results">
        <!-- 搜索中提示（P2-9） -->
        <div v-if="searchLoading" class="search-loading">
          <svg class="inline-spinner" viewBox="0 0 24 24" fill="none" width="14" height="14">
            <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
          </svg>
          搜索中…
        </div>
        <template v-else>
          <button
            v-for="session in filteredSessions"
            :key="session.id"
            class="search-result"
            :class="{ active: session.id === sess.sessionId }"
            type="button"
            @click="openSearchResult(session.id)"
          >
            <span class="search-result-icon">
              <svg viewBox="0 0 24 24" fill="none">
                <path d="M8 10h8M8 14h5M6.5 19A7.5 7.5 0 1 1 18 17.7L21 20l-1.3 1.5-3.1-2.3A7.5 7.5 0 0 1 6.5 19Z"
                      stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </span>
            <span class="search-result-main">
              <span class="search-result-title">{{ session.title }}</span>
              <span class="search-result-snippet">{{ snippet(session) }}</span>
            </span>
            <span class="search-result-date">{{ formatDate(session.createdAt) }}</span>
          </button>
          <div v-if="filteredSessions.length === 0" class="search-empty">
            <div class="search-empty-icon">
              <svg viewBox="0 0 24 24" fill="none"><path d="m21 21-4.2-4.2m2.2-5.3a7.5 7.5 0 1 1-15 0 7.5 7.5 0 0 1 15 0Z" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
            </div>
            <div>没有找到相关对话</div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import Avatar from '../ui/Avatar.vue';
import { useAuthStore } from '../../stores/auth.js';
import { useSessionStore } from '../../stores/sessions.js';
import { useUiStore } from '../../stores/ui.js';

const props = defineProps({ collapsed: { type: Boolean, default: false } });
defineEmits(['toggle']);

const router = useRouter();
const auth = useAuthStore();
const sess = useSessionStore();
const ui   = useUiStore();

// ── Logo SVG 内联组件 ──────────────────────────────────────────────
const LogoMark = defineComponent({
  setup: () => () => h('svg', { viewBox: '0 0 32 32', fill: 'currentColor' }, [
    h('path', { d: 'M27.6 11.8c-1.8.2-3.4-.2-4.8-1.1-1.9-1.3-3-3.3-3.5-5.9-.1-.6-.8-.9-1.3-.5-2.5 1.7-4 4-4.4 6.9-2.2-1.2-4.9-1.5-8-.9-.6.1-.9.8-.6 1.3 1.4 2.6 3.3 4.6 5.7 5.9-1.2.8-2.5 1.1-3.9 1.1-.7 0-1.1.8-.7 1.4 2 3.3 5.4 5.2 9.7 5.2 6.1 0 10.7-3.8 11.6-9.2.6-.6 1.1-1.4 1.5-2.3.4-.9-.2-2-1.3-1.9Zm-8 6.6c-1.9 1.6-4.5 1.8-6.5.4 1.7-.4 3-1.2 4-2.5 1.4.7 3 .9 4.7.6-.5.6-1.2 1.1-2.2 1.5Z' }),
  ]),
});

// ── 用户下拉菜单 ────────────────────────────────────────────────────
const userMenuOpen = ref(false);

function closeUserMenuOnOutsideClick() {
  userMenuOpen.value = false;
}

function handleGlobalKeydown(event) {
  if (event.key !== 'Escape') return;
  userMenuOpen.value = false;
  modelMenuOpen.value = false;
  exitBulkMode();
}

onMounted(() => {
  document.addEventListener('click', closeUserMenuOnOutsideClick);
  document.addEventListener('keydown', handleGlobalKeydown);
});

onBeforeUnmount(() => {
  document.removeEventListener('click', closeUserMenuOnOutsideClick);
  document.removeEventListener('keydown', handleGlobalKeydown);
});

async function handleChangePassword() {
  userMenuOpen.value = false;
  const form = await ui.showForm({
    title: '修改密码',
    confirmText: '保存',
    fields: [
      { key: 'oldPassword', label: '当前密码', type: 'password', placeholder: '输入当前密码' },
      { key: 'newPassword', label: '新密码',   type: 'password', placeholder: '至少 6 位' },
    ],
  });
  if (!form || !form.oldPassword || !form.newPassword) return;
  try {
    const { changePassword } = await import('../../services/api.js');
    await changePassword(form.oldPassword, form.newPassword);
    ui.showToast('success', '密码修改成功，请重新登录');
    setTimeout(() => auth.logout(), 1500);
  } catch (err) {
    ui.showToast('error', err.message || '修改失败');
  }
}

// ── 模型选择 ────────────────────────────────────────────────────────
const modelMenuOpen = ref(false);
const modelOptions = [
  { value: 'deepseek', label: 'DeepSeek Chat', desc: '高性价比，适合大多数任务' },
  { value: 'claude',   label: 'Claude',        desc: '复杂推理，代码分析更强' },
];
const currentModelLabel = computed(() =>
  modelOptions.find(o => o.value === sess.model)?.label || sess.model
);
function selectModel(v) {
  sess.model      = v;
  modelMenuOpen.value = false;
}

const groupedSessions = computed(() => {
  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const dayMs = 24 * 60 * 60 * 1000;
  const groups = [
    { label: '今天', items: [] },
    { label: '昨天', items: [] },
    { label: '7 天内', items: [] },
    { label: '30 天内', items: [] },
    { label: '更早', items: [] },
  ];

  sess.sessions.forEach(session => {
    const time = new Date(session.createdAt || Date.now()).getTime();
    if (time >= startOfToday) groups[0].items.push(session);
    else if (time >= startOfToday - dayMs) groups[1].items.push(session);
    else if (time >= startOfToday - 7 * dayMs) groups[2].items.push(session);
    else if (time >= startOfToday - 30 * dayMs) groups[3].items.push(session);
    else groups[4].items.push(session);
  });

  return groups;
});

// ── 会话操作 ────────────────────────────────────────────────────────
const bulkMode = ref(false);
const selectedSessionIds = ref(new Set());
const selectedCount = computed(() => selectedSessionIds.value.size);
const visibleSessionIds = computed(() =>
  groupedSessions.value.flatMap(group => group.items.map(session => session.id))
);
const allVisibleSelected = computed(() =>
  visibleSessionIds.value.length > 0
  && visibleSessionIds.value.every(id => selectedSessionIds.value.has(id))
);

function handleNewSession() {
  exitBulkMode();
  sess.newSession();
  router.push('/chat');
}

function handleSwitchSession(id) {
  sess.switchSession(id);
  router.push('/chat');
}

function handleSessionClick(id) {
  if (bulkMode.value) {
    toggleSessionSelection(id);
    return;
  }
  handleSwitchSession(id);
}

function enterBulkMode() {
  bulkMode.value = true;
  selectedSessionIds.value = new Set();
}

function exitBulkMode() {
  bulkMode.value = false;
  selectedSessionIds.value = new Set();
}

function toggleSessionSelection(id) {
  const next = new Set(selectedSessionIds.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  selectedSessionIds.value = next;
}

function toggleSelectAll() {
  if (allVisibleSelected.value) {
    selectedSessionIds.value = new Set();
    return;
  }
  selectedSessionIds.value = new Set(visibleSessionIds.value);
}

async function handleDeleteSelected() {
  const ids = [...selectedSessionIds.value];
  if (!ids.length) return;
  const confirmed = await ui.showConfirm({
    title: '删除所选会话',
    message: `确认删除选中的 ${ids.length} 个会话？删除后不可恢复。`,
    confirmText: '删除',
    variant: 'danger',
  });
  if (!confirmed) return;
  try {
    await sess.removeSessions(ids);
    exitBulkMode();
  } catch (err) {
    ui.showToast('error', err.message || '批量删除失败');
  }
}

async function handleDeleteAll() {
  const confirmed = await ui.showConfirm({
    title: '清空全部会话',
    message: '确认删除所有会话记录？删除后不可恢复。',
    confirmText: '清空全部',
    variant: 'danger',
  });
  if (!confirmed) return;
  try {
    await sess.removeAllSessions();
    exitBulkMode();
  } catch (err) {
    ui.showToast('error', err.message || '清空全部会话失败');
  }
}

// ── 标题编辑 ────────────────────────────────────────────────────────
const editingSessionId = ref(null);
const editingTitle     = ref('');
const titleInputEl     = ref(null);

async function startEditTitle(session) {
  editingSessionId.value = session.id;
  editingTitle.value     = session.title;
  await nextTick();
  titleInputEl.value?.focus?.();
  titleInputEl.value?.[0]?.focus?.();
}

async function saveTitle(id) {
  const newTitle = editingTitle.value.trim();
  editingSessionId.value = null;
  if (!newTitle) return;
  await sess.renameSession(id, newTitle);
}

function cancelEditTitle() {
  editingSessionId.value = null;
}

// ── 搜索（P2-9：优先调用服务端接口，客户端兜底） ────────────────────────
const searchVisible  = ref(false);
const searchQuery    = ref('');
const searchInputEl  = ref(null);
const serverResults  = ref(null);   // null = 未搜索，[] = 搜索结果
const searchLoading  = ref(false);
let _searchTimer     = null;

function openSearch() {
  searchVisible.value = true;
  searchQuery.value   = '';
  serverResults.value = null;
  nextTick(() => searchInputEl.value?.focus());
}

function closeSearch() {
  searchVisible.value = false;
  serverResults.value = null;
}

function openSearchResult(id) {
  sess.switchSession(id);
  router.push('/chat');
  closeSearch();
}

// 输入变化时 debounce 调用服务端搜索
watch(searchQuery, (q) => {
  clearTimeout(_searchTimer);
  serverResults.value = null;
  if (!q.trim()) return;
  searchLoading.value = true;
  _searchTimer = setTimeout(async () => {
    try {
      const { listChatSessions } = await import('../../services/api.js');
      const results = await listChatSessions(q.trim());
      serverResults.value = (results || []).map(s => ({
        id:        s.sessionId || s.id,
        title:     s.title || '历史对话',
        createdAt: s.createdAt,
      }));
    } catch {
      // 降级为客户端过滤
      serverResults.value = [];
    } finally {
      searchLoading.value = false;
    }
  }, 300);
});

const filteredSessions = computed(() => {
  const q = searchQuery.value.trim().toLowerCase();
  if (!q) return sess.sessions;
  // 优先使用服务端结果，否则客户端标题过滤兜底
  if (serverResults.value !== null) return serverResults.value;
  return sess.sessions.filter(s => s.title.toLowerCase().includes(q));
});

function snippet(session) {
  const q    = searchQuery.value.trim().toLowerCase();
  const msgs = sess.sessionMessages[session.id] || [];
  if (q && msgs.length) {
    const matched = msgs.find(m => stripHtml(m.html).toLowerCase().includes(q));
    if (matched) {
      const text = stripHtml(matched.html);
      const idx  = text.toLowerCase().indexOf(q);
      return text.slice(Math.max(0, idx - 20), idx + 60) || text.slice(0, 80);
    }
  }
  // 服务端搜索结果可能没有在内存中的消息，直接返回标题摘要
  if (msgs.length) return stripHtml(msgs[msgs.length - 1].html).slice(0, 80);
  return '点击打开这段历史对话';
}

function formatDate(value) {
  const d = new Date(value || Date.now());
  const today = new Date();
  if (d.toDateString() === today.toDateString()) return '今天';
  return `${d.getMonth() + 1}月${d.getDate()}日`;
}

function stripHtml(html) {
  const tmp = document.createElement('div');
  tmp.innerHTML = html;
  return tmp.textContent || '';
}
</script>
