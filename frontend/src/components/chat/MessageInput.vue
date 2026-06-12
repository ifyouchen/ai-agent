<template>
  <div class="chat-input-area">
    <div class="input-wrapper">
      <textarea
        ref="inputEl"
        v-model="inputText"
        :disabled="sess.currentSessionSending"
        :placeholder="enterToSendHint"
        rows="1"
        @input="autoResize"
        @keydown="handleKeydown"
      ></textarea>
      <div class="composer-footer">
        <div class="composer-tools">
          <button
            class="quick-prompt tool-chip"
            type="button"
            :class="{ active: !sess.reactEnabled }"
            @click="sess.reactEnabled = false; sess.streamEnabled = true"
          >
            <svg viewBox="0 0 24 24" fill="currentColor"><path d="m13 2-8 12h6l-1 8 9-13h-6l1-7Z"/></svg>
            快速模式
          </button>
          <button
            class="quick-prompt tool-chip"
            type="button"
            :class="{ active: sess.reactEnabled }"
            @click="sess.reactEnabled = true; sess.streamEnabled = true"
          >
            <svg viewBox="0 0 24 24" fill="none">
              <path d="M12 3 4 7.5v9L12 21l8-4.5v-9L12 3Z" stroke="currentColor" stroke-width="1.8"/>
              <path d="M8.5 9.8 12 7.8l3.5 2-3.5 2-3.5-2Z" stroke="currentColor" stroke-width="1.8"/>
            </svg>
            专家模式
          </button>
          <button
            class="quick-prompt tool-chip"
            type="button"
            :class="{ active: sess.enterToSend }"
            :title="sess.enterToSend ? '当前：Enter 发送，点击切换为 Ctrl+Enter' : '当前：Ctrl+Enter 发送，点击切换为 Enter'"
            @click="sess.enterToSend = !sess.enterToSend"
          >
            <svg viewBox="0 0 24 24" fill="none" width="12" height="12">
              <path d="M20 6H4M4 12h10M4 18h6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              <path d="m16 15 3 3-3 3" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            {{ sess.enterToSend ? 'Enter 发送' : 'Ctrl+Enter' }}
          </button>
        </div>
        <div class="composer-actions">
          <!-- KB 关联按钮 -->
          <button
            class="attach-btn"
            :class="{ active: sess.currentKbId }"
            type="button"
            title="关联知识库"
            @click="$emit('attachKb')"
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

defineEmits(['attachKb']);

const sess = useSessionStore();
const kb   = useKbStore();
const inputEl = ref(null);

const inputText = computed({
  get: () => sess.messageInput ?? '',
  set: v => { sess.messageInput = v; },
});

const currentKbName = computed(() => kb.currentKbName);

const enterToSendHint = computed(() =>
  sess.enterToSend
    ? '输入消息，Enter 发送，Shift+Enter 换行...'
    : '输入消息，Ctrl+Enter 发送...'
);

function autoResize() {
  const el = inputEl.value;
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = Math.min(el.scrollHeight, 160) + 'px';
}

function handleKeydown(event) {
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

async function handleSend() {
  const text = inputText.value.trim();
  if (!text || sess.currentSessionSending) return;
  inputText.value = '';
  nextTick(() => {
    const el = inputEl.value;
    if (el) el.style.height = 'auto';
  });
  await sess.sendMessage(text, sess.currentKbId);
}
</script>
