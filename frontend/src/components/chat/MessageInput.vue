<template>
  <div class="chat-input-area">
    <div class="input-wrapper">
      <textarea
        id="messageInput"
        ref="inputEl"
        v-model="inputText"
        :disabled="sess.currentSessionSending"
        :placeholder="enterToSendHint"
        rows="1"
        @blur="handleInputBlur"
        @click="updateMentionState"
        @input="handleInput"
        @keydown="handleKeydown"
      ></textarea>
      <div v-if="mentionVisible" class="doc-mention-popover" @mousedown.prevent>
        <div class="doc-mention-header">
          <span>引用文档</span>
          <small v-if="currentKbName">{{ currentKbName }}</small>
        </div>
        <div v-if="!sess.currentKbId" class="doc-mention-empty">
          先关联知识库后可 @ 文档
        </div>
        <div v-else-if="kb.docsLoading" class="doc-mention-empty">
          正在加载文档…
        </div>
        <div v-else-if="!mentionDocs.length" class="doc-mention-empty">
          当前知识库暂无可检索文档
        </div>
        <template v-else>
          <button
            v-for="(doc, index) in mentionDocs"
            :key="doc.id"
            class="doc-mention-item"
            :class="{ active: index === mentionActiveIndex }"
            type="button"
            @mousedown.prevent="insertMention(doc)"
          >
            <span class="doc-mention-icon" :class="getDocIconClass(doc.filename)"></span>
            <span class="doc-mention-main">
              <span class="doc-mention-name">{{ doc.filename }}</span>
              <small>{{ doc.chunks || 0 }} 个切片</small>
            </span>
          </button>
        </template>
      </div>
      <div class="composer-footer">
        <div class="composer-tools">
          <button
            class="quick-prompt tool-chip"
            type="button"
            :class="{ active: sess.reactEnabled }"
            @click="sess.toggleExpertMode()"
          >
            <svg viewBox="0 0 24 24" fill="currentColor"><path d="m13 2-8 12h6l-1 8 9-13h-6l1-7Z"/></svg>
            深度思考
          </button>
          <button
            class="quick-prompt tool-chip"
            type="button"
            :class="{ active: sess.currentKbId }"
            title="选择知识库用于检索增强回答"
            @click="$emit('attachKb')"
          >
            <svg viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.8"/>
              <path d="M3 12h18M12 3c2.3 2.6 3.5 5.6 3.5 9s-1.2 6.4-3.5 9M12 3c-2.3 2.6-3.5 5.6-3.5 9s1.2 6.4 3.5 9"
                    stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
            </svg>
            知识库检索
          </button>
        </div>
        <div class="composer-actions">
          <!-- 上传文档到当前知识库 -->
          <input
            ref="fileInputEl"
            class="composer-file-input"
            type="file"
            multiple
            accept=".pdf,.doc,.docx,.txt,.md"
            @change="handleFileChange"
          />
          <button
            class="attach-btn"
            type="button"
            title="上传文档到知识库"
            @click="openFilePicker"
          >
            <svg viewBox="0 0 24 24" fill="none">
              <path d="m20 11.5-7.7 7.7a5.2 5.2 0 0 1-7.4-7.4l8.4-8.4a3.6 3.6 0 0 1 5.1 5.1l-8.4 8.4a2 2 0 0 1-2.8-2.8l7.6-7.6"
                    stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
          <!-- 停止按钮 -->
          <button
            v-if="sess.currentSessionSending"
            class="stop-btn"
            type="button"
            title="停止生成"
            @click="sess.stopGeneration()"
          >
            <svg viewBox="0 0 24 24" fill="currentColor"><rect x="7" y="7" width="10" height="10" rx="2"/></svg>
          </button>
          <!-- 发送按钮 -->
          <button
            v-else
            class="send-btn"
            type="button"
            :disabled="!inputText.trim()"
            @click="handleSend"
          >
            <svg viewBox="0 0 24 24" fill="none">
              <path d="M12 19V5M6 11l6-6 6 6" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
        </div>
      </div>
    </div>

    <!-- 输入区域底部提示 -->
    <div class="input-hints">
      <span v-if="sess.currentKbId" class="kb-active-badge">
        <svg viewBox="0 0 24 24" fill="none" width="11" height="11">
          <path d="M4 19V7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v7M4 19h16M4 19a2 2 0 0 1-2-2v-1h20v1a2 2 0 0 1-2 2"
                stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        </svg>
        {{ currentKbName }}
        <button class="kb-active-clear" type="button" title="取消关联知识库" @click.stop="sess.currentKbId = null">×</button>
      </span>
      <span class="hint-text">
        {{ sess.enterToSend ? 'Enter 发送 · Shift+Enter 换行' : 'Ctrl+Enter 发送 · Enter 换行' }}
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref } from 'vue';
import { useSessionStore } from '../../stores/sessions.js';
import { useKbStore } from '../../stores/kb.js';
import { useOrgStore } from '../../stores/org.js';

const emit = defineEmits(['attachKb', 'uploadFiles']);

const sess = useSessionStore();
const kb   = useKbStore();
const org  = useOrgStore();
const inputEl = ref(null);
const fileInputEl = ref(null);
const mentionOpen = ref(false);
const mentionQuery = ref('');
const mentionStart = ref(-1);
const mentionActiveIndex = ref(0);
let mentionCloseTimer = null;

const inputText = computed({
  get: () => sess.messageInput ?? '',
  set: v => { sess.messageInput = v; },
});

const currentKbName = computed(() => kb.currentKbName);
const mentionSourceDocs = computed(() =>
  kb.docs.filter(doc => doc.filename && (doc.status === 'DONE' || doc.chunks > 0))
);
const mentionDocs = computed(() => {
  const query = mentionQuery.value.trim().toLowerCase();
  const docs = query
    ? mentionSourceDocs.value.filter(doc => doc.filename.toLowerCase().includes(query))
    : mentionSourceDocs.value;
  return docs.slice(0, 8);
});
const mentionVisible = computed(() => mentionOpen.value);

const enterToSendHint = computed(() =>
  '给 DeepSeek 发送消息'
);

function autoResize() {
  const el = inputEl.value;
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = Math.min(el.scrollHeight, 160) + 'px';
}

function handleInput() {
  autoResize();
  updateMentionState();
}

async function ensureMentionDocs() {
  if (!sess.currentKbId) return;
  if (kb.currentKbId !== sess.currentKbId) {
    await kb.selectKb(sess.currentKbId, org.currentOrgId);
    return;
  }
  if (!kb.docs.length && !kb.docsLoading) {
    await kb.loadDocs(org.currentOrgId);
  }
}

function updateMentionState() {
  clearTimeout(mentionCloseTimer);
  const el = inputEl.value;
  if (!el) return;
  const caret = el.selectionStart ?? inputText.value.length;
  const beforeCaret = inputText.value.slice(0, caret);
  const match = beforeCaret.match(/(^|\s)@([^\s@]*)$/);
  if (!match) {
    closeMention();
    return;
  }
  mentionStart.value = beforeCaret.length - match[2].length - 1;
  mentionQuery.value = match[2];
  mentionOpen.value = true;
  mentionActiveIndex.value = 0;
  ensureMentionDocs();
}

function closeMention() {
  mentionOpen.value = false;
  mentionQuery.value = '';
  mentionStart.value = -1;
  mentionActiveIndex.value = 0;
}

function handleInputBlur() {
  mentionCloseTimer = setTimeout(closeMention, 120);
}

function insertMention(doc) {
  const el = inputEl.value;
  if (!el || mentionStart.value < 0) return;
  const caret = el.selectionStart ?? inputText.value.length;
  const before = inputText.value.slice(0, mentionStart.value);
  const after = inputText.value.slice(caret);
  const insertion = `@${doc.filename} `;
  inputText.value = before + insertion + after;
  closeMention();
  nextTick(() => {
    const pos = before.length + insertion.length;
    el.focus();
    el.setSelectionRange(pos, pos);
    autoResize();
  });
}

function handleMentionKeydown(event) {
  if (!mentionOpen.value) return false;
  if (event.key === 'Escape') {
    event.preventDefault();
    closeMention();
    return true;
  }
  if (!mentionDocs.value.length) return false;
  if (event.key === 'ArrowDown') {
    event.preventDefault();
    mentionActiveIndex.value = (mentionActiveIndex.value + 1) % mentionDocs.value.length;
    return true;
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault();
    mentionActiveIndex.value = (mentionActiveIndex.value - 1 + mentionDocs.value.length) % mentionDocs.value.length;
    return true;
  }
  if (event.key === 'Enter' || event.key === 'Tab') {
    event.preventDefault();
    insertMention(mentionDocs.value[mentionActiveIndex.value]);
    return true;
  }
  return false;
}

function handleKeydown(event) {
  if (handleMentionKeydown(event)) return;
  if (event.key === 'Enter') {
    if (sess.enterToSend) {
      if (event.shiftKey) return;
      event.preventDefault();
      handleSend();
    } else {
      if (event.ctrlKey || event.metaKey) {
        event.preventDefault();
        handleSend();
      }
    }
  }
}

function openFilePicker() {
  fileInputEl.value?.click?.();
}

function handleFileChange(event) {
  const files = Array.from(event.target.files || []);
  if (files.length) emit('uploadFiles', files);
  event.target.value = '';
}

async function handleSend() {
  const text = inputText.value.trim();
  if (!text || sess.currentSessionSending) return;
  const message = buildMentionAwareMessage(text);
  inputText.value = '';
  closeMention();
  nextTick(() => {
    const el = inputEl.value;
    if (el) el.style.height = 'auto';
  });
  await sess.sendMessage(text, sess.currentKbId, message);
}

function buildMentionAwareMessage(text) {
  const matchedDocs = mentionSourceDocs.value
    .filter(doc => text.includes(`@${doc.filename}`))
    .map(doc => doc.filename);
  if (!matchedDocs.length) return text;
  if (!sess.currentKbId && kb.currentKbId) {
    sess.currentKbId = kb.currentKbId;
  }
  const docList = matchedDocs.map(name => `《${name}》`).join('、');
  return `${text}\n\n请优先基于已引用文档 ${docList} 的内容进行检索和回答；如果文档中没有依据，请明确说明。`;
}

function getDocIconClass(filename = '') {
  const lower = filename.toLowerCase();
  if (lower.endsWith('.pdf')) return 'pdf';
  if (lower.endsWith('.doc') || lower.endsWith('.docx')) return 'word';
  if (lower.endsWith('.md')) return 'md';
  return 'text';
}
</script>

<style scoped>
@import '../../css/components/message-input.css';
</style>
