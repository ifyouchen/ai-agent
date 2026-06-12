<template>
  <div class="kb-view">
    <div class="kb-panel">
      <!-- 左侧：知识库列表 -->
      <div class="kb-selector-area">
        <div class="kb-selector-header">
          <div class="kb-section-title-row">
            <h3 class="kb-section-title">
              知识库
              <svg v-if="kb.kbLoading" class="inline-spinner" viewBox="0 0 24 24" fill="none" width="14" height="14">
                <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
              </svg>
            </h3>
            <span class="kb-org-badge" :title="'当前组织：' + org.currentOrgName">{{ org.currentOrgName }}</span>
          </div>
          <button class="kb-create-btn" type="button" @click="handleCreateKb">+ 新建</button>
        </div>

        <!-- 知识库列表 -->
        <div class="kb-list">
          <div v-if="kb.kbLoading && !kb.knowledgeBases.length" class="kb-loading-placeholder">
            <div v-for="i in 3" :key="i" class="loading-item-skeleton"></div>
          </div>
          <div v-else-if="!kb.kbLoading && !kb.knowledgeBases.length" class="kb-empty-hint">
            暂无知识库，点击上方按钮创建
          </div>
          <div
            v-for="item in kb.knowledgeBases"
            :key="item.id"
            class="kb-item"
            :class="{ active: item.id === kb.currentKbId }"
            @click="kb.selectKb(item.id, org.currentOrgId)"
          >
            <div class="kb-item-icon"></div>
            <div class="kb-item-info">
              <div class="kb-item-name">{{ item.name }}</div>
              <div class="kb-item-meta">{{ item.docCount || 0 }} 篇文档</div>
              <div v-if="item.description" class="kb-item-desc" :title="item.description">
                {{ item.description }}
              </div>
            </div>
            <button class="kb-item-edit" type="button" title="编辑知识库" @click.stop="handleEditKb(item)">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none">
                <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </button>
            <button class="kb-item-delete" type="button" title="删除知识库" @click.stop="handleDeleteKb(item)"></button>
          </div>
        </div>
      </div>

      <!-- 右侧：文档管理 -->
      <div class="kb-current-area">
        <div class="kb-current-header">
          <h3 class="kb-section-title">
            文档管理
            <svg v-if="kb.docsLoading" class="inline-spinner" viewBox="0 0 24 24" fill="none" width="14" height="14">
              <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
            </svg>
          </h3>
          <div style="display:flex;align-items:center;gap:6px;">
            <button v-if="kb.currentKbId" class="kb-manage-members-btn" type="button"
                    title="刷新文档列表" @click="kb.loadDocs(org.currentOrgId)" style="padding:4px 8px;">↻</button>
            <button v-if="kb.currentKbId" class="kb-manage-members-btn" type="button"
                    title="管理知识库成员" @click="openKbMembers">成员</button>
            <!-- 在对话中使用此知识库 -->
            <button v-if="kb.currentKbId" class="kb-manage-members-btn" type="button"
                    style="color:var(--primary);" @click="useKbInChat">在对话中使用</button>
          </div>
        </div>

        <!-- 上传区域 -->
        <div
          class="kb-upload-area"
          :class="{ 'drag-over': dragOver }"
          :style="{ opacity: kb.currentKbId ? 1 : 0.5, pointerEvents: kb.currentKbId ? 'auto' : 'none' }"
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
          <div class="kb-upload-desc">支持 PDF、Word、TXT、Markdown · 拖拽或点击上传 · 最大 50MB</div>
          <button class="upload-btn" type="button">选择文件</button>
          <input
            ref="fileInputEl"
            type="file"
            multiple
            accept=".pdf,.doc,.docx,.txt,.md"
            style="display:none"
            @change="handleFileChange"
          />
        </div>

        <!-- 上传队列 -->
        <div v-if="kb.uploadQueue.length > 0" class="upload-queue">
          <div class="upload-queue-header">
            <span>{{ queueSummary }}</span>
            <span v-if="queueFinished" class="upload-queue-done">
              <svg viewBox="0 0 24 24" fill="none" width="13" height="13">
                <path d="m5 12 4 4L19 6" stroke="#00A96E" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              全部完成
            </span>
          </div>
          <div v-for="task in kb.uploadQueue" :key="task.id" class="upload-task">
            <div class="upload-task-top">
              <span class="upload-task-name" :title="task.filename">{{ task.filename }}</span>
              <span class="upload-task-status" :class="`status-${task.status}`">
                <template v-if="task.status === 'uploading'">{{ task.pct }}%</template>
                <template v-else-if="task.status === 'processing'">解析中…</template>
                <template v-else-if="task.status === 'done'">✓ 完成</template>
                <template v-else-if="task.status === 'error'">✗ 失败</template>
                <template v-else>等待</template>
              </span>
            </div>
            <div class="upload-task-bar">
              <div class="upload-task-fill" :class="`fill-${task.status}`" :style="{ width: task.barWidth + '%' }"></div>
            </div>
            <div v-if="task.status === 'uploading'" class="upload-task-meta">
              <span>{{ task.speedText }}</span>
              <span v-if="task.etaText">剩余 {{ task.etaText }}</span>
              <span>{{ task.loadedText }} / {{ task.totalText }}</span>
            </div>
            <div v-if="task.status === 'error'" class="upload-task-error">{{ task.error }}</div>
          </div>
        </div>

        <!-- 文档列表 -->
        <div class="kb-docs-title">已导入文档</div>
        <div>
          <div v-if="!kb.currentKbId" class="empty-docs">请先选择一个知识库</div>
          <div v-else-if="!kb.docs.length" class="empty-docs">暂无文档，上传后 AI 可基于文档内容回答</div>
          <div
            v-for="doc in kb.docs"
            :key="doc.id"
            class="doc-item"
            :class="{ 'doc-item-processing': ['PROCESSING','PENDING','PARSING','CHUNKING','EMBEDDING'].includes(doc.status) }"
          >
            <div class="doc-icon" :class="getFileIcon(doc.filename).cls" v-html="getFileIcon(doc.filename).icon"></div>
            <div class="doc-info">
              <div class="doc-name" :title="doc.filename">
                {{ doc.filename }}
                <span v-if="['PROCESSING','PENDING','PARSING','CHUNKING','EMBEDDING'].includes(doc.status)" class="doc-parsing-badge">
                  <svg class="inline-spinner" viewBox="0 0 24 24" fill="none" width="10" height="10">
                    <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="3" stroke-dasharray="14 50" stroke-linecap="round"/>
                  </svg>
                  {{ statusLabel(doc.status) }}
                </span>
                <span v-else-if="doc.status === 'FAILED'" class="doc-failed-badge" :title="doc.parseError">解析失败</span>
              </div>
              <div class="doc-meta">
                {{ doc.chunks > 0 ? doc.chunks + ' 个切片' : '待切片' }}
                {{ doc.size ? ` · ${formatFileSize(doc.size)}` : '' }}
                · {{ doc.uploadedAt }}
              </div>
            </div>
            <div class="doc-actions">
              <button class="doc-delete" type="button" title="从知识库删除此文档" @click="handleDeleteDoc(doc)"></button>
            </div>
          </div>
        </div>
      </div>

      <!-- 成员管理面板 -->
      <div v-if="kb.kbMembersVisible" class="kb-members-panel">
        <div class="kb-members-header">
          <h3>知识库成员</h3>
          <button class="kb-members-close" type="button" @click="kb.kbMembersVisible = false">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
              <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/>
            </svg>
          </button>
        </div>
        <div class="kb-members-add">
          <div class="kb-member-search-wrap">
            <input
              v-model.trim="memberUsername"
              type="text"
              placeholder="输入用户名搜索..."
              class="kb-member-input"
              autocomplete="off"
              @input="searchMembers"
              @blur="hideKbSugg"
              @focus="searchMembers"
            />
            <div v-if="kbSuggestions.length && kbSuggVisible" class="kb-member-suggestions">
              <button
                v-for="u in kbSuggestions"
                :key="u.userId"
                class="kb-member-suggestion-item"
                type="button"
                @mousedown.prevent="selectKbSugg(u)"
              >
                <span class="kb-member-sug-name">{{ u.username }}</span>
                <span class="kb-member-sug-id">{{ u.userId }}</span>
              </button>
            </div>
          </div>
          <select v-model="memberRole" class="kb-member-role-select">
            <option value="VIEWER">只读（VIEWER）</option>
            <option value="EDITOR">编辑（EDITOR）</option>
          </select>
          <button class="kb-member-add-btn" type="button" @click="addMember">添加</button>
        </div>
        <div class="kb-members-list">
          <div v-if="!kb.kbMembers.length" class="empty-hint">暂无成员</div>
          <div v-for="member in kb.kbMembers" :key="member.userId" class="kb-member-item">
            <span class="kb-member-id">
              {{ member.username || member.userId }}
              <small v-if="member.username" class="kb-member-sub-id">{{ member.userId }}</small>
            </span>
            <template v-if="member.role === 'OWNER'">
              <span class="kb-member-role owner-badge">所有者</span>
            </template>
            <template v-else>
              <select
                class="kb-member-role-inline"
                :value="member.role"
                @change="kb.updateKbMemberRole(member.userId, $event.target.value, org.currentOrgId)"
              >
                <option value="VIEWER">只读</option>
                <option value="EDITOR">编辑</option>
              </select>
              <button class="kb-member-remove-btn" type="button" @click="kb.removeKbMember(member.userId, org.currentOrgId)">
                <svg width="11" height="11" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/>
                </svg>
              </button>
            </template>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useKbStore } from '../stores/kb.js';
import { useOrgStore } from '../stores/org.js';
import { useSessionStore } from '../stores/sessions.js';
import { useUiStore } from '../stores/ui.js';
import { formatFileSize, getFileIcon } from '../js/utils.js';
import * as api from '../services/api.js';

const kb     = useKbStore();
const org    = useOrgStore();
const sess   = useSessionStore();
const ui     = useUiStore();
const router = useRouter();

const fileInputEl   = ref(null);
const dragOver      = ref(false);
const memberUsername = ref('');
const memberRole    = ref('VIEWER');
const memberUserId  = ref('');
const kbSuggestions = ref([]);
const kbSuggVisible = ref(false);

const queueSummary = computed(() => {
  const total = kb.uploadQueue.length;
  const done  = kb.uploadQueue.filter(t => t.status === 'done').length;
  return `${done}/${total} 完成`;
});

const queueFinished = computed(() =>
  kb.uploadQueue.length > 0 && kb.uploadQueue.every(t => ['done','error'].includes(t.status))
);

function statusLabel(status) {
  const map = { PENDING: '等待', PARSING: '解析中', CHUNKING: '切片中', EMBEDDING: '向量化中', PROCESSING: '处理中' };
  return map[status] || status;
}

// ── 知识库操作 ──────────────────────────────────────────────────────
async function handleCreateKb() {
  const form = await ui.showForm({
    title: '新建知识库',
    confirmText: '创建',
    fields: [
      { key: 'name',        label: '知识库名称', placeholder: '例如：产品文档、客户案例、内部 SOP' },
      { key: 'description', label: '描述（可选）', placeholder: '描述知识库的用途', multiline: true },
    ],
  });
  if (!form?.name?.trim()) return;
  try {
    await kb.createKb(form.name.trim(), form.description?.trim() || '', org.currentOrgId);
  } catch (err) {
    ui.showToast('error', err.message || '创建失败');
  }
}

async function handleEditKb(item) {
  const form = await ui.showForm({
    title: '编辑知识库',
    confirmText: '保存',
    fields: [
      { key: 'name',        label: '知识库名称', placeholder: item.name,        defaultValue: item.name },
      { key: 'description', label: '描述（可选）', placeholder: item.description, defaultValue: item.description || '', multiline: true },
    ],
  });
  if (!form?.name?.trim()) return;
  try {
    await kb.updateKb(item.id, form.name.trim(), form.description?.trim() || '', org.currentOrgId);
  } catch (err) {
    ui.showToast('error', err.message || '更新失败');
  }
}

async function handleDeleteKb(item) {
  const confirmed = await ui.showConfirm({
    title: '删除知识库',
    message: `确认删除知识库「${item.name}」？\n此操作不可恢复，所有文档和切片将被永久删除。`,
    confirmText: '删除',
    variant: 'danger',
  });
  if (!confirmed) return;
  try {
    await kb.deleteKb(item.id, org.currentOrgId);
    ui.showToast('success', `已删除：${item.name}`);
  } catch (err) {
    ui.showToast('error', err.message || '删除失败');
  }
}

// ── 文件上传 ────────────────────────────────────────────────────────
function triggerUpload() {
  if (!kb.currentKbId) { ui.showToast('warning', '请先选择或创建知识库'); return; }
  fileInputEl.value?.click();
}

function handleFileChange(event) {
  Array.from(event.target.files || []).forEach(f => kb.uploadFile(f, org.currentOrgId));
  event.target.value = '';
}

function handleDrop(event) {
  dragOver.value = false;
  if (!kb.currentKbId) { ui.showToast('warning', '请先选择或创建知识库'); return; }
  Array.from(event.dataTransfer.files || []).forEach(f => kb.uploadFile(f, org.currentOrgId));
}

// ── 文档操作 ────────────────────────────────────────────────────────
async function handleDeleteDoc(doc) {
  const confirmed = await ui.showConfirm({
    title: '删除文档',
    message: `确认删除文档「${doc.filename}」？`,
    confirmText: '删除',
    variant: 'danger',
  });
  if (!confirmed) return;
  try {
    await api.deleteDocument(kb.currentKbId, doc.id, org.currentOrgId);
    ui.showToast('success', `已删除文档：${doc.filename}`);
    await kb.loadDocs(org.currentOrgId);
  } catch (err) {
    ui.showToast('error', err.message || '删除失败');
  }
}

// ── 成员管理 ────────────────────────────────────────────────────────
async function openKbMembers() {
  kb.kbMembersVisible = true;
  await kb.loadKbMembers(org.currentOrgId);
}

let _searchTimer = null;
function searchMembers() {
  clearTimeout(_searchTimer);
  _searchTimer = setTimeout(async () => {
    if (!memberUsername.value.trim()) { kbSuggestions.value = []; return; }
    kbSuggestions.value = await api.searchUsers(memberUsername.value.trim());
    kbSuggVisible.value = true;
  }, 200);
}

function hideKbSugg() {
  setTimeout(() => { kbSuggVisible.value = false; }, 150);
}

function selectKbSugg(u) {
  memberUsername.value = u.username;
  memberUserId.value   = u.userId;
  kbSuggestions.value  = [];
  kbSuggVisible.value  = false;
}

async function addMember() {
  if (!memberUserId.value) { ui.showToast('warning', '请先从搜索结果中选择用户'); return; }
  try {
    await kb.addKbMember(memberUserId.value, memberRole.value, org.currentOrgId);
    ui.showToast('success', '成员已添加');
    memberUsername.value = '';
    memberUserId.value   = '';
  } catch (err) {
    ui.showToast('error', err.message || '添加失败');
  }
}

// ── 在对话中使用 ──────────────────────────────────────────────────────
function useKbInChat() {
  sess.currentKbId = kb.currentKbId;
  ui.showToast('success', `已在对话中关联「${kb.currentKbName}」`);
  router.push('/chat');
}
</script>
