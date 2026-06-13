<template>
  <main class="share-page">
    <section class="share-shell">
      <div class="share-page-header">
        <div>
          <p class="share-kicker">AI Agent 会话分享</p>
          <h1>{{ share?.title || '会话分享' }}</h1>
        </div>
        <p v-if="share?.expiresAt" class="share-page-expire">
          有效期至 {{ formatTime(share.expiresAt) }}
        </p>
      </div>

      <div v-if="loading" class="share-state">正在加载分享内容…</div>
      <div v-else-if="error" class="share-state error">{{ error }}</div>
      <div v-else class="share-message-list">
        <article
          v-for="(msg, index) in share.messages"
          :key="index"
          class="share-message"
          :class="msg.role"
        >
          <div class="share-role">{{ msg.role === 'ai' ? 'AI' : '我' }}</div>
          <div class="share-content bubble" v-html="formatMarkdown(msg.content)"></div>
        </article>
      </div>
    </section>
  </main>
</template>

<script setup>
import { onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { getChatShare } from '../services/chatApi.js';
import { formatMarkdown } from '../js/utils.js';

const route = useRoute();
const loading = ref(true);
const error = ref('');
const share = ref(null);

onMounted(async () => {
  try {
    share.value = await getChatShare(route.params.shareId);
  } catch (err) {
    error.value = err.message || '分享不存在或已失效';
  } finally {
    loading.value = false;
  }
});

function formatTime(value) {
  return new Date(value).toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}
</script>

<style scoped>
.share-page {
  height: 100vh;
  width: 100%;
  overflow-y: auto;
  padding: 48px 20px;
  background: #f6f8fc;
  color: #1f2937;
}

.share-shell {
  width: min(860px, 100%);
  margin: 0 auto;
}

.share-page-header {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: flex-start;
  margin-bottom: 28px;
}

.share-kicker {
  margin: 0 0 8px;
  color: #667085;
  font-size: 13px;
}

.share-page-header h1 {
  margin: 0;
  font-size: 28px;
  line-height: 1.3;
}

.share-page-expire {
  margin: 4px 0 0;
  color: #8a93a5;
  font-size: 13px;
  white-space: nowrap;
}

.share-message-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.share-message {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 14px;
  padding: 18px;
  border: 1px solid #e5eaf3;
  border-radius: 16px;
  background: #fff;
}

.share-message.user {
  background: #f0f5ff;
}

.share-role {
  width: 34px;
  height: 34px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #4f67f5;
  color: #fff;
  font-weight: 700;
  font-size: 13px;
}

.share-message.user .share-role {
  background: #e3ebfb;
  color: #2d3442;
}

.share-content {
  min-width: 0;
  line-height: 1.72;
}

/* 通用段落间距 */
.share-content :deep(p) {
  margin: 0 0 8px;
}

.share-content :deep(*:first-child) {
  margin-top: 0;
}

.share-content :deep(*:last-child) {
  margin-bottom: 0;
}

/* 代码块盒子边距不受 first/last-child 清零影响 */
.share-content :deep(.code-block-wrap) {
  margin-top: 14px;
  margin-bottom: 16px;
}
.share-content :deep(.code-block-wrap:first-child) {
  margin-top: 14px;
}
.share-content :deep(.code-block-wrap:last-child) {
  margin-bottom: 16px;
}

/* ── AI 消息微调（等效于 .message.ai .bubble）── */
.share-message.ai .share-content :deep(.md-h2) {
  margin: 18px 0 8px;
  font-size: 16px;
  line-height: 1.45;
  font-weight: 700;
  color: #202633;
}

.share-message.ai .share-content :deep(.md-h3) {
  margin: 14px 0 6px;
  font-size: 15px;
  line-height: 1.45;
  font-weight: 700;
  color: #242a35;
}

.share-message.ai .share-content :deep(ul),
.share-message.ai .share-content :deep(ol) {
  margin: 6px 0 10px;
  padding-left: 1.35em;
}

.share-message.ai .share-content :deep(li) {
  margin: 3px 0;
  padding-left: 2px;
}

.share-message.ai .share-content :deep(li > p) {
  margin: 0;
}

.share-message.ai .share-content :deep(blockquote) {
  margin: 10px 0;
  padding: 2px 0 2px 12px;
  border-left: 3px solid #dfe6f3;
  color: #586174;
}

.share-message.ai .share-content :deep(hr) {
  margin: 14px 0;
  border: 0;
  border-top: 1px solid #edf1f7;
}

.share-message.ai .share-content :deep(table) {
  width: 100%;
  margin: 10px 0 12px;
  border-collapse: collapse;
  font-size: 14px;
  line-height: 1.55;
}

.share-message.ai .share-content :deep(th),
.share-message.ai .share-content :deep(td) {
  padding: 7px 9px;
  border: 1px solid #e5eaf2;
  text-align: left;
  vertical-align: top;
}

.share-message.ai .share-content :deep(th) {
  background: #f7f9fd;
  font-weight: 700;
}

.share-state {
  padding: 28px;
  border: 1px solid #e5eaf3;
  border-radius: 16px;
  background: #fff;
  color: #667085;
  text-align: center;
}

.share-state.error {
  color: #c24141;
}

@media (max-width: 640px) {
  .share-page {
    padding: 28px 12px;
  }

  .share-page-header {
    flex-direction: column;
    gap: 10px;
  }

  .share-page-header h1 {
    font-size: 22px;
  }

  .share-message {
    grid-template-columns: 1fr;
    gap: 10px;
    padding: 14px;
  }
}
</style>
