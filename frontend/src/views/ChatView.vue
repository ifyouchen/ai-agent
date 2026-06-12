<template>
  <div class="chat-view">
    <!-- 消息列表 -->
    <div ref="chatEl" class="chat-messages">
      <!-- 欢迎页 -->
      <div v-if="sess.messages.length === 0" class="welcome">
        <div class="welcome-icon">
          <svg viewBox="0 0 32 32" fill="currentColor" width="40" height="40">
            <path d="M27.6 11.8c-1.8.2-3.4-.2-4.8-1.1-1.9-1.3-3-3.3-3.5-5.9-.1-.6-.8-.9-1.3-.5-2.5 1.7-4 4-4.4 6.9-2.2-1.2-4.9-1.5-8-.9-.6.1-.9.8-.6 1.3 1.4 2.6 3.3 4.6 5.7 5.9-1.2.8-2.5 1.1-3.9 1.1-.7 0-1.1.8-.7 1.4 2 3.3 5.4 5.2 9.7 5.2 6.1 0 10.7-3.8 11.6-9.2.6-.6 1.1-1.4 1.5-2.3.4-.9-.2-2-1.3-1.9Zm-8 6.6c-1.9 1.6-4.5 1.8-6.5.4 1.7-.4 3-1.2 4-2.5 1.4.7 3 .9 4.7.6-.5.6-1.2 1.1-2.2 1.5Z"/>
          </svg>
        </div>
        <h2>使用{{ sess.reactEnabled ? '专家模式' : '快速模式' }}开始对话</h2>
        <div class="welcome-modes">
          <button class="welcome-mode" :class="{ active: !sess.reactEnabled }" type="button" @click="sess.reactEnabled = false; sess.streamEnabled = true">
            <svg viewBox="0 0 24 24" fill="currentColor"><path d="m13 2-8 12h6l-1 8 9-13h-6l1-7Z"/></svg>
            快速模式
          </button>
          <button class="welcome-mode" :class="{ active: sess.reactEnabled }" type="button" @click="sess.reactEnabled = true; sess.streamEnabled = true">
            <svg viewBox="0 0 24 24" fill="none">
              <path d="M12 3 4 7.5v9L12 21l8-4.5v-9L12 3Z" stroke="currentColor" stroke-width="1.8"/>
              <path d="M8.5 9.8 12 7.8l3.5 2-3.5 2-3.5-2Z" stroke="currentColor" stroke-width="1.8"/>
            </svg>
            专家模式
          </button>
        </div>
        <div class="welcome-prompts">
          <button
            v-for="p in quickPrompts"
            :key="p.label"
            class="welcome-prompt-btn"
            type="button"
            @click="sendQuick(p.message)"
          >
            <span class="welcome-prompt-icon" v-html="p.icon"></span>
            <span>{{ p.label }}</span>
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
      />
    </div>

    <!-- 顶部操作按钮（清除记忆、导出） -->
    <div class="chat-toolbar">
      <button class="topbar-btn danger" type="button" @click="handleClearMemory">清除记忆</button>
      <button class="topbar-btn" type="button" @click="sess.exportCurrentSession" title="导出对话为 Markdown">
        <svg viewBox="0 0 24 24" fill="none" width="13" height="13">
          <path d="M12 15V3m0 12-4-4m4 4 4-4M2 17l.621 2.485A2 2 0 0 0 4.561 21h14.878a2 2 0 0 0 1.94-1.515L22 17"
                stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        导出
      </button>
    </div>

    <!-- 输入区域 -->
    <MessageInput @attach-kb="handleAttachKb" />
  </div>
</template>

<script setup>
import { nextTick, onUpdated, ref } from 'vue';
import { useRouter } from 'vue-router';
import MessageBubble from '../components/chat/MessageBubble.vue';
import MessageInput  from '../components/chat/MessageInput.vue';
import { useSessionStore } from '../stores/sessions.js';
import { useKbStore } from '../stores/kb.js';
import { useUiStore } from '../stores/ui.js';
import * as api from '../services/api.js';

const sess   = useSessionStore();
const kb     = useKbStore();
const ui     = useUiStore();
const router = useRouter();
const chatEl = ref(null);

const quickPrompts = [
  {
    label: '查询订单状态',
    message: '帮我查一下订单 #12345 的状态',
    icon: '<svg viewBox="0 0 24 24" fill="none" width="14" height="14"><path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2M9 5a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2M9 5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
  },
  {
    label: '查询今日天气',
    message: '北京今天天气怎么样？',
    icon: '<svg viewBox="0 0 24 24" fill="none" width="14" height="14"><path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  },
  {
    label: '了解我的功能',
    message: '帮我介绍一下你能做什么',
    icon: '<svg viewBox="0 0 24 24" fill="none" width="14" height="14"><circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="2"/><path d="M12 8v4M12 16h.01" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
  },
  {
    label: '查询账户余额',
    message: '查询用户 U001 的账户余额',
    icon: '<svg viewBox="0 0 24 24" fill="none" width="14" height="14"><rect x="2" y="5" width="20" height="14" rx="2" stroke="currentColor" stroke-width="2"/><path d="M2 10h20" stroke="currentColor" stroke-width="2"/></svg>',
  },
];

// 消息更新时自动滚动到底部
onUpdated(() => {
  nextTick(() => {
    if (chatEl.value) chatEl.value.scrollTop = chatEl.value.scrollHeight;
  });
});

async function sendQuick(text) {
  sess.messageInput = text;
  await sess.sendMessage(text, sess.currentKbId);
  sess.messageInput = '';
}

async function handleRegenerate(messageId) {
  await sess.regenerateMessage(messageId, sess.currentKbId);
}

function handleFeedback(messageId, fb) {
  sess.setFeedback(messageId, fb);
}

async function handleClearMemory() {
  const confirmed = await ui.showConfirm({
    title: '清除记忆',
    message: `确认清除当前会话的所有记忆？\n清除后对话将重新开始。`,
    confirmText: '清除',
    variant: 'danger',
  });
  if (!confirmed) return;
  try {
    sess.stopSessionGeneration(sess.sessionId, false);
    await api.clearMemory(sess.sessionId);
    sess.sessionMessages[sess.sessionId] = [];
    sess.messages = sess.sessionMessages[sess.sessionId];
    ui.showToast('success', '记忆已清除，对话重新开始');
  } catch {
    ui.showToast('error', '清除失败，请重试');
  }
}

async function handleAttachKb() {
  const kbs = kb.knowledgeBases;
  if (!kbs.length) {
    ui.showToast('warning', '暂无知识库，请先在「知识库」页上传文档');
    return;
  }
  const choices = kbs.slice(0, 4).map(k => ({
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
    sess.currentKbId = null;
    ui.showToast('info', '已取消关联知识库');
  } else {
    sess.currentKbId = Number(chosen);
    const found = kbs.find(k => k.id === sess.currentKbId);
    ui.showToast('success', `已关联知识库「${found?.name || chosen}」`);
  }
}
</script>

<style scoped>
.chat-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  position: relative;
}

.chat-toolbar {
  position: absolute;
  top: 10px;
  right: 16px;
  display: flex;
  gap: 8px;
  z-index: 10;
}
</style>
