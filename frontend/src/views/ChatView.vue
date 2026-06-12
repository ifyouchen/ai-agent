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
        <!-- P2-12：当前 RAG 模式感知提示 -->
        <div v-if="sess.currentKbId" class="welcome-kb-hint">
          <svg viewBox="0 0 24 24" fill="none" width="13" height="13">
            <path d="M4 19V7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v7M4 19h16M4 19a2 2 0 0 1-2-2v-1h20v1a2 2 0 0 1-2 2"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
          RAG 模式：{{ kb.currentKbName || '已关联知识库' }}
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

const sess   = useSessionStore();
const kb     = useKbStore();
const ui     = useUiStore();
const router = useRouter();
const chatEl = ref(null);

// P2-11：快捷提示词使用通用示例，不使用硬编码测试数据
const quickPrompts = [
  {
    label: '帮我查询订单',
    message: '我想查一下我最近的订单状态，有什么方法吗？',
    icon: '<svg viewBox="0 0 24 24" fill="none" width="14" height="14"><path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2M9 5a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2M9 5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
  },
  {
    label: '查询当前天气',
    message: '帮我查一下北京当前的天气情况',
    icon: '<svg viewBox="0 0 24 24" fill="none" width="14" height="14"><path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  },
  {
    label: '了解你的能力',
    message: '你都能帮我做什么？请介绍一下你具备的功能',
    icon: '<svg viewBox="0 0 24 24" fill="none" width="14" height="14"><circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="2"/><path d="M12 8v4M12 16h.01" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
  },
  {
    label: '计算一道题',
    message: '帮我计算一下：(1234 + 5678) × 2 ÷ 3 等于多少？',
    icon: '<svg viewBox="0 0 24 24" fill="none" width="14" height="14"><rect x="3" y="3" width="18" height="18" rx="3" stroke="currentColor" stroke-width="2"/><path d="M9 9h.01M12 9h.01M15 9h.01M9 12h.01M12 12h.01M15 12h.01M9 15h3M15 15h.01" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
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

async function handleAttachKb() {
  const kbs = kb.knowledgeBases;
  if (!kbs.length) {
    ui.showToast('warning', '暂无知识库，请先在「知识库」页上传文档');
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
