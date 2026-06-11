<template>
  <aside class="sidebar">
    <div class="sidebar-logo">
      <h1><span class="logo-icon"><LogoMark /></span>AI Agent</h1>
    </div>

    <div class="sidebar-section">
      <button class="new-chat-btn" type="button" @click="newSession">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><path d="M19 13H13V19H11V13H5V11H11V5H13V11H19V13Z"/></svg>
        新建对话
      </button>
    </div>

    <div class="sidebar-section">
      <div class="sidebar-section-title">最近对话</div>
    </div>
    <div class="session-list">
      <div v-if="sessions.length === 0" class="session-empty">暂无历史对话</div>
      <div
        v-for="session in sessions"
        :key="session.id"
        class="session-item"
        :class="{ active: session.id === sessionId }"
        :title="session.title"
        @click="switchSession(session.id)"
      >
        <span class="session-icon">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor" opacity=".5"><path d="M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z"/></svg>
        </span>
        <span class="session-title">{{ session.title }}</span>
        <button class="session-delete" type="button" title="删除会话" @click.stop="removeSession(session.id)">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>
        </button>
      </div>
    </div>

    <div class="sidebar-bottom">
      <div v-if="user" class="user-info">
        <div class="user-avatar">{{ (user.username || 'U')[0].toUpperCase() }}</div>
        <div class="user-text">
          <div class="user-name">{{ user.username || user.userId }}</div>
          <div class="user-state">已登录</div>
        </div>
        <button class="logout-btn" type="button" title="退出登录" @click="handleLogout">⎋</button>
      </div>
      <div class="sidebar-section-title model-title">当前模型</div>
      <select class="model-selector" v-model="model">
        <option value="deepseek">DeepSeek（默认）</option>
        <option value="claude">Claude</option>
      </select>
    </div>
  </aside>

  <main class="main">
    <div class="topbar">
      <div class="topbar-title">{{ currentSessionTitle }}</div>
      <div class="topbar-actions">
        <button class="topbar-btn danger" type="button" @click="handleClearMemory">清除记忆</button>
        <button class="topbar-btn" type="button" @click="activeTab = 'kb'">知识库</button>
      </div>
    </div>

    <div class="tabs">
      <button v-for="tab in tabs" :key="tab.key" class="tab" :class="{ active: activeTab === tab.key }" type="button" @click="activeTab = tab.key">
        {{ tab.label }}
      </button>
    </div>

    <div class="content">
      <section class="tab-panel" :class="{ active: activeTab === 'chat' }">
        <div ref="chatMessagesEl" class="chat-messages">
          <div v-if="messages.length === 0" class="welcome">
            <div class="welcome-icon"><LogoMark /></div>
            <h2>你好，我是 AI Agent</h2>
            <p>我可以回答问题、查询信息、帮你完成各种任务</p>
            <div class="quick-prompts">
              <button v-for="prompt in quickPrompts" :key="prompt.message" class="quick-prompt" type="button" @click="sendQuick(prompt.message)">
                {{ prompt.label }}
              </button>
            </div>
          </div>

          <div v-for="message in messages" :key="message.id" class="message" :class="message.role">
            <div v-if="message.role === 'ai'" class="avatar ai">AI</div>
            <div class="bubble" v-html="message.html"></div>
          </div>
        </div>

        <div class="chat-input-area">
          <div class="input-wrapper">
            <textarea
              id="messageInput"
              ref="messageInputEl"
              v-model="messageInput"
              :disabled="isSending"
              placeholder="输入消息，Ctrl+Enter 发送..."
              rows="1"
              @input="autoResize"
              @keydown.ctrl.enter.prevent="sendMessage"
            ></textarea>
            <button class="send-btn" type="button" :disabled="isSending || !messageInput.trim()" @click="sendMessage">
              <svg v-if="!isSending" width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></svg>
              <svg v-else width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><path d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z"/></svg>
            </button>
          </div>
          <div class="input-hints">
            <span class="hint-text">Ctrl+Enter 发送 · Enter 换行</span>
            <label class="stream-toggle" title="开启后文字逐步显示">
              <button class="toggle" :class="{ on: streamEnabled }" type="button" @click="streamEnabled = !streamEnabled"></button>
              <span>流式输出</span>
            </label>
            <label class="stream-toggle" title="适合复杂任务，多步推理自动拆解">
              <button class="toggle" :class="{ on: reactEnabled }" type="button" @click="toggleReact"></button>
              <span>深度推理</span>
            </label>
          </div>
        </div>
      </section>

      <section class="tab-panel" :class="{ active: activeTab === 'kb' }">
        <div class="kb-panel">
          <div class="kb-selector-area">
            <div class="kb-selector-header">
              <h3 class="kb-section-title">我的知识库</h3>
              <button class="kb-create-btn" type="button" @click="handleCreateKb">+ 新建</button>
            </div>
            <div class="kb-list">
              <div v-if="knowledgeBases.length === 0" class="kb-empty-hint">暂无知识库，点击上方按钮创建</div>
              <div
                v-for="kb in knowledgeBases"
                :key="kb.id"
                class="kb-item"
                :class="{ active: kb.id === currentKbId }"
                @click="selectKb(kb.id)"
              >
                <div class="kb-item-icon"></div>
                <div class="kb-item-info">
                  <div class="kb-item-name">{{ kb.name }}</div>
                  <div class="kb-item-meta">{{ kb.docCount || 0 }} 篇文档</div>
                </div>
                <button class="kb-item-delete" type="button" title="删除知识库" @click.stop="handleDeleteKb(kb.id)"></button>
              </div>
            </div>
          </div>

          <div class="kb-current-area">
            <div class="kb-current-header">
              <h3 class="kb-section-title">文档管理</h3>
              <button v-if="currentKbId" class="kb-manage-members-btn" type="button" title="管理知识库成员" @click="openKbMembers">成员</button>
            </div>

            <div
              class="kb-upload-area"
              :class="{ 'drag-over': dragOver }"
              :style="{ opacity: currentKbId ? '1' : '0.5', pointerEvents: currentKbId ? 'auto' : 'none' }"
              @click="triggerUpload"
              @dragover.prevent="dragOver = true"
              @dragleave="dragOver = false"
              @drop.prevent="handleDrop"
            >
              <div class="kb-upload-icon">
                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#9CA3AF" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                  <polyline points="14 2 14 8 20 8"/>
                  <line x1="12" y1="18" x2="12" y2="12"/>
                  <line x1="9" y1="15" x2="15" y2="15"/>
                </svg>
              </div>
              <div class="kb-upload-title">上传文档到当前知识库</div>
              <div class="kb-upload-desc">支持 PDF、Word、TXT、Markdown 等格式 · 拖拽或点击上传 · 最大 50MB</div>
              <button class="upload-btn" type="button">选择文件</button>
              <input ref="fileInputEl" id="fileInput" type="file" multiple accept=".pdf,.doc,.docx,.txt,.md" @change="handleFileChange">
            </div>

            <div v-show="uploadProgress.show" class="upload-progress">
              <div class="progress-info">
                <span class="progress-filename">{{ uploadProgress.filename || '上传中...' }}</span>
                <span class="progress-pct">{{ Math.round(uploadProgress.pct) }}%</span>
              </div>
              <div class="progress-bar"><div class="progress-fill" :style="{ width: uploadProgress.pct + '%' }"></div></div>
            </div>

            <div class="kb-docs-title">已导入文档</div>
            <div>
              <div v-if="!currentKbId" class="empty-docs">请先选择一个知识库</div>
              <div v-else-if="docs.length === 0" class="empty-docs">暂无文档，上传后 AI 可基于文档内容回答</div>
              <div v-for="doc in docs" :key="doc.id" class="doc-item">
                <div class="doc-icon" :class="getFileIcon(doc.filename).cls" v-html="getFileIcon(doc.filename).icon"></div>
                <div class="doc-info">
                  <div class="doc-name" :title="doc.filename">{{ doc.filename }}</div>
                  <div class="doc-meta">{{ statusLabel(doc.status) }} {{ doc.chunks }} 个切片{{ doc.size ? ` · ${formatFileSize(doc.size)}` : '' }} · {{ doc.uploadedAt }}</div>
                </div>
                <div class="doc-actions">
                  <button class="doc-delete" type="button" title="从知识库删除此文档" @click="handleDeleteDoc(doc)"></button>
                </div>
              </div>
            </div>
          </div>

          <div v-if="kbMembersVisible" class="kb-members-panel">
            <div class="kb-members-header">
              <h3>知识库成员</h3>
              <button class="kb-members-close" type="button" @click="kbMembersVisible = false">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>
              </button>
            </div>
            <div class="kb-members-add">
              <input v-model.trim="kbMemberUserId" type="text" placeholder="输入用户 ID" class="kb-member-input">
              <select v-model="kbMemberRole" class="kb-member-role-select">
                <option value="VIEWER">只读（VIEWER）</option>
                <option value="EDITOR">编辑（EDITOR）</option>
              </select>
              <button class="kb-member-add-btn" type="button" @click="addMemberToCurrentKb">添加</button>
            </div>
            <div class="kb-members-list">
              <div v-if="kbMembers.length === 0" class="empty-hint">暂无成员</div>
              <div v-for="member in kbMembers" :key="member.userId" class="kb-member-item">
                <span class="kb-member-id">{{ member.userId }}</span>
                <span class="kb-member-role">{{ kbRoleLabel(member.role) }}</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section class="tab-panel" :class="{ active: activeTab === 'org' }">
        <div class="kb-panel">
          <div class="org-panel">
            <div class="org-header">
              <h3 class="org-section-title">组织管理</h3>
              <button class="org-create-btn" type="button" @click="handleCreateOrg">+ 创建企业组织</button>
            </div>
            <div class="org-desc">组织是多租户的基本单位。个人用户自动拥有「个人空间」，企业可创建组织邀请员工共享知识库。</div>
            <div class="org-list">
              <div v-if="organizations.length === 0" class="org-empty-hint">暂无组织</div>
              <div
                v-for="org in organizations"
                :key="org.orgId"
                class="org-item"
                :class="{ active: org.orgId === currentOrgId }"
                @click="selectOrg(org.orgId)"
              >
                <div class="org-item-icon">{{ org.orgId?.startsWith('org_') ? '个人' : '企业' }}</div>
                <div class="org-item-info">
                  <div class="org-item-name">{{ org.orgId?.startsWith('org_') ? '个人空间' : (org.name || org.orgId) }}</div>
                  <div class="org-item-meta">{{ orgRoleLabel(org.role) }}</div>
                </div>
              </div>
            </div>
            <div v-if="currentOrgId" class="org-actions">
              <h4 class="org-section-title">组织操作</h4>
              <button class="org-action-btn" type="button" @click="handleInviteMember">邀请成员</button>
              <button class="org-action-btn" type="button" @click="showOrgMembers(currentOrgId)">查看成员</button>
            </div>
          </div>
        </div>
      </section>
    </div>
  </main>

  <div class="toast-container">
    <div v-for="toast in toasts" :key="toast.id" class="toast toast-visible" :class="`toast-${toast.type}`">
      <span class="toast-icon" v-html="toastIcon(toast.type)"></span>
      <span class="toast-msg">{{ toast.message }}</span>
      <button class="toast-close" type="button" @click="dismissToast(toast.id)">×</button>
    </div>
  </div>

  <div v-if="orgModal.visible" class="modal-overlay" @click.self="orgModal.visible = false">
    <div class="modal-content">
      <div class="modal-header">
        <h3>{{ orgModal.title }}</h3>
        <button class="modal-close" type="button" @click="orgModal.visible = false">×</button>
      </div>
      <div class="modal-body">
        <div v-if="orgModal.members.length === 0" class="empty-hint">暂无成员</div>
        <div v-for="member in orgModal.members" :key="member.userId" class="member-item">
          <div class="member-info">
            <div class="member-id">{{ member.userId }}</div>
            <div class="member-role">{{ orgRoleLabel(member.role) }}</div>
          </div>
          <button v-if="member.role !== 'OWNER'" class="member-remove" type="button" @click="removeOrgMemberFromModal(member.userId)">移除</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, nextTick, onMounted, reactive, ref } from 'vue';
import * as api from './services/api.js';
import { formatFileSize, formatMarkdown, getFileIcon } from './js/utils.js';

const LogoMark = defineComponent({
  setup() {
    return () => h('svg', { viewBox: '0 0 24 24', fill: '#fff', xmlns: 'http://www.w3.org/2000/svg' }, [
      h('path', { d: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 14H9V8h2v8zm4 0h-2V8h2v8z' })
    ]);
  }
});

const tabs = [
  { key: 'chat', label: '对话' },
  { key: 'kb', label: '知识库' },
  { key: 'org', label: '组织' }
];
const quickPrompts = [
  { label: '查询订单状态', message: '帮我查一下订单 #12345 的状态' },
  { label: '查询天气', message: '北京今天天气怎么样？' },
  { label: '了解功能', message: '帮我介绍一下你能做什么' },
  { label: '查询账户', message: '查询用户 U001 的账户余额' }
];

const user = ref(api.getUser());
const model = ref('deepseek');
const activeTab = ref('chat');
const sessionId = ref(generateId());
const sessions = ref([]);
const messages = ref([]);
const messageInput = ref('');
const streamEnabled = ref(true);
const reactEnabled = ref(false);
const isSending = ref(false);
const chatMessagesEl = ref(null);
const messageInputEl = ref(null);

const knowledgeBases = ref([]);
const currentKbId = ref(null);
const docs = ref([]);
const fileInputEl = ref(null);
const dragOver = ref(false);
const uploadProgress = reactive({ show: false, filename: '', pct: 0, timer: null });
const kbMembersVisible = ref(false);
const kbMembers = ref([]);
const kbMemberUserId = ref('');
const kbMemberRole = ref('VIEWER');

const organizations = ref([]);
const currentOrgId = ref(null);
const orgModal = reactive({ visible: false, title: '', orgId: '', members: [] });
const toasts = ref([]);

const currentSessionTitle = computed(() => sessions.value.find(s => s.id === sessionId.value)?.title || '新对话');

onMounted(async () => {
  if (!api.getToken()) {
    location.replace('/login.html');
    return;
  }
  addSession(sessionId.value, '新对话');
  await Promise.all([loadKnowledgeBases(), loadOrganizations()]);
});

function generateId() {
  return 'session-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7);
}

function handleLogout() {
  if (confirm('确认退出登录？')) api.logout();
}

function newSession() {
  const id = generateId();
  sessionId.value = id;
  messages.value = [];
  addSession(id, '新对话');
  activeTab.value = 'chat';
}

function addSession(id, title) {
  if (!sessions.value.some(s => s.id === id)) {
    sessions.value.unshift({ id, title, createdAt: Date.now() });
  }
}

function switchSession(id) {
  sessionId.value = id;
  messages.value = [];
  activeTab.value = 'chat';
}

function removeSession(id) {
  const index = sessions.value.findIndex(s => s.id === id);
  if (index < 0) return;
  const [removed] = sessions.value.splice(index, 1);
  showToast('info', `已删除会话：${removed.title}`);
  if (sessionId.value === id) {
    if (sessions.value.length) switchSession(sessions.value[0].id);
    else newSession();
  }
}

function updateSessionTitle(text) {
  const session = sessions.value.find(s => s.id === sessionId.value);
  if (session && session.title === '新对话') {
    session.title = text.slice(0, 12) + (text.length > 12 ? '...' : '');
  }
}

async function sendQuick(text) {
  messageInput.value = text;
  await sendMessage();
}

async function sendMessage() {
  const text = messageInput.value.trim();
  if (!text || isSending.value) return;
  messageInput.value = '';
  resetInputHeight();
  pushMessage('user', formatMarkdown(text));
  updateSessionTitle(text);

  if (reactEnabled.value) await doReactChat(text);
  else if (streamEnabled.value) await doStreamChat(text);
  else await doSyncChat(text);
}

async function doSyncChat(text) {
  isSending.value = true;
  const bubble = pushMessage('ai', '<span class="typing-dots">●●●</span>');
  try {
    const data = await api.chatSync(sessionId.value, text);
    bubble.html = formatMarkdown(data.reply);
  } catch (error) {
    bubble.html = `<span class="error-msg">请求失败：${escapeHtml(error.message)}</span>`;
    showToast('error', '发送失败，请检查网络或服务');
  } finally {
    isSending.value = false;
    scrollToBottom();
  }
}

async function doStreamChat(text) {
  isSending.value = true;
  const bubble = pushMessage('ai', '');
  let fullText = '';
  const eventSource = api.chatStream(sessionId.value, text);

  eventSource.onmessage = (event) => {
    if (event.data === '[DONE]') return;
    fullText += event.data;
    bubble.html = formatMarkdown(fullText) + '<span class="typing-cursor"></span>';
    scrollToBottom();
  };
  eventSource.addEventListener('replace', (event) => {
    fullText = event.data;
    bubble.html = formatMarkdown(fullText) + '<span class="typing-cursor"></span>';
    scrollToBottom();
  });
  eventSource.addEventListener('done', () => {
    eventSource.close();
    bubble.html = formatMarkdown(fullText);
    finish();
  });
  eventSource.onerror = () => {
    eventSource.close();
    if (!fullText) {
      bubble.html = '<span class="error-msg">连接失败，请重试</span>';
      showToast('error', '流式连接失败');
    }
    finish();
  };

  function finish() {
    isSending.value = false;
    scrollToBottom();
  }
}

async function doReactChat(text) {
  isSending.value = true;
  const bubble = pushMessage('ai', '<span class="typing-dots">●●●</span>');
  try {
    const data = await api.chatReact(sessionId.value, text);
    bubble.html = renderReactAnswer(data);
  } catch (error) {
    bubble.html = `<span class="error-msg">推理失败：${escapeHtml(error.message)}</span>`;
    showToast('error', '深度推理失败，请重试');
  } finally {
    isSending.value = false;
    scrollToBottom();
  }
}

function renderReactAnswer(data) {
  if (!data.steps?.length) return formatMarkdown(data.answer);
  const steps = data.steps.map(step => `
    <div class="react-step">
      <div class="react-step-label">第 ${step.iteration} 步 · ${escapeHtml(step.toolName || '')}</div>
      ${step.thought ? `<div class="react-thought">${escapeHtml(trimText(step.thought, 120))}</div>` : ''}
      <div class="react-tool">${escapeHtml(step.toolName || '')}(${escapeHtml(step.toolArgs || '')})</div>
      ${step.observation ? `<div class="react-obs">${escapeHtml(trimText(step.observation, 150))}</div>` : ''}
    </div>
  `).join('');
  return `
    <details class="react-steps-container">
      <summary class="react-steps-summary">推理过程（${data.iterations} 步 · ${data.durationMs}ms）</summary>
      <div class="react-steps">${steps}</div>
    </details>
    <div class="react-answer">${formatMarkdown(data.answer)}</div>
  `;
}

function pushMessage(role, html) {
  const item = { id: generateId(), role, html };
  messages.value.push(item);
  scrollToBottom();
  return item;
}

async function handleClearMemory() {
  if (!confirm(`确认清除当前会话的所有记忆？\n会话ID: ${sessionId.value}`)) return;
  try {
    await api.clearMemory(sessionId.value);
    messages.value = [];
    showToast('success', '记忆已清除，对话重新开始');
  } catch {
    showToast('error', '清除失败，请重试');
  }
}

function toggleReact() {
  reactEnabled.value = !reactEnabled.value;
  if (reactEnabled.value) showToast('info', '深度推理已开启，适合复杂多步任务');
}

function autoResize() {
  const el = messageInputEl.value;
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = Math.min(el.scrollHeight, 160) + 'px';
}

function resetInputHeight() {
  nextTick(() => {
    if (messageInputEl.value) messageInputEl.value.style.height = 'auto';
  });
}

function scrollToBottom() {
  nextTick(() => {
    if (chatMessagesEl.value) chatMessagesEl.value.scrollTop = chatMessagesEl.value.scrollHeight;
  });
}

async function loadKnowledgeBases() {
  try {
    knowledgeBases.value = await api.listKnowledgeBases();
    if (knowledgeBases.value.length && !currentKbId.value) selectKb(knowledgeBases.value[0].id);
  } catch {
    knowledgeBases.value = [];
  }
}

async function selectKb(kbId) {
  currentKbId.value = kbId;
  kbMembersVisible.value = false;
  await loadDocumentList();
}

async function handleCreateKb() {
  const name = prompt('请输入知识库名称：');
  if (!name?.trim()) return;
  try {
    await api.createKnowledgeBase(name.trim(), '');
    showToast('success', `知识库「${name.trim()}」创建成功`);
    await loadKnowledgeBases();
  } catch (error) {
    showToast('error', `创建失败：${error.message}`);
  }
}

async function handleDeleteKb(kbId) {
  const kb = knowledgeBases.value.find(item => item.id === kbId);
  if (!kb || !confirm(`确认删除知识库「${kb.name}」？\n\n此操作不可恢复，所有文档和切片将被永久删除。`)) return;
  try {
    await api.deleteKnowledgeBase(kbId);
    showToast('success', `已删除：${kb.name}`);
    if (currentKbId.value === kbId) currentKbId.value = null;
    docs.value = [];
    await loadKnowledgeBases();
  } catch (error) {
    showToast('error', `删除失败：${error.message}`);
  }
}

async function loadDocumentList() {
  if (!currentKbId.value) {
    docs.value = [];
    return;
  }
  try {
    const data = await api.listDocuments(currentKbId.value);
    docs.value = data.map(doc => ({
      id: doc.id,
      filename: doc.name ?? doc.filename,
      chunks: doc.chunkCount ?? doc.chunks ?? 0,
      size: doc.fileSize ?? 0,
      status: doc.parseStatus ?? 'UNKNOWN',
      uploadedAt: doc.createdAt ?? new Date().toLocaleString()
    }));
  } catch {
    docs.value = [];
  }
}

function triggerUpload() {
  if (!currentKbId.value) {
    showToast('warning', '请先选择或创建知识库');
    return;
  }
  fileInputEl.value?.click();
}

function handleFileChange(event) {
  Array.from(event.target.files || []).forEach(handleUpload);
  event.target.value = '';
}

function handleDrop(event) {
  dragOver.value = false;
  if (!currentKbId.value) {
    showToast('warning', '请先选择或创建知识库');
    return;
  }
  Array.from(event.dataTransfer.files || []).forEach(handleUpload);
}

async function handleUpload(file) {
  const allowed = ['pdf', 'doc', 'docx', 'txt', 'md'];
  const ext = file.name.split('.').pop().toLowerCase();
  if (!allowed.includes(ext)) return showToast('error', `不支持的文件类型：.${ext}`);
  if (file.size > 50 * 1024 * 1024) return showToast('error', `文件过大（最大 50MB）：${file.name}`);

  showUploadProgress(file.name);
  try {
    const data = await api.uploadDocument(currentKbId.value, file);
    finishUploadProgress();
    docs.value.push({
      id: data.documentId ?? Date.now().toString(),
      filename: file.name,
      chunks: data.chunkCount ?? 0,
      size: file.size,
      status: 'DONE',
      uploadedAt: new Date().toLocaleString()
    });
    showToast('success', `${file.name} 导入成功`);
    await loadKnowledgeBases();
  } catch (error) {
    hideUploadProgress();
    showToast('error', `上传失败：${error.message}`);
  }
}

async function handleDeleteDoc(doc) {
  if (!confirm(`确认从知识库删除：${doc.filename}？\n\n此操作不可恢复。`)) return;
  try {
    await api.deleteDocument(currentKbId.value, doc.id);
    docs.value = docs.value.filter(item => item.id !== doc.id);
    showToast('success', `已删除：${doc.filename}`);
    await loadKnowledgeBases();
  } catch (error) {
    showToast('error', `删除失败：${error.message}`);
  }
}

async function openKbMembers() {
  kbMembersVisible.value = true;
  try {
    kbMembers.value = await api.listKbMembers(currentKbId.value);
  } catch {
    kbMembers.value = [];
  }
}

async function addMemberToCurrentKb() {
  if (!kbMemberUserId.value) return showToast('warning', '请输入用户 ID');
  try {
    await api.addKbMember(currentKbId.value, kbMemberUserId.value, kbMemberRole.value);
    showToast('success', `已添加成员：${kbMemberUserId.value}`);
    kbMemberUserId.value = '';
    await openKbMembers();
  } catch (error) {
    showToast('error', `添加成员失败：${error.message}`);
  }
}

function showUploadProgress(filename) {
  clearInterval(uploadProgress.timer);
  uploadProgress.show = true;
  uploadProgress.filename = filename;
  uploadProgress.pct = 0;
  uploadProgress.timer = setInterval(() => {
    uploadProgress.pct = Math.min(uploadProgress.pct + Math.random() * 12, 88);
  }, 200);
}

function finishUploadProgress() {
  clearInterval(uploadProgress.timer);
  uploadProgress.pct = 100;
  setTimeout(hideUploadProgress, 800);
}

function hideUploadProgress() {
  clearInterval(uploadProgress.timer);
  uploadProgress.show = false;
  uploadProgress.pct = 0;
  uploadProgress.filename = '';
}

async function loadOrganizations() {
  try {
    const memberships = await api.listOrganizations();
    organizations.value = memberships.map(item => ({
      orgId: item.orgId,
      role: item.role,
      name: item.name || item.orgId
    }));
    if (organizations.value.length && !currentOrgId.value) currentOrgId.value = organizations.value[0].orgId;
  } catch {
    organizations.value = [];
  }
}

function selectOrg(orgId) {
  currentOrgId.value = orgId;
  showToast('info', '已切换组织');
}

async function handleCreateOrg() {
  const name = prompt('请输入企业/组织名称：');
  if (!name?.trim()) return;
  const description = prompt('组织描述（可选）：', '') || '';
  try {
    await api.createOrganization(name.trim(), description.trim());
    showToast('success', `企业组织「${name.trim()}」创建成功`);
    await loadOrganizations();
  } catch (error) {
    showToast('error', `创建失败：${error.message}`);
  }
}

async function handleInviteMember() {
  if (!currentOrgId.value) return showToast('warning', '请先选择一个组织');
  const userId = prompt('请输入要邀请的用户 ID：');
  if (!userId?.trim()) return;
  const role = prompt('请选择角色（输入数字）：\n1. 成员\n2. 管理员', '1');
  const roleValue = role === '2' ? 'ADMIN' : 'MEMBER';
  try {
    await api.inviteOrgMember(currentOrgId.value, userId.trim(), roleValue);
    showToast('success', `已邀请 ${userId.trim()} 加入组织`);
  } catch (error) {
    showToast('error', `邀请失败：${error.message}`);
  }
}

async function showOrgMembers(orgId) {
  try {
    const data = await api.getOrganization(orgId);
    orgModal.visible = true;
    orgModal.title = `组织成员 - ${orgId}`;
    orgModal.orgId = orgId;
    orgModal.members = data.members || [];
  } catch (error) {
    showToast('error', `加载成员失败：${error.message}`);
  }
}

async function removeOrgMemberFromModal(userId) {
  if (!confirm(`确认移除成员 ${userId}？`)) return;
  try {
    await api.removeOrgMember(orgModal.orgId, userId);
    showToast('success', `已移除：${userId}`);
    await showOrgMembers(orgModal.orgId);
  } catch (error) {
    showToast('error', `移除失败：${error.message}`);
  }
}

function statusLabel(status) {
  if (status === 'DONE') return '完成';
  if (status === 'FAILED') return '失败';
  return '处理中';
}

function kbRoleLabel(role) {
  if (role === 'OWNER') return '拥有者';
  if (role === 'EDITOR') return '编辑者';
  return '只读';
}

function orgRoleLabel(role) {
  if (role === 'OWNER') return '拥有者';
  if (role === 'ADMIN') return '管理员';
  return '成员';
}

function showToast(type, message, duration = 3500) {
  const id = generateId();
  toasts.value.push({ id, type, message });
  if (duration > 0) setTimeout(() => dismissToast(id), duration);
}

function dismissToast(id) {
  toasts.value = toasts.value.filter(item => item.id !== id);
}

function toastIcon(type) {
  const icons = {
    success: '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41L9 16.17z"/></svg>',
    error: '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>',
    info: '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/></svg>',
    warning: '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"/></svg>'
  };
  return icons[type] || icons.info;
}

function trimText(text, len) {
  return text.length > len ? text.substring(0, len) + '...' : text;
}

function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}
</script>
