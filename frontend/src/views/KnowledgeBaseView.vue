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
            <button class="kb-item-delete" type="button" title="删除知识库" @click.stop="handleDeleteKb(item)">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none">
                <path d="M3 6h18M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6M10 6V4h4v2" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </button>
          </div>
        </div>
      </div>

      <!-- 右侧：文档管理 / 测试查询 -->
      <div class="kb-current-area">
        <div class="kb-current-header">
          <!-- P1-6：标签页切换 -->
          <div class="kb-tabs">
            <button class="kb-tab" :class="{ active: activeTab === 'docs' }" type="button" @click="activeTab = 'docs'">
              文档管理
              <svg v-if="kb.docsLoading && activeTab === 'docs'" class="inline-spinner" viewBox="0 0 24 24" fill="none" width="12" height="12">
                <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
              </svg>
            </button>
            <button class="kb-tab" :class="{ active: activeTab === 'query' }" type="button" @click="activeTab = 'query'">
              测试查询
            </button>
          </div>
          <div style="display:flex;align-items:center;gap:6px;">
            <button v-if="kb.currentKbId && activeTab === 'docs'" class="kb-manage-members-btn" type="button"
                    title="刷新文档列表" @click="kb.loadDocs(org.currentOrgId)" style="padding:4px 8px;">↻</button>
            <button v-if="kb.currentKbId" class="kb-manage-members-btn" type="button"
                    title="管理知识库成员" @click="openKbMembers">成员</button>
            <button v-if="kb.currentKbId" class="kb-manage-members-btn" type="button"
                    style="color:var(--primary);" @click="useKbInChat">在对话中使用</button>
          </div>
        </div>

        <!-- 测试查询面板（P1-6） -->
        <div v-if="activeTab === 'query'" class="kb-query-panel">
          <div v-if="!kb.currentKbId" class="empty-docs">请先选择一个知识库</div>
          <template v-else>
            <div class="kb-query-input-row">
              <textarea
                v-model.trim="queryText"
                class="kb-query-input"
                placeholder="输入测试问题，验证知识库检索效果..."
                rows="3"
                @keydown.ctrl.enter.prevent="runQuery"
                @keydown.meta.enter.prevent="runQuery"
              ></textarea>
              <button class="kb-query-btn" type="button" :disabled="!queryText || queryLoading" @click="runQuery">
                <svg v-if="queryLoading" class="inline-spinner" viewBox="0 0 24 24" fill="none" width="14" height="14">
                  <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
                </svg>
                <svg v-else viewBox="0 0 24 24" fill="none" width="14" height="14">
                  <path d="m21 21-4.2-4.2m2.2-5.3a7.5 7.5 0 1 1-15 0 7.5 7.5 0 0 1 15 0Z" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                </svg>
                {{ queryLoading ? '检索中…' : '检索' }}
              </button>
            </div>
            <div class="kb-query-hint">Ctrl+Enter 发送</div>

            <!-- 查询结果 -->
            <div v-if="queryResult" class="kb-query-result">
              <div class="kb-query-result-header">
                <span class="kb-query-confidence" :class="confidenceClass">
                  置信度 {{ queryResult.confidence }}
                </span>
                <span v-if="!queryResult.answerFound" class="kb-query-no-answer">未找到相关内容</span>
              </div>
              <div class="kb-query-answer">{{ queryResult.answer }}</div>
              <div v-if="queryResult.citations?.length" class="kb-query-citations">
                <div class="kb-query-citations-title">引用来源：</div>
                <div v-for="(c, i) in queryResult.citations" :key="i" class="kb-query-citation">
                  <div class="kb-citation-header">
                    <span class="kb-citation-source">{{ c.source }}</span>
                    <span class="kb-citation-score">相关度 {{ c.score }}</span>
                  </div>
                  <div class="kb-citation-snippet">{{ c.snippet }}</div>
                </div>
              </div>
            </div>
            <div v-if="queryError" class="kb-query-error">{{ queryError }}</div>
          </template>
        </div>

        <!-- 上传区域（文档管理Tab） -->
        <div v-if="activeTab === 'docs'">
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
            class="doc-item-wrapper"
          >
            <div
              class="doc-item"
              :class="{ 'doc-item-processing': ['PROCESSING','PENDING','PARSING','CHUNKING','EMBEDDING'].includes(doc.status),
                        'doc-item-expanded': expandedDocId === doc.id }"
              @click="toggleDocChunks(doc)"
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
                  <span v-if="doc.chunks > 0" class="doc-meta-hint">点击查看切片</span>
                </div>
              </div>
              <div class="doc-actions" @click.stop>
                <!-- P3-15：展开/收起切片 -->
                <button v-if="doc.chunks > 0" class="doc-chunks-btn" type="button"
                        :title="expandedDocId === doc.id ? '收起切片' : '查看切片'"
                        @click="toggleDocChunks(doc)">
                  <svg viewBox="0 0 24 24" fill="none" width="12" height="12"
                       :style="{ transform: expandedDocId === doc.id ? 'rotate(180deg)' : '', transition: 'transform .2s' }">
                    <path d="m6 9 6 6 6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                  </svg>
                </button>
                <button class="doc-delete" type="button" title="从知识库删除此文档" @click="handleDeleteDoc(doc)">
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none">
                    <path d="M3 6h18M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6M10 6V4h4v2" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                  </svg>
                </button>
              </div>
            </div>

            <!-- 切片展开区（P3-15） -->
            <div v-if="expandedDocId === doc.id" class="doc-chunks-panel">
              <div v-if="docChunksCache[doc.id]?.loading" class="doc-chunks-loading">
                <svg class="inline-spinner" viewBox="0 0 24 24" fill="none" width="13" height="13">
                  <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
                </svg> 加载切片中…
              </div>
              <div v-else-if="docChunksCache[doc.id]?.error" class="doc-chunks-error">
                {{ docChunksCache[doc.id].error }}
              </div>
              <template v-else-if="docChunksCache[doc.id]?.chunks?.length">
                <div class="doc-chunks-header">
                  共 {{ docChunksCache[doc.id].total }} 个切片，显示前 {{ docChunksCache[doc.id].showing }} 个
                </div>
                <div v-for="chunk in docChunksCache[doc.id].chunks" :key="chunk.id" class="doc-chunk-item">
                  <div class="doc-chunk-meta">
                    第 {{ chunk.index + 1 }} 片
                    <span v-if="chunk.tokenCount"> · {{ chunk.tokenCount }} tokens</span>
                  </div>
                  <div class="doc-chunk-content">{{ chunk.content }}</div>
                </div>
              </template>
              <div v-else class="doc-chunks-empty">暂无切片数据</div>
            </div>
          </div>
        </div>
        </div><!-- end activeTab === 'docs' -->
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
import { computed, reactive, ref } from 'vue';
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

// P3-15：文档切片展开查看
const expandedDocId = ref(null);
const docChunksCache = reactive({});   // {docId: {chunks, total, showing, loading}}

async function toggleDocChunks(doc) {
  if (!['DONE'].includes(doc.status) && doc.chunks === 0) return; // 未处理完不展开
  if (expandedDocId.value === doc.id) {
    expandedDocId.value = null;
    return;
  }
  expandedDocId.value = doc.id;
  if (docChunksCache[doc.id]) return; // 已加载
  docChunksCache[doc.id] = { chunks: [], total: 0, showing: 0, loading: true };
  try {
    const res = await api.listDocumentChunks(kb.currentKbId, doc.id, org.currentOrgId, 20);
    docChunksCache[doc.id] = { ...res, loading: false };
  } catch (err) {
    docChunksCache[doc.id] = { chunks: [], total: 0, showing: 0, loading: false, error: err.message };
  }
}

// P1-6：标签页 + 测试查询状态
const activeTab   = ref('docs');
const queryText   = ref('');
const queryLoading = ref(false);
const queryResult = ref(null);
const queryError  = ref('');

const confidenceClass = computed(() => {
  const c = parseFloat(queryResult.value?.confidence ?? 0);
  if (c >= 0.7) return 'confidence-high';
  if (c >= 0.4) return 'confidence-mid';
  return 'confidence-low';
});

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

// ── 测试查询（P1-6） ─────────────────────────────────────────────────
async function runQuery() {
  if (!queryText.value || queryLoading.value) return;
  queryLoading.value = true;
  queryResult.value  = null;
  queryError.value   = '';
  try {
    const res = await api.queryKnowledgeBase(kb.currentKbId, queryText.value, org.currentOrgId);
    queryResult.value = res;
  } catch (err) {
    queryError.value = err.message || '查询失败，请重试';
  } finally {
    queryLoading.value = false;
  }
}

// ── 在对话中使用 ──────────────────────────────────────────────────────
function useKbInChat() {
  sess.currentKbId = kb.currentKbId;
  ui.showToast('success', `已在对话中关联「${kb.currentKbName}」`);
  router.push('/chat');
}
</script>

<style scoped>
/* ── 标签页（P1-6） ─────────────────────────────────────────────────── */
.kb-tabs {
  display: flex;
  gap: 0;
  border-bottom: 1.5px solid #EBEBEB;
  margin-bottom: -1px;
}
.kb-tab {
  padding: 7px 16px;
  font-size: 13px;
  font-weight: 500;
  color: #888;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
  transition: color .15s, border-color .15s;
}
.kb-tab.active { color: var(--primary, #4D6BFE); border-bottom-color: var(--primary, #4D6BFE); }
.kb-tab:hover:not(.active) { color: #444; }

/* ── 测试查询面板 ─────────────────────────────────────────────────────── */
.kb-query-panel {
  padding: 16px 0 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.kb-query-input-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}
.kb-query-input {
  flex: 1;
  padding: 10px 12px;
  border: 1.5px solid #EBEBEB;
  border-radius: 8px;
  font-size: 14px;
  resize: vertical;
  outline: none;
  transition: border-color .2s;
  font-family: inherit;
  line-height: 1.5;
}
.kb-query-input:focus { border-color: var(--primary, #4D6BFE); }
.kb-query-btn {
  padding: 10px 16px;
  background: var(--primary, #4D6BFE);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
  transition: opacity .2s;
}
.kb-query-btn:disabled { opacity: .5; cursor: not-allowed; }
.kb-query-btn:hover:not(:disabled) { opacity: .88; }
.kb-query-hint { font-size: 11px; color: #aaa; }

/* 查询结果 */
.kb-query-result {
  background: #F8F9FF;
  border: 1px solid #E0E4FF;
  border-radius: 10px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.kb-query-result-header { display: flex; align-items: center; gap: 8px; }
.kb-query-confidence {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 10px;
}
.confidence-high { background: #E8F8F0; color: #00A96E; }
.confidence-mid  { background: #FFF8E5; color: #D69E2E; }
.confidence-low  { background: #FFF0F0; color: #E53E3E; }
.kb-query-no-answer { font-size: 12px; color: #E53E3E; }
.kb-query-answer {
  font-size: 14px;
  line-height: 1.65;
  color: #1A1A1A;
  white-space: pre-wrap;
}
.kb-query-citations { display: flex; flex-direction: column; gap: 8px; }
.kb-query-citations-title { font-size: 12px; font-weight: 600; color: #555; }
.kb-query-citation {
  background: #fff;
  border: 1px solid #EBEBEB;
  border-radius: 8px;
  padding: 8px 10px;
}
.kb-citation-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}
.kb-citation-source { font-size: 12px; font-weight: 600; color: #4D6BFE; }
.kb-citation-score  { font-size: 11px; color: #999; }
.kb-citation-snippet {
  font-size: 12px;
  color: #666;
  line-height: 1.5;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
}
.kb-query-error {
  color: #E53E3E;
  font-size: 13px;
  padding: 8px 0;
}

/* ── 文档切片查看（P3-15） ────────────────────────────────────────────── */
.doc-item-wrapper { display: flex; flex-direction: column; }
.doc-item { cursor: pointer; }
.doc-item.doc-item-expanded { background: #F8F9FF; }
.doc-meta-hint { color: var(--primary, #4D6BFE); font-size: 10px; margin-left: 6px; opacity: .7; }
.doc-chunks-btn {
  padding: 3px 6px;
  border: 1px solid #EBEBEB;
  border-radius: 4px;
  background: none;
  cursor: pointer;
  color: #888;
  display: flex;
  align-items: center;
  transition: all .15s;
}
.doc-chunks-btn:hover { border-color: var(--primary, #4D6BFE); color: var(--primary, #4D6BFE); }
.doc-chunks-panel {
  background: #F8F9FF;
  border: 1px solid #E0E4FF;
  border-top: none;
  border-radius: 0 0 8px 8px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 400px;
  overflow-y: auto;
}
.doc-chunks-loading,
.doc-chunks-error,
.doc-chunks-empty {
  font-size: 12px;
  color: #aaa;
  text-align: center;
  padding: 12px 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.doc-chunks-error { color: #E53E3E; }
.doc-chunks-header {
  font-size: 11px;
  color: #888;
  padding-bottom: 6px;
  border-bottom: 1px solid #EBEBEB;
}
.doc-chunk-item {
  background: #fff;
  border: 1px solid #EBEBEB;
  border-radius: 6px;
  padding: 8px 10px;
}
.doc-chunk-meta {
  font-size: 10px;
  font-weight: 600;
  color: #4D6BFE;
  margin-bottom: 4px;
}
.doc-chunk-content {
  font-size: 12px;
  color: #555;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
