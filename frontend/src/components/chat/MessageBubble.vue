<template>
  <div class="message" :class="message.role">
    <!-- AI 头像 -->
    <div v-if="message.role === 'ai'" class="avatar ai">AI</div>

    <div class="bubble-wrap">
      <!-- 消息气泡内容 -->
      <div class="bubble" v-html="displayHtml"></div>

      <!-- 消息底部信息：时间戳 + 耗时 -->
      <div v-if="message.timestamp" class="message-meta">
        <span class="message-time">{{ formatTime(message.timestamp) }}</span>
        <span v-if="message.durationMs" class="message-duration">
          · {{ (message.durationMs / 1000).toFixed(1) }}s
        </span>
      </div>

      <!-- AI 消息操作栏（hover 时显示） -->
      <div v-if="message.role === 'ai' && !isStreaming" class="message-actions">
        <!-- 复制消息文本 -->
        <button
          type="button"
          class="msg-action-btn"
          :class="{ 'copied': copyState }"
          title="复制内容"
          @click="copyMessage"
        >
          <svg v-if="!copyState" viewBox="0 0 24 24" fill="none" width="13" height="13">
            <rect x="9" y="9" width="13" height="13" rx="2" stroke="currentColor" stroke-width="2"/>
            <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" stroke="currentColor" stroke-width="2"/>
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="none" width="13" height="13">
            <path d="m5 12 4 4L19 6" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/>
          </svg>
          {{ copyState ? '已复制' : '复制' }}
        </button>

        <!-- 重新生成 -->
        <button
          type="button"
          class="msg-action-btn"
          title="重新生成"
          @click="$emit('regenerate', message.id)"
        >
          <svg viewBox="0 0 24 24" fill="none" width="13" height="13">
            <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            <path d="M21 3v5h-5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          重新生成
        </button>

        <!-- 点赞 -->
        <button
          type="button"
          class="msg-action-btn feedback-btn"
          :class="{ active: message.feedback === 'up' }"
          title="有帮助"
          @click="$emit('feedback', message.id, 'up')"
        >
          <svg viewBox="0 0 24 24" fill="none" width="14" height="14">
            <path d="M7 22V11M2 13v7a2 2 0 0 0 2 2h12.4a2 2 0 0 0 1.98-1.74L19.5 11H15V7a3 3 0 0 0-3-3h-1l-4 6v12Z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
                  :fill="message.feedback === 'up' ? 'currentColor' : 'none'"/>
          </svg>
        </button>

        <!-- 点踩 -->
        <button
          type="button"
          class="msg-action-btn feedback-btn"
          :class="{ active: message.feedback === 'down' }"
          title="没帮助"
          @click="$emit('feedback', message.id, 'down')"
        >
          <svg viewBox="0 0 24 24" fill="none" width="14" height="14">
            <path d="M17 2v11M22 11V4a2 2 0 0 0-2-2H7.6a2 2 0 0 0-1.98 1.74L4.5 13H9v4a3 3 0 0 0 3 3h1l4-6V2Z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
                  :fill="message.feedback === 'down' ? 'currentColor' : 'none'"/>
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue';
import { formatMarkdown } from '../../js/utils.js';

const props = defineProps({
  message:   { type: Object,  required: true },
  isStreaming: { type: Boolean, default: false },
});

defineEmits(['regenerate', 'feedback']);

const copyState = ref(false);

const displayHtml = computed(() => {
  const html = props.message.html || '';
  if (!looksLikeLegacyMarkdownHtml(html)) return html;
  return formatMarkdown(htmlToMarkdownText(html));
});

function looksLikeLegacyMarkdownHtml(html) {
  if (/<h[23]\b|<li\b|class=["']md-h[23]/i.test(html)) return false;
  return /(^|<br\s*\/?>|\n)\s*(#{1,3}\S|-\S)/i.test(html);
}

function htmlToMarkdownText(html) {
  const withLineBreaks = html
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(?:p|div|h[1-6]|li)>/gi, '\n');
  const tmp = document.createElement('div');
  tmp.innerHTML = withLineBreaks;
  return tmp.textContent || '';
}

function formatTime(ts) {
  if (!ts) return '';
  const d = new Date(ts);
  return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
}

function copyMessage() {
  const tmp = document.createElement('div');
  tmp.innerHTML = displayHtml.value;
  const text = tmp.innerText || tmp.textContent || '';
  navigator.clipboard.writeText(text).then(() => {
    copyState.value = true;
    setTimeout(() => { copyState.value = false; }, 1800);
  }).catch(() => {});
}
</script>
