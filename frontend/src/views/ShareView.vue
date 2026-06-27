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
import {onMounted, ref} from 'vue';
import {useRoute} from 'vue-router';
import {getChatShare} from '../services/chatApi.js';
import { setupCopyCodeHandler } from '../js/utils.js';
import { formatMarkdown } from '../js/markdown.js';

const route = useRoute();
const loading = ref(true);
const error = ref('');
const share = ref(null);

onMounted(async () => {
  // 代码块复制/下载按钮事件委托（分享页独立挂载，不经过 MainLayout）
  setupCopyCodeHandler();
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

/* ── 内容区基础排版（等效 .message.ai .bubble / .message.user .bubble）── */
.share-message.ai .share-content {
  font-size: 16px;
  line-height: 1.68;
  color: #242a35;
}

.share-message.user .share-content {
  font-size: 15px;
  line-height: 1.65;
  color: #20242c;
}

.share-content {
  min-width: 0;
  word-break: break-word;
  overflow-wrap: break-word;
}

/* 段落 */
.share-content :deep(p) {
  margin: 0 0 8px;
}

/* 用户消息保留换行（等效 .message.user .bubble p { white-space: pre-wrap }） */
.share-message.user .share-content :deep(p) {
  margin: 0;
  white-space: pre-wrap;
}

.share-content :deep(*:first-child) {
  margin-top: 0;
}

.share-content :deep(*:last-child) {
  margin-bottom: 0;
}

/* 标题 */
.share-message.ai .share-content :deep(.md-h2) {
  margin: 18px 0 8px;
  font-size: 17px;
  line-height: 1.45;
  font-weight: 700;
  color: #202633;
}

.share-message.ai .share-content :deep(.md-h3) {
  margin: 14px 0 6px;
  font-size: 16px;
  line-height: 1.45;
  font-weight: 700;
  color: #242a35;
}

.share-message.user .share-content :deep(.md-h2) {
  margin: 14px 0 6px;
  font-size: 16px;
  font-weight: 600;
  color: #20242c;
}

.share-message.user .share-content :deep(.md-h3) {
  margin: 12px 0 5px;
  font-size: 15px;
  font-weight: 600;
  color: #20242c;
}

/* 列表 */
.share-content :deep(ul),
.share-content :deep(ol) {
  margin: 6px 0 10px;
  padding-left: 1.35em;
}

.share-content :deep(li) {
  margin: 3px 0;
  padding-left: 2px;
}

.share-content :deep(li > p) {
  margin: 0;
}

/* 用户消息列表也保留换行 */
.share-message.user .share-content :deep(li) {
  white-space: pre-wrap;
}

/* 引用 */
.share-content :deep(blockquote) {
  margin: 10px 0;
  padding: 2px 0 2px 12px;
  border-left: 3px solid #dfe6f3;
  color: #586174;
}

/* 分割线 */
.share-content :deep(hr) {
  margin: 14px 0;
  border: 0;
  border-top: 1px solid #edf1f7;
}

/* 表格 */
.share-content :deep(table) {
  width: 100%;
  margin: 10px 0 12px;
  border-collapse: collapse;
  font-size: 14px;
  line-height: 1.55;
}

.share-content :deep(th),
.share-content :deep(td) {
  padding: 7px 9px;
  border: 1px solid #e5eaf2;
  text-align: left;
  vertical-align: top;
}

.share-content :deep(th) {
  background: #f7f9fd;
  font-weight: 700;
}

/* 链接 */
.share-content :deep(a) {
  color: #2f6df6;
  text-decoration: none;
}

.share-content :deep(a:hover) {
  text-decoration: underline;
}

/* 文本格式 */
.share-content :deep(strong) {
  font-weight: 700;
}

.share-content :deep(em) {
  font-style: italic;
}

.share-content :deep(del) {
  text-decoration: line-through;
  color: #8a93a5;
}

/* 行内代码 */
.share-message.ai .share-content :deep(.inline-code) {
  background: #f0f0f2;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 13px;
  color: #c0341d;
  font-family: 'JetBrains Mono', 'Fira Code', 'SF Mono', Consolas, monospace;
}

.share-message.user .share-content :deep(.inline-code) {
  background: rgba(79, 103, 245, 0.12);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 13px;
  color: #2d3442;
  font-family: 'JetBrains Mono', 'Fira Code', 'SF Mono', Consolas, monospace;
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

.share-message.ai .share-content :deep(.code-block-wrap) {
  max-width: 100%;
}

.share-message.ai .share-content :deep(.code-block) {
  max-width: 100%;
  margin: 0;
  white-space: pre;
}

/* 独立 pre（无 code-block-wrap 包裹时的兜底） */
.share-content :deep(pre:not(.code-block)) {
  background: #f7f8fa;
  border: 1px solid #eef1f5;
  border-radius: 14px;
  padding: 14px 16px;
  overflow-x: auto;
  font-size: 14px;
  line-height: 1.7;
  font-family: 'JetBrains Mono', 'Fira Code', 'SF Mono', Consolas, monospace;
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
