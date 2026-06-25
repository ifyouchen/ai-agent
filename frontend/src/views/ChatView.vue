<template>
  <div class="chat-view" :class="{ 'is-empty': sess.messages.length === 0 }">
    <!-- 消息列表 -->
    <div ref="chatEl" class="chat-messages">
      <!-- 欢迎页 -->
      <div v-if="sess.messages.length === 0" class="welcome">
        <div class="welcome-title-row">
          <div class="welcome-icon">
          <svg viewBox="0 0 32 32" fill="currentColor" width="40" height="40">
            <path d="M27.6 11.8c-1.8.2-3.4-.2-4.8-1.1-1.9-1.3-3-3.3-3.5-5.9-.1-.6-.8-.9-1.3-.5-2.5 1.7-4 4-4.4 6.9-2.2-1.2-4.9-1.5-8-.9-.6.1-.9.8-.6 1.3 1.4 2.6 3.3 4.6 5.7 5.9-1.2.8-2.5 1.1-3.9 1.1-.7 0-1.1.8-.7 1.4 2 3.3 5.4 5.2 9.7 5.2 6.1 0 10.7-3.8 11.6-9.2.6-.6 1.1-1.4 1.5-2.3.4-.9-.2-2-1.3-1.9Zm-8 6.6c-1.9 1.6-4.5 1.8-6.5.4 1.7-.4 3-1.2 4-2.5 1.4.7 3 .9 4.7.6-.5.6-1.2 1.1-2.2 1.5Z"/>
          </svg>
          </div>
          <h2>使用{{ sess.reactEnabled ? '专家模式' : '快速模式' }}开始对话</h2>
        </div>
        <!-- P2-12：当前 RAG 模式感知提示 -->
        <div v-if="sess.currentKbId" class="welcome-kb-hint">
          <svg viewBox="0 0 24 24" fill="none" width="13" height="13">
            <path d="M4 19V7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v7M4 19h16M4 19a2 2 0 0 1-2-2v-1h20v1a2 2 0 0 1-2 2"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
          RAG 模式：{{ kb.currentKbName || '已关联知识库' }}
        </div>
        <div class="welcome-modes">
          <button class="welcome-mode" :class="{ active: !sess.reactEnabled }" type="button" @click="sess.setQuickMode()">
            <svg viewBox="0 0 24 24" fill="currentColor"><path d="m13 2-8 12h6l-1 8 9-13h-6l1-7Z"/></svg>
            快速模式
          </button>
          <button class="welcome-mode" :class="{ active: sess.reactEnabled }" type="button" @click="sess.setExpertMode()">
            <svg viewBox="0 0 24 24" fill="none">
              <path d="M12 3 4 7.5v9L12 21l8-4.5v-9L12 3Z" stroke="currentColor" stroke-width="1.8"/>
              <path d="M8.5 9.8 12 7.8l3.5 2-3.5 2-3.5-2Z" stroke="currentColor" stroke-width="1.8"/>
            </svg>
            专家模式
          </button>
        </div>
      </div>

      <!-- 消息列表 -->
      <MessageBubble
        v-for="msg in sess.messages"
        :key="msg.id"
        :message="msg"
        :is-streaming="sess.currentSessionSending && msg === sess.messages[sess.messages.length - 1]"
        @regenerate="handleRegenerate"
        @feedback="handleFeedback"
        @edit="handleEditMessage"
        @share="handleQuickShare"
      />
    </div>

    <!-- 输入区域 -->
    <MessageInput @attach-kb="handleAttachKb" @upload-files="handleUploadFiles" />
  </div>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import MessageBubble from '../components/chat/MessageBubble.vue';
import MessageInput  from '../components/chat/MessageInput.vue';
import { copyText } from '../js/utils.js';
import { useSessionStore } from '../stores/sessions.js';
import { useKbStore } from '../stores/kb.js';
import { useUiStore } from '../stores/ui.js';
import { useOrgStore } from '../stores/org.js';

const sess   = useSessionStore();
const kb     = useKbStore();
const ui     = useUiStore();
const org    = useOrgStore();
const router = useRouter();
const chatEl = ref(null);

// 只在切换会话或新增消息时即时定位到底部，避免 smooth scroll 产生“从上滚到底”的动画。
watch(
  () => [sess.sessionId, sess.messages.length],
  async () => {
    await nextTick();
    if (chatEl.value) {
      chatEl.value.scrollTop = chatEl.value.scrollHeight;
    }
  },
  { flush: 'post' }
);

async function handleRegenerate(messageId) {
  await sess.regenerateMessage(messageId, sess.currentKbId);
}

function handleFeedback(messageId, fb) {
  sess.setFeedback(messageId, fb);
}

function handleEditMessage(messageId) {
  sess.startEditingMessage(messageId);
}

async function handleQuickShare() {
  try {
    const share = await sess.createShareLink();
    if (!share?.url) return;
    const copied = await copyText(share.url);
    ui.showToast(copied ? 'success' : 'warning', copied ? '分享链接已复制' : '复制失败，请手动复制');
  } catch (err) {
    ui.showToast('error', err.message || '创建分享失败');
  }
}

async function handleAttachKb() {
  if (!kb.knowledgeBases.length && !kb.kbLoading) {
    await kb.loadKbs(org.currentOrgId);
  }
  const kbs = kb.knowledgeBases;
  if (!kbs.length) {
    ui.showToast('warning', '暂无知识库，请先在「知识库」页创建知识库并上传文档');
    router.push('/kb');
    return;
  }
  // P0-4: 去除 4 个上限，展示全部知识库
  const choices = kbs.map(k => ({
    value: String(k.id),
    label: k.name,
    desc: `${k.docCount || 0} 篇文档`,
  }));
  if (sess.currentKbId) {
    choices.unshift({ value: '', label: '取消关联知识库', desc: '恢复为普通对话模式' });
  }
  const chosen = await ui.showChoice({
    title: '关联知识库',
    message: '选择知识库后，本次对话将基于其内容生成答案（RAG 模式）',
    confirmText: '确认',
    choices,
    defaultValue: sess.currentKbId ? String(sess.currentKbId) : choices[0]?.value,
  });
  if (chosen === false || chosen === undefined) return;
  if (chosen === '') {
    sess.clearCurrentKb();
    ui.showToast('info', '已取消关联知识库');
  } else {
    sess.setCurrentKb(Number(chosen), org.currentOrgId);
    const found = kbs.find(k => k.id === sess.currentKbId);
    if (kb.currentKbId !== sess.currentKbId) {
      await kb.selectKb(sess.currentKbId, org.currentOrgId);
    } else if (!kb.docs.length) {
      await kb.loadDocs(org.currentOrgId);
    }
    ui.showToast('success', `已关联知识库「${found?.name || chosen}」`);
  }
}

async function handleUploadFiles(files) {
  if (!files?.length) return;
  if (!kb.knowledgeBases.length) {
    ui.showToast('warning', '暂无知识库，请先在「知识库」页创建知识库');
    router.push('/kb');
    return;
  }
  if (!kb.currentKbId) {
    ui.showToast('warning', '请先在「知识库」页选择一个知识库');
    router.push('/kb');
    return;
  }
  const targetName = kb.currentKbName || '当前知识库';
  ui.showToast('info', `开始上传到「${targetName}」`);
  await Promise.all(files.map(file => kb.uploadFile(file, org.currentOrgId)));
  ui.showToast('success', `已添加 ${files.length} 个文档到「${targetName}」`);
}
</script>

<style scoped>
.chat-view {
  display: flex;
  flex-direction: column;
  height: 100%;
}

/* P2-12：欢迎页 KB 状态提示 */
.welcome-kb-hint {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  background: #EEF1FF;
  color: #4D6BFE;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 500;
  margin-bottom: 4px;
}
</style>
