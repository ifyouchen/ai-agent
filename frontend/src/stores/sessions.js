/**
 * Sessions Store — 会话与消息管理
 *
 * 迁移自 App.vue 中的会话/消息相关 reactive 状态与方法：
 *   - sessions, sessionId, messages, sessionMessages, sessionRuntime
 *   - sendMessage, stopGeneration, switchSession, removeSession, newSession
 *   - loadSessions, saveSessions, syncToServer
 */
import { defineStore } from 'pinia';
import { computed, nextTick, reactive, ref, watch } from 'vue';
import * as api from '../services/api.js';
import { formatMarkdown } from '../js/utils.js';
import { useUiStore } from './ui.js';
import {
  EXPERT_MODEL,
  MAX_MSGS,
  MAX_SESSIONS,
  QUICK_MODEL,
  SAVE_DEBOUNCE,
  escapeHtml,
  generateId,
  renderReactBubble,
  storageKey,
  stripHtml,
} from './sessionUtils.js';

export const useSessionStore = defineStore('sessions', () => {
  const ui = useUiStore();

  // ── 状态 ─────────────────────────────────────────────────────────────
  const sessions       = ref([]);            // [{id, title, createdAt}]
  const sessionId      = ref('');            // 当前会话 ID
  const messages       = ref([]);            // 当前会话消息列表（指针）
  const sessionMessages = reactive({});      // {sessionId: [messages]}
  const sessionRuntime  = reactive({});      // {sessionId: {sending,eventSource,...}}
  const reactEnabled   = ref(false);
  const streamEnabled  = ref(true);
  const enterToSend    = ref(true);
  const model          = ref(QUICK_MODEL);
  const currentKbId    = ref(null);
  const messageInput   = ref('');

  let _saveTimer = null;

  // ── 计算属性 ──────────────────────────────────────────────────────────
  const currentSessionSending = computed(() =>
    sessionRuntime[sessionId.value]?.sending ?? false
  );

  const currentSessionTitle = computed(() => {
    const s = sessions.value.find(s => s.id === sessionId.value);
    return s?.title || '新对话';
  });

  const activeModel = computed(() =>
    reactEnabled.value ? EXPERT_MODEL : QUICK_MODEL
  );

  function setQuickMode() {
    reactEnabled.value = false;
    streamEnabled.value = true;
    model.value = QUICK_MODEL;
  }

  function setExpertMode() {
    reactEnabled.value = true;
    streamEnabled.value = true;
    model.value = EXPERT_MODEL;
  }

  function toggleExpertMode() {
    if (reactEnabled.value) setQuickMode();
    else setExpertMode();
  }

  // ── 会话持久化 ────────────────────────────────────────────────────────
  function scheduleSave() {
    clearTimeout(_saveTimer);
    _saveTimer = setTimeout(saveSessions, SAVE_DEBOUNCE);
  }

  function saveSessions() {
    const userId = api.getUser()?.userId;
    if (!userId) return;
    const payload = sessions.value.slice(0, MAX_SESSIONS).map(s => ({
      ...s,
      messages: (sessionMessages[s.id] || []).slice(-MAX_MSGS).map(m => ({
        id: m.id, role: m.role,
        html: m.html, timestamp: m.timestamp, durationMs: m.durationMs,
        feedback: m.feedback,
      })),
    }));
    try { localStorage.setItem(storageKey(userId), JSON.stringify(payload)); } catch {}
  }

  function loadSessions() {
    const userId = api.getUser()?.userId;
    if (!userId) return;
    try {
      const raw = localStorage.getItem(storageKey(userId));
      if (!raw) return;
      const data = JSON.parse(raw);
      if (!Array.isArray(data)) return;
      data.slice(0, MAX_SESSIONS).forEach(s => {
        if (!s.id) return;
        if (!sessions.value.find(x => x.id === s.id)) {
          sessions.value.push({ id: s.id, title: s.title || '历史对话', createdAt: s.createdAt || Date.now() });
        }
        sessionMessages[s.id] = (s.messages || []).map(m => ({ ...m }));
      });
    } catch {}
  }

  async function loadSessionsFromServer() {
    try {
      const serverSessions = await api.listChatSessions();
      if (!Array.isArray(serverSessions)) return;
      serverSessions.forEach(s => {
        const id = s.sessionId || s.id;
        if (!id) return;
        if (!sessions.value.find(x => x.id === id)) {
          sessions.value.push({ id, title: s.title || '历史对话', createdAt: s.createdAt || Date.now() });
        }
        if (!sessionMessages[id]) sessionMessages[id] = [];
      });
      if (sessions.value.length) {
        const firstId = sessions.value[0].id;
        if (!sessionMessages[firstId]?.length) {
          await lazyLoadSessionMessages(firstId);
        }
      }
    } catch {}
  }

  async function syncLocalToServer() {
    const userId = api.getUser()?.userId;
    if (!userId) return;
    try {
      const payload = sessions.value.slice(0, MAX_SESSIONS).map(s => ({
        id: s.id, title: s.title, createdAt: s.createdAt,
        messages: (sessionMessages[s.id] || []).slice(-MAX_MSGS).map(m => ({
          role: m.role,
          content: stripHtml(m.html),
          timestamp: m.timestamp,
        })),
      }));
      await api.syncChatSessions(payload);
    } catch {}
  }

  async function lazyLoadSessionMessages(id) {
    if (!id || (sessionMessages[id] && sessionMessages[id].length)) return;
    try {
      const msgs = await api.getChatMessages(id);
      if (Array.isArray(msgs) && msgs.length) {
        sessionMessages[id] = msgs.map(m => ({
          id: m.id || generateId(),
          role: m.role,
          html: formatMarkdown(m.content || ''),
          timestamp: m.createdAt ? new Date(m.createdAt).getTime() : Date.now(),
          durationMs: 0,
          feedback: m.feedback || null,
        }));
      }
    } catch {}
  }

  // ── 初始化 ────────────────────────────────────────────────────────────
  async function init() {
    loadSessions();
    await loadSessionsFromServer();
    syncLocalToServer();

    if (!sessions.value.length) {
      newSession();
    } else {
      const id = sessions.value[0].id;
      sessionId.value = id;
      messages.value  = sessionMessages[id] || [];
    }

    watch(messages, scheduleSave, { deep: true });
    watch(sessions, scheduleSave, { deep: true });
  }

  // ── 会话操作 ──────────────────────────────────────────────────────────
  function newSession() {
    const id = generateId();
    sessions.value.unshift({ id, title: '新对话', createdAt: Date.now() });
    sessionMessages[id] = [];
    sessionId.value     = id;
    messages.value      = sessionMessages[id];
  }

  async function switchSession(id) {
    if (!sessionMessages[id]) sessionMessages[id] = [];
    sessionId.value = id;
    messages.value  = sessionMessages[id];
    await lazyLoadSessionMessages(id);
    messages.value = sessionMessages[id];
  }

  async function removeSession(id) {
    const idx = sessions.value.findIndex(s => s.id === id);
    if (idx < 0) return;
    const target = sessions.value[idx];

    const confirmed = await ui.showConfirm({
      title: '删除会话',
      message: `确认删除会话「${target.title}」？删除后不可恢复。`,
      confirmText: '删除',
      variant: 'danger',
    });
    if (!confirmed) return;

    stopSessionGeneration(id, false);
    sessions.value.splice(idx, 1);
    delete sessionMessages[id];
    delete sessionRuntime[id];

    api.deleteChatSession(id).catch(() => {});
    ui.showToast('info', `已删除会话：${target.title}`);

    if (sessionId.value === id) {
      if (sessions.value.length) switchSession(sessions.value[0].id);
      else newSession();
    }
  }

  function updateSessionTitle(text, id = sessionId.value) {
    const session = sessions.value.find(s => s.id === id);
    if (session && session.title === '新对话') {
      session.title = text.slice(0, 20) + (text.length > 20 ? '…' : '');
    }
  }

  /** 前端手动编辑标题后保存 */
  async function renameSession(id, newTitle) {
    const session = sessions.value.find(s => s.id === id);
    if (!session) return;
    session.title = newTitle.slice(0, 50);
    try { await api.updateSessionTitle(id, newTitle); } catch {}
    scheduleSave();
  }

  // ── 消息 ──────────────────────────────────────────────────────────────
  function pushMessage(targetId, role, html, extra = {}) {
    const item = { id: generateId(), role, html, timestamp: Date.now(), feedback: null, ...extra };
    if (!sessionMessages[targetId]) sessionMessages[targetId] = [];
    sessionMessages[targetId].push(item);
    if (targetId === sessionId.value) messages.value = sessionMessages[targetId];
    scheduleSave();
    return sessionMessages[targetId][sessionMessages[targetId].length - 1];
  }

  function setFeedback(messageId, fb) {
    for (const msgs of Object.values(sessionMessages)) {
      const m = msgs.find(m => m.id === messageId);
      if (m) {
        // P3-20: 同一反馈再次点击则撤销（toggle）
        const newFb = m.feedback === fb ? null : fb;
        m.feedback = newFb;
        scheduleSave();
        // P1-5: 若消息已持久化到服务端（ID 为数字），同步到后端
        if (typeof messageId === 'number') {
          api.saveMessageFeedback(messageId, newFb).catch(() => {});
        }
        return;
      }
    }
  }

  // ── Runtime 管理 ──────────────────────────────────────────────────────
  function ensureRuntime(id) {
    if (!sessionRuntime[id]) {
      sessionRuntime[id] = {
        sending: false, eventSource: null, bubble: null, text: '',
        requestId: 0, cancelled: false,
        reactSteps: null, reactAnswer: null, reactStartMs: 0,
      };
    }
    return sessionRuntime[id];
  }

  function stopSessionGeneration(id, showNotice = true) {
    const rt = ensureRuntime(id);
    if (!rt.sending && !rt.eventSource) return;
    rt.cancelled = true;
    rt.requestId += 1;
    rt.eventSource?.close();
    if (rt.bubble) {
      let html = '';
      if (rt.reactSteps !== null) {
        html = renderReactBubble(rt.reactSteps, rt.reactAnswer, Date.now() - (rt.reactStartMs || Date.now()));
      } else {
        html = rt.text ? formatMarkdown(rt.text) : '';
      }
      rt.bubble.html = `${html}<div class="stopped-msg">已停止生成</div>`;
    }
    rt.sending = false; rt.eventSource = null; rt.bubble = null;
    rt.text = ''; rt.reactSteps = null; rt.reactAnswer = null;
    if (showNotice) ui.showToast('info', '已停止当前会话的生成');
  }

  // ── 聊天发送 ──────────────────────────────────────────────────────────
  async function sendMessage(text, kbId = null, requestText = text) {
    const reqId = sessionId.value;
    const rt    = ensureRuntime(reqId);
    if (!text?.trim() || rt.sending) return;
    const outboundText = requestText?.trim() || text;

    pushMessage(reqId, 'user', formatMarkdown(text));
    updateSessionTitle(text, reqId);

    if (reactEnabled.value)   await doReactChat(reqId, outboundText, kbId);
    else if (streamEnabled.value) await doStreamChat(reqId, outboundText, kbId);
    else                       await doSyncChat(reqId, outboundText, kbId);
  }

  /** 重新生成某条 AI 消息（删除该消息，重发上一条 user 消息） */
  async function regenerateMessage(messageId, kbId = null) {
    const msgs   = sessionMessages[sessionId.value] || [];
    const idx    = msgs.findIndex(m => m.id === messageId);
    if (idx < 0) return;
    const userMsg = msgs.slice(0, idx).reverse().find(m => m.role === 'user');
    if (!userMsg) return;
    msgs.splice(idx, 1);   // 删除旧 AI 消息
    messages.value = [...msgs];
    sessionMessages[sessionId.value] = messages.value;
    const text = stripHtml(userMsg.html);
    if (reactEnabled.value)   await doReactChat(sessionId.value, text, kbId);
    else if (streamEnabled.value) await doStreamChat(sessionId.value, text, kbId);
    else                       await doSyncChat(sessionId.value, text, kbId);
  }

  async function doSyncChat(reqId, text, kbId) {
    const rt = ensureRuntime(reqId);
    rt.sending = true; rt.cancelled = false;
    const rid = ++rt.requestId;
    const startMs = Date.now();
    const bubble = pushMessage(reqId, 'ai', '<span class="typing-dots">●●●</span>');
    rt.bubble = bubble;
    try {
      const data = await api.chatSync(reqId, text, kbId, activeModel.value);
      if (rt.requestId !== rid || rt.cancelled) return;
      bubble.html = formatMarkdown(data.reply);
      bubble.durationMs = Date.now() - startMs;
    } catch (err) {
      if (rt.requestId !== rid || rt.cancelled) return;
      bubble.html = `<span class="error-msg">请求失败：${escapeHtml(err.message)}</span>`;
      ui.showToast('error', '发送失败，请检查网络或服务');
    } finally {
      if (rt.requestId === rid) { rt.sending = false; rt.bubble = null; }
    }
  }

async function doStreamChat(reqId, text, kbId) {
    const rt = ensureRuntime(reqId);
    rt.sending = true; rt.cancelled = false;
    const rid = ++rt.requestId;
    const startMs = Date.now();
    const initHtml = kbId
      ? '<div class="stream-hint"><span class="typing-dots">●●●</span><span class="stream-hint-label">知识库检索中…</span></div>'
      : '<span class="typing-dots">●●●</span>';
    const bubble = pushMessage(reqId, 'ai', initHtml);
    rt.bubble = bubble; rt.text = '';
    let fullText = '', firstToken = true;
    const es = api.chatStream(reqId, text, kbId, activeModel.value);
    rt.eventSource = es;

    let rafPending = false, rafId = null;
    const doRender = () => {
      rafPending = false;
      if (rt.requestId !== rid || rt.cancelled) return;
      bubble.html = formatMarkdown(fullText) + '<span class="typing-cursor"></span>';
      nextTick(() => {
        const el = document.querySelector('.chat-messages');
        if (el) el.scrollTop = el.scrollHeight;
      });
    };
    const schedRender = () => {
      if (rafPending) return;
      rafPending = true;
      rafId = requestAnimationFrame(doRender);
    };

    const onVisible = () => {
      if (document.hidden || rt.requestId !== rid || rt.cancelled || !fullText) return;
      if (!rafPending) { rafPending = true; rafId = requestAnimationFrame(doRender); }
    };
    document.addEventListener('visibilitychange', onVisible);

    es.onmessage = ev => {
      if (rt.eventSource !== es || rt.requestId !== rid || rt.cancelled) return;
      if (ev.data === '[DONE]') return;
      if (firstToken) { firstToken = false; fullText = ''; }
      fullText += ev.data; rt.text = fullText; schedRender();
    };
    es.addEventListener('replace', ev => {
      if (rt.eventSource !== es || rt.requestId !== rid || rt.cancelled) return;
      fullText = ev.data; rt.text = fullText; schedRender();
    });
    es.addEventListener('done', () => {
      if (rt.eventSource !== es || rt.requestId !== rid || rt.cancelled) return;
      es.close(); cancelAnimationFrame(rafId); rafPending = false;
      bubble.html = formatMarkdown(fullText);
      bubble.durationMs = Date.now() - startMs;
      finishStream();
    });
    es.onerror = () => {
      if (rt.eventSource !== es || rt.requestId !== rid || rt.cancelled) return;
      es.close(); cancelAnimationFrame(rafId); rafPending = false;
      if (!fullText) {
        bubble.html = '<span class="error-msg">连接失败，请重试</span>';
        ui.showToast('error', '流式连接失败');
      } else {
        bubble.html = formatMarkdown(fullText);
        bubble.durationMs = Date.now() - startMs;
      }
      finishStream();
    };

    function finishStream() {
      document.removeEventListener('visibilitychange', onVisible);
      if (rt.requestId === rid) {
        rt.sending = false; rt.eventSource = null; rt.bubble = null; rt.text = '';
        scheduleSave();
      }
    }
  }

  async function doReactChat(reqId, text, kbId) {
    const rt = ensureRuntime(reqId);
    rt.sending = true; rt.cancelled = false;
    const rid = ++rt.requestId;
    const startMs = Date.now();
    const bubble = pushMessage(reqId, 'ai',
      '<div class="react-thinking"><span class="typing-dots">●●●</span><span class="react-thinking-label">思考中…</span></div>');
    rt.bubble = bubble; rt.reactSteps = []; rt.reactAnswer = null; rt.reactStartMs = startMs;
    const es = api.chatReactStream(reqId, text, kbId, activeModel.value);
    rt.eventSource = es;

    es.addEventListener('step', ev => {
      if (rt.eventSource !== es || rt.requestId !== rid || rt.cancelled) return;
      try {
        const step = JSON.parse(ev.data);
        rt.reactSteps.push(step);
        bubble.html = renderReactBubble(rt.reactSteps, null, Date.now() - startMs);
      } catch {}
    });
    es.addEventListener('answer', ev => {
      if (rt.eventSource !== es || rt.requestId !== rid || rt.cancelled) return;
      try {
        const data = JSON.parse(ev.data);
        rt.reactAnswer = data.answer;
        bubble.html = renderReactBubble(rt.reactSteps, data.answer, data.durationMs);
        bubble.durationMs = data.durationMs;
      } catch {}
    });
    es.addEventListener('replace-answer', ev => {
      if (rt.eventSource !== es || rt.requestId !== rid || rt.cancelled) return;
      try {
        const data = JSON.parse(ev.data);
        rt.reactAnswer = data.answer;
        bubble.html = renderReactBubble(rt.reactSteps, data.answer, Date.now() - startMs);
      } catch {}
    });
    es.addEventListener('done', () => {
      if (rt.eventSource !== es || rt.requestId !== rid || rt.cancelled) return;
      es.close(); finishReact();
    });
    es.addEventListener('error', ev => {
      if (rt.eventSource !== es || rt.requestId !== rid || rt.cancelled) return;
      es.close();
      const msg = ev.data || '推理失败，请重试';
      if (!rt.reactAnswer) {
        bubble.html = `<span class="error-msg">推理失败：${escapeHtml(msg)}</span>`;
        ui.showToast('error', '深度推理失败，请重试');
      }
      finishReact();
    });
    es.onerror = () => {
      if (rt.eventSource !== es || rt.requestId !== rid || rt.cancelled) return;
      es.close();
      if (!rt.reactAnswer && !rt.reactSteps?.length) {
        bubble.html = '<span class="error-msg">连接失败，请重试</span>';
        ui.showToast('error', '深度推理连接失败');
      }
      finishReact();
    };

    function finishReact() {
      if (rt.requestId === rid) {
        rt.sending = false; rt.eventSource = null; rt.bubble = null;
        rt.reactSteps = null; rt.reactAnswer = null;
        scheduleSave();
      }
    }
  }

  // ── 导出对话 ──────────────────────────────────────────────────────────
  function exportCurrentSession() {
    const s   = sessions.value.find(s => s.id === sessionId.value);
    const title = s?.title || '对话记录';
    const msgs  = (sessionMessages[sessionId.value] || []);
    const md = `# ${title}\n\n` + msgs.map(m => {
      const role = m.role === 'user' ? '**用户**' : '**AI**';
      const text = stripHtml(m.html).trim();
      return `${role}：\n\n${text}`;
    }).join('\n\n---\n\n');
    const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' });
    const url  = URL.createObjectURL(blob);
    const a    = document.createElement('a');
    a.href     = url;
    a.download = `${title.replace(/[/\\?%*:|"<>]/g, '_')}.md`;
    a.click();
    URL.revokeObjectURL(url);
  }

  return {
    sessions, sessionId, messages, sessionMessages, sessionRuntime,
    reactEnabled, streamEnabled, enterToSend, model, activeModel, currentKbId, messageInput,
    currentSessionSending, currentSessionTitle,
    QUICK_MODEL, EXPERT_MODEL, setQuickMode, setExpertMode, toggleExpertMode,
    init, newSession, switchSession, removeSession, renameSession, updateSessionTitle,
    sendMessage, regenerateMessage, setFeedback,
    stopGeneration: id => stopSessionGeneration(id ?? sessionId.value, true),
    stopSessionGeneration, ensureRuntime,
    exportCurrentSession, scheduleSave,
  };
});
