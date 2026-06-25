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
import { useOrgStore } from './org.js';
import {
  EXPERT_MODEL,
  MAX_CHAT_MESSAGE_CHARS,
  MAX_MSGS,
  MAX_SESSIONS,
  QUICK_MODEL,
  SAVE_DEBOUNCE,
  escapeHtml,
  generateId,
  renderReactBubble,
  renderStreamingText,
  storageKey,
  stripHtml,
} from './sessionUtils.js';

export const useSessionStore = defineStore('sessions', () => {
  const ui = useUiStore();
  const org = useOrgStore();

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
  const currentKbOrgId = ref(null);
  const messageInput   = ref('');
  const editingMessageId = ref(null);
  const editingOriginalText = ref('');

  let _saveTimer = null;
  const REACT_WATCHDOG_MS = 150000;
  const REACT_WAITING_UPDATE_MS = 1000;

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
    if (Object.values(sessionRuntime).some(rt => rt?.suppressSave)) return;
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
        html: m.html, text: m.text || '', timestamp: m.timestamp, durationMs: m.durationMs,
        feedback: m.feedback,
      })),
    }));
    try { localStorage.setItem(storageKey(userId), JSON.stringify(payload)); } catch {}
  }

  function ragContextKey() {
    const userId = api.getUser()?.userId;
    return userId ? `ai_agent_active_rag_${userId}` : null;
  }

  function readStoredRagContext() {
    const key = ragContextKey();
    if (!key) return null;
    try {
      const data = JSON.parse(localStorage.getItem(key) || 'null');
      const kbId = Number(data?.kbId);
      const orgId = data?.orgId ? String(data.orgId) : '';
      if (!orgId || !Number.isFinite(kbId)) return null;
      return { orgId, kbId };
    } catch {
      return null;
    }
  }

  function persistRagContext(kbId, orgId) {
    const key = ragContextKey();
    if (!key || !kbId || !orgId) return;
    try {
      localStorage.setItem(key, JSON.stringify({ orgId, kbId: Number(kbId) }));
    } catch {}
  }

  function clearStoredRagContext() {
    const key = ragContextKey();
    if (!key) return;
    try {
      localStorage.removeItem(key);
    } catch {}
  }

  function setCurrentKb(kbId, orgId = org.currentOrgId) {
    if (!kbId || !orgId) {
      clearCurrentKb();
      return;
    }
    currentKbId.value = Number(kbId);
    currentKbOrgId.value = orgId;
    persistRagContext(currentKbId.value, orgId);
  }

  function clearCurrentKb() {
    currentKbId.value = null;
    currentKbOrgId.value = null;
    clearStoredRagContext();
  }

  function restoreRagContext() {
    const saved = readStoredRagContext();
    if (!saved) {
      clearCurrentKb();
      return null;
    }

    const hasOrgAccess = org.organizations.some(item => item.orgId === saved.orgId);
    if (!hasOrgAccess) {
      clearCurrentKb();
      return null;
    }

    if (org.currentOrgId !== saved.orgId) {
      org.selectOrg(saved.orgId);
    }
    setCurrentKb(saved.kbId, saved.orgId);
    return saved;
  }

  function activeKbIdForCurrentOrg() {
    return currentKbOrgId.value === org.currentOrgId ? currentKbId.value : null;
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
          text: m.content || '',
          timestamp: m.createdAt ? new Date(m.createdAt).getTime() : Date.now(),
          durationMs: 0,
          feedback: m.feedback || null,
        }));
      }
    } catch {}
  }

  // ── 初始化 ────────────────────────────────────────────────────────────
  async function init() {
    restoreRagContext();
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
    cancelEditingMessage();
    const id = generateId();
    sessions.value.unshift({ id, title: '新对话', createdAt: Date.now() });
    sessionMessages[id] = [];
    sessionId.value     = id;
    messages.value      = sessionMessages[id];
  }

  async function switchSession(id) {
    cancelEditingMessage();
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

  function removeSessionsFromState(ids) {
    const idSet = new Set(ids.filter(Boolean));
    if (!idSet.size) return;

    idSet.forEach(id => {
      stopSessionGeneration(id, false);
      delete sessionMessages[id];
      delete sessionRuntime[id];
    });

    sessions.value = sessions.value.filter(s => !idSet.has(s.id));

    if (idSet.has(sessionId.value)) {
      if (sessions.value.length) switchSession(sessions.value[0].id);
      else newSession();
    }
    scheduleSave();
  }

  async function removeSessions(ids) {
    const cleanedIds = [...new Set((ids || []).filter(Boolean))];
    if (!cleanedIds.length) return;
    await api.deleteChatSessions(cleanedIds);
    removeSessionsFromState(cleanedIds);
    ui.showToast('info', `已删除 ${cleanedIds.length} 个会话`);
  }

  async function removeAllSessions() {
    const ids = sessions.value.map(s => s.id);
    if (!ids.length) return;
    await api.deleteAllChatSessions();
    ids.forEach(id => {
      stopSessionGeneration(id, false);
      delete sessionMessages[id];
      delete sessionRuntime[id];
    });
    sessions.value = [];
    newSession();
    scheduleSave();
    ui.showToast('info', '已清空全部会话');
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

  function serializeMessagesForServer(msgs) {
    return (msgs || []).slice(-MAX_MSGS).map(m => ({
      role: m.role === 'ai' ? 'ai' : 'user',
      content: (m.text || '').trim() || stripHtml(m.html || '').trim(),
      timestamp: m.timestamp || Date.now(),
    })).filter(m => m.content);
  }

  function startEditingMessage(messageId) {
    const msg = (sessionMessages[sessionId.value] || []).find(m => m.id === messageId && m.role === 'user');
    if (!msg || currentSessionSending.value) return;
    editingMessageId.value = messageId;
    editingOriginalText.value = stripHtml(msg.html || '');
    messageInput.value = editingOriginalText.value;
  }

  function cancelEditingMessage() {
    editingMessageId.value = null;
    editingOriginalText.value = '';
  }

  async function submitEditedMessage(text, kbId = null, requestText = text) {
    const targetId = editingMessageId.value;
    const id = sessionId.value;
    const msgs = sessionMessages[id] || [];
    const idx = msgs.findIndex(m => m.id === targetId && m.role === 'user');
    if (idx < 0) {
      cancelEditingMessage();
      return sendMessage(text, kbId, requestText);
    }

    const previous = msgs.slice();
    const pruned = msgs.slice(0, idx);
    sessionMessages[id] = pruned;
    messages.value = sessionMessages[id];
    try {
      await api.rewriteChatMessages(id, serializeMessagesForServer(pruned));
      if (idx === 0) {
        const session = sessions.value.find(s => s.id === id);
        const nextTitle = text.slice(0, 20) + (text.length > 20 ? '…' : '');
        if (session) session.title = nextTitle;
        api.updateSessionTitle(id, nextTitle).catch(() => {});
      }
      cancelEditingMessage();
      messageInput.value = '';
      await sendMessage(text, kbId, requestText);
    } catch (err) {
      sessionMessages[id] = previous;
      messages.value = sessionMessages[id];
      ui.showToast('error', err.message || '修改失败，请重试');
      throw err;
    }
  }

  // ── 消息 ──────────────────────────────────────────────────────────────
  function pushMessage(targetId, role, html, extra = {}) {
    const item = { id: generateId(), role, html, text: '', timestamp: Date.now(), feedback: null, ...extra };
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
        requestId: 0, cancelled: false, suppressSave: false,
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
    rt.suppressSave = false;
    rt.sending = false; rt.eventSource = null; rt.bubble = null;
    rt.text = ''; rt.reactSteps = null; rt.reactAnswer = null;
    scheduleSave();
    if (showNotice) ui.showToast('info', '已停止当前会话的生成');
  }

  function renderReactThinking(status, startMs) {
    const elapsed = Math.max(0, Math.floor((Date.now() - startMs) / 1000));
    const label = elapsed >= 3 ? `${status}，已等待 ${elapsed} 秒` : status;
    return `<div class="react-thinking"><span class="typing-dots">●●●</span><span class="react-thinking-label">${escapeHtml(label)}</span></div>`;
  }

  function parseReactPayload(raw) {
    if (raw && typeof raw === 'object') return raw;
    if (!raw) return {};
    try {
      const data = JSON.parse(raw);
      return data && typeof data === 'object' ? data : { message: String(data) };
    } catch {
      return { message: String(raw) };
    }
  }

  function mapReactErrorMessage(raw, fallback = '深度推理失败，请稍后重试') {
    const data = parseReactPayload(raw);
    const code = String(data.code || '').toLowerCase();
    const message = String(data.message || raw || fallback);
    const source = `${code} ${message}`.toLowerCase();

    if (source.includes('account_overdue')) {
      return '模型/Embedding 服务账号欠费或不可用，请检查 API Key 或账户余额';
    }
    if (['busy', 'rate_limited', 'prompt_blocked', 'kb_forbidden', 'timeout'].includes(code)) {
      return message;
    }
    if (code === 'internal_error') {
      return fallback;
    }
    return source.includes('{') ? fallback : message;
  }

  function mapStreamErrorMessage(raw, fallback = '流式连接失败，请重试') {
    const data = parseReactPayload(raw);
    const code = String(data.code || '').toLowerCase();
    const message = String(data.message || raw || fallback);
    const source = `${code} ${message}`.toLowerCase();

    if (source.includes('account_overdue')) {
      return '模型服务账号欠费或不可用，请检查 API Key 或账户余额';
    }
    if (source.includes('context') || source.includes('token') || source.includes('length') || source.includes('maximum')) {
      return '内容或会话上下文过长，请缩短文本、开启新会话，或导入作品后分章节处理';
    }
    if (['busy', 'rate_limited', 'prompt_blocked', 'kb_forbidden', 'timeout'].includes(code)) {
      return message;
    }
    if (code === 'internal_error') {
      return fallback;
    }
    return source.includes('{') ? fallback : message;
  }

  // ── 聊天发送 ──────────────────────────────────────────────────────────
  async function sendMessage(text, kbId = null, requestText = text) {
    const reqId = sessionId.value;
    const rt    = ensureRuntime(reqId);
    if (!text?.trim() || rt.sending) return;
    const outboundText = requestText?.trim() || text;
    if (outboundText.length > MAX_CHAT_MESSAGE_CHARS) {
      ui.showToast('warning', `内容过长（${outboundText.length} 字），请控制在 ${MAX_CHAT_MESSAGE_CHARS} 字以内，或导入作品后分章节处理`);
      return;
    }
    const effectiveKbId = kbId ?? activeKbIdForCurrentOrg() ?? null;

    pushMessage(reqId, 'user', formatMarkdown(text), { text });
    updateSessionTitle(text, reqId);

    if (reactEnabled.value)   await doReactChat(reqId, outboundText, effectiveKbId);
    else if (streamEnabled.value) await doStreamChat(reqId, outboundText, effectiveKbId);
    else                       await doSyncChat(reqId, outboundText, effectiveKbId);
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
    if (text.length > MAX_CHAT_MESSAGE_CHARS) {
      ui.showToast('warning', `原消息过长（${text.length} 字），请控制在 ${MAX_CHAT_MESSAGE_CHARS} 字以内后再重新生成`);
      return;
    }
    const effectiveKbId = kbId ?? activeKbIdForCurrentOrg() ?? null;
    if (reactEnabled.value)   await doReactChat(sessionId.value, text, effectiveKbId);
    else if (streamEnabled.value) await doStreamChat(sessionId.value, text, effectiveKbId);
    else                       await doSyncChat(sessionId.value, text, effectiveKbId);
  }

  async function createShareLink() {
    const id = sessionId.value;
    const s = sessions.value.find(s => s.id === id);
    const snapshot = serializeMessagesForServer(sessionMessages[id] || []);
    if (!snapshot.length) {
      ui.showToast('warning', '空会话无法分享');
      return null;
    }
    const data = await api.createChatShare(id, {
      title: s?.title || '对话分享',
      messages: snapshot,
    });
    return {
      ...data,
      url: `${window.location.origin}${window.location.pathname}#/share/${data.shareId}`,
    };
  }

  async function revokeShare(shareId) {
    if (!shareId) return;
    await api.revokeChatShare(shareId);
    ui.showToast('info', '已撤销分享链接');
  }

  async function doSyncChat(reqId, text, kbId) {
    const rt = ensureRuntime(reqId);
    rt.sending = true; rt.cancelled = false;
    const rid = ++rt.requestId;
    const startMs = Date.now();
    const bubble = pushMessage(reqId, 'ai', '<span class="typing-dots">●●●</span>');
    rt.bubble = bubble;
    try {
      const data = await api.chatSync(reqId, text, kbId, activeModel.value, org.currentOrgId);
      if (rt.requestId !== rid || rt.cancelled) return;
      bubble.html = formatMarkdown(data.reply);
      bubble.text = data.reply || '';
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
    rt.bubble = bubble; rt.text = ''; rt.suppressSave = true;
    let fullText = '', firstToken = true, serverErrorHandled = false;
    let es;
    try {
      es = await api.chatStream(reqId, text, kbId, activeModel.value, org.currentOrgId);
    } catch (err) {
      if (rt.requestId === rid && !rt.cancelled) {
        bubble.html = `<span class="error-msg">连接失败：${escapeHtml(err.message || '流式连接失败')}</span>`;
        bubble.durationMs = Date.now() - startMs;
        rt.suppressSave = false;
        rt.sending = false; rt.eventSource = null; rt.bubble = null; rt.text = '';
        scheduleSave();
      }
      ui.showToast('error', err.message || '流式连接失败');
      return;
    }
    if (rt.requestId !== rid || rt.cancelled) {
      es.close();
      return;
    }
    rt.eventSource = es;

    let rafPending = false, rafId = null;
    const doRender = () => {
      rafPending = false;
      if (rt.requestId !== rid || rt.cancelled) return;
      bubble.html = renderStreamingText(fullText) + '<span class="typing-cursor"></span>';
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
    es.addEventListener('error', ev => {
      if (rt.eventSource !== es || rt.requestId !== rid || rt.cancelled || serverErrorHandled) return;
      if (!ev?.data) return;
      serverErrorHandled = true;
      es.close(); cancelAnimationFrame(rafId); rafPending = false;
      const message = mapStreamErrorMessage(ev.data);
      if (!fullText) {
        bubble.html = `<span class="error-msg">${escapeHtml(message)}</span>`;
        bubble.durationMs = Date.now() - startMs;
        ui.showToast('error', message);
      } else {
        bubble.html = `${formatMarkdown(fullText)}<div class="stopped-msg">${escapeHtml(message)}</div>`;
        bubble.durationMs = Date.now() - startMs;
      }
      finishStream();
    });
    es.addEventListener('done', () => {
      if (rt.eventSource !== es || rt.requestId !== rid || rt.cancelled) return;
      es.close(); cancelAnimationFrame(rafId); rafPending = false;
      bubble.html = formatMarkdown(fullText);
      bubble.text = fullText;
      bubble.durationMs = Date.now() - startMs;
      finishStream();
    });
    es.onerror = () => {
      if (rt.eventSource !== es || rt.requestId !== rid || rt.cancelled || serverErrorHandled) return;
      es.close(); cancelAnimationFrame(rafId); rafPending = false;
      if (!fullText) {
        bubble.html = '<span class="error-msg">连接失败，请重试</span>';
        ui.showToast('error', '流式连接失败');
      } else {
        bubble.html = formatMarkdown(fullText);
        bubble.text = fullText;
        bubble.durationMs = Date.now() - startMs;
      }
      finishStream();
    };

    function finishStream() {
      document.removeEventListener('visibilitychange', onVisible);
      if (rt.requestId === rid) {
        rt.suppressSave = false;
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
    let currentStatus = '思考中…';
    const bubble = pushMessage(reqId, 'ai', renderReactThinking(currentStatus, startMs));
    rt.bubble = bubble; rt.reactSteps = []; rt.reactAnswer = null; rt.reactStartMs = startMs; rt.suppressSave = true;
    let es;
    try {
      es = await api.chatReactStream(reqId, text, kbId, activeModel.value, org.currentOrgId);
    } catch (err) {
      if (rt.requestId === rid && !rt.cancelled) {
        bubble.html = `<span class="error-msg">${escapeHtml(err.message || '深度推理连接失败，请重试')}</span>`;
        bubble.durationMs = Date.now() - startMs;
        rt.suppressSave = false;
        rt.sending = false;
        rt.bubble = null;
        rt.reactSteps = null;
        rt.reactAnswer = null;
        scheduleSave();
      }
      ui.showToast('error', err.message || '深度推理连接失败');
      return;
    }
    if (rt.requestId !== rid || rt.cancelled) {
      es.close();
      return;
    }
    rt.eventSource = es;
    let lastEventAt = Date.now();
    let receivedAnswer = false;
    let answerStreaming = false;
    let answerText = '';
    let finished = false;
    let watchdogId = null;
    let rafPending = false;
    let rafId = null;
    let pendingDurationMs = null;
    let pendingForceAnswer = false;
    // 追踪当前正在流式输出的 reasoning 轮次；
    // 后端 reasoning-token 已改为发裸文本（去掉 JSON 封装），前端从 reasoning-start 事件中获取轮次。
    let currentReasoningIteration = null;

    function isActive() {
      return rt.eventSource === es && rt.requestId === rid && !rt.cancelled;
    }

    function touchEvent(status = null) {
      lastEventAt = Date.now();
      if (status) currentStatus = status;
    }

    function setWaitingStatus(status = currentStatus) {
      if (receivedAnswer || answerStreaming || answerText || rt.reactSteps?.length) return;
      bubble.html = renderReactThinking(status, startMs);
    }

    function scrollToBottom() {
      nextTick(() => {
        const el = document.querySelector('.chat-messages');
        if (el) el.scrollTop = el.scrollHeight;
      });
    }

    function cancelPendingRender() {
      if (rafId) cancelAnimationFrame(rafId);
      rafId = null;
      rafPending = false;
      pendingDurationMs = null;
      pendingForceAnswer = false;
    }

    function renderProgress(durationMs = Date.now() - startMs, forceAnswer = false) {
      if (!isActive()) return;
      const visibleAnswer = (answerStreaming || answerText || forceAnswer) ? answerText : null;
      bubble.html = renderReactBubble(
        rt.reactSteps,
        visibleAnswer,
        durationMs,
        answerStreaming && !receivedAnswer
      );
      scrollToBottom();
    }

    function scheduleRender(durationMs = Date.now() - startMs, forceAnswer = false) {
      pendingDurationMs = durationMs;
      pendingForceAnswer = pendingForceAnswer || forceAnswer;
      if (rafPending) return;
      rafPending = true;
      rafId = requestAnimationFrame(() => {
        rafPending = false;
        rafId = null;
        const nextDurationMs = pendingDurationMs ?? Date.now() - startMs;
        const nextForceAnswer = pendingForceAnswer;
        pendingDurationMs = null;
        pendingForceAnswer = false;
        renderProgress(nextDurationMs, nextForceAnswer);
      });
    }

    function ensureReactStep(iteration) {
      const stepNo = Number(iteration) || (rt.reactSteps.length + 1);
      let step = rt.reactSteps.find(item => Number(item.iteration) === stepNo);
      if (!step) {
        step = { iteration: stepNo, thought: '', toolName: '', toolArgs: '', observation: '' };
        rt.reactSteps.push(step);
        rt.reactSteps.sort((a, b) => Number(a.iteration) - Number(b.iteration));
      }
      return step;
    }

    function mergeReactStep(data) {
      const step = ensureReactStep(data.iteration);
      if (Object.prototype.hasOwnProperty.call(data, 'thought')) step.thought = String(data.thought || '');
      if (Object.prototype.hasOwnProperty.call(data, 'toolName')) step.toolName = String(data.toolName || '');
      if (Object.prototype.hasOwnProperty.call(data, 'toolArgs')) step.toolArgs = String(data.toolArgs || '');
      if (Object.prototype.hasOwnProperty.call(data, 'observation')) step.observation = String(data.observation || '');
      return step;
    }

    function renderReactError(message) {
      cancelPendingRender();
      const progress = (rt.reactSteps?.length || answerText)
        ? renderReactBubble(rt.reactSteps, answerText || '', Date.now() - startMs, false)
        : '';
      bubble.html = `${progress}<div class="react-answer"><span class="error-msg">${escapeHtml(message)}</span></div>`;
      bubble.durationMs = Date.now() - startMs;
    }

    function failReact(raw, fallback, toastMessage = '深度推理失败，请重试') {
      if (finished || !isActive()) return;
      const msg = mapReactErrorMessage(raw, fallback);
      es.close();
      renderReactError(msg);
      ui.showToast('error', toastMessage);
      finishReact();
    }

    watchdogId = window.setInterval(() => {
      if (finished || rt.eventSource !== es || rt.requestId !== rid || rt.cancelled) {
        window.clearInterval(watchdogId);
        return;
      }
      if (Date.now() - lastEventAt >= REACT_WATCHDOG_MS) {
        failReact(
          { message: '深度推理超时，请稍后重试', code: 'timeout' },
          '深度推理超时，请稍后重试',
          '深度推理超时'
        );
        return;
      }
      setWaitingStatus();
    }, REACT_WAITING_UPDATE_MS);

    es.addEventListener('status', ev => {
      if (!isActive()) return;
      const data = parseReactPayload(ev.data);
      touchEvent(data.message || '思考中…');
      setWaitingStatus(currentStatus);
    });
    es.addEventListener('reasoning-start', ev => {
      if (!isActive()) return;
      const data = parseReactPayload(ev.data);
      touchEvent();
      currentReasoningIteration = data.iteration;  // 记录当前推理轮次，供 reasoning-token 使用
      ensureReactStep(data.iteration);
      scheduleRender();
    });
    es.addEventListener('reasoning-token', ev => {
      if (!isActive()) return;
      touchEvent();
      // 后端已改为发裸文本（去掉 JSON 封装），ev.data 就是本批次的 token 文本
      const step = ensureReactStep(currentReasoningIteration);
      step.thought = `${step.thought || ''}${ev.data || ''}`;

      // 快速路径：直接更新 DOM 中的 thought 文本节点，跳过 renderReactBubble 全量重建。
      // renderReactBubble 会替换整个 bubble 的 innerHTML，代价较高；
      // 而 thought 文本是 reasoning-token 阶段唯一高频变化的内容，可以精准更新。
      const thoughtEl = document.querySelector(`[data-thought-iter="${currentReasoningIteration}"]`);
      if (thoughtEl) {
        const t = step.thought;
        // 与 trimText(t, 220) 逻辑保持一致；textContent 赋值不需要 HTML 转义
        thoughtEl.textContent = t.length > 220 ? t.slice(0, 220) + '…' : t;
        scrollToBottom();
      } else {
        // 首个 token 或 DOM 尚未就绪（reasoning-start 后首次渲染前）：
        // 走全量重建，同时创建带 data-thought-iter 的元素，后续 token 走快速路径
        scheduleRender();
      }
    });
    es.addEventListener('reasoning-done', ev => {
      if (!isActive()) return;
      const data = parseReactPayload(ev.data);
      touchEvent();
      const step = ensureReactStep(data.iteration);
      if (Object.prototype.hasOwnProperty.call(data, 'text')) {
        step.thought = String(data.text || '');
      }
      scheduleRender();
    });
    es.addEventListener('tool-call', ev => {
      if (!isActive()) return;
      const data = parseReactPayload(ev.data);
      touchEvent();
      const step = ensureReactStep(data.iteration);
      step.toolName = String(data.toolName || '');
      step.toolArgs = String(data.toolArgs || '');
      step.pending = true;   // 标记工具正在执行中，触发 loading 动画
      scheduleRender();
    });
    es.addEventListener('tool-result', ev => {
      if (!isActive()) return;
      const data = parseReactPayload(ev.data);
      touchEvent();
      const step = ensureReactStep(data.iteration);
      step.toolName = String(data.toolName || step.toolName || '');
      step.observation = String(data.observation || '');
      step.pending = false;  // 工具执行完毕，清除 loading 动画
      scheduleRender();
    });
    es.addEventListener('step', ev => {
      if (!isActive()) return;
      touchEvent();
      try {
        const step = JSON.parse(ev.data);
        mergeReactStep(step);
        scheduleRender();
      } catch {}
    });
    es.addEventListener('answer-start', ev => {
      if (!isActive()) return;
      touchEvent();
      answerStreaming = true;
      scheduleRender(Date.now() - startMs, true);
    });
    es.addEventListener('answer-token', ev => {
      if (!isActive()) return;
      touchEvent();
      answerStreaming = true;
      answerText += ev.data || '';
      rt.reactAnswer = answerText;
      scheduleRender(Date.now() - startMs, true);
    });
    es.addEventListener('answer', ev => {
      if (!isActive()) return;
      touchEvent();
      try {
        const data = JSON.parse(ev.data);
        const answer = data.answer || '';
        receivedAnswer = !!String(answer).trim();
        answerStreaming = false;
        answerText = answer;
        rt.reactAnswer = answerText;
        bubble.text = answerText;
        cancelPendingRender();
        renderProgress(data.durationMs, true);
        bubble.durationMs = data.durationMs;
      } catch {}
    });
    es.addEventListener('replace-answer', ev => {
      if (!isActive()) return;
      touchEvent();
      try {
        const data = JSON.parse(ev.data);
        const answer = data.answer || '';
        receivedAnswer = !!String(answer).trim();
        answerStreaming = false;
        answerText = answer;
        rt.reactAnswer = answerText;
        bubble.text = answerText;
        cancelPendingRender();
        renderProgress(Date.now() - startMs, true);
      } catch {}
    });
    es.addEventListener('react-error', ev => {
      if (!isActive()) return;
      touchEvent();
      failReact(ev.data, '深度推理失败，请稍后重试');
    });
    es.addEventListener('done', () => {
      if (!isActive()) return;
      touchEvent();
      es.close();
      cancelPendingRender();
      if (!receivedAnswer) {
        renderReactError('推理已结束但没有返回答案，请重试');
        ui.showToast('error', '深度推理未返回答案');
      }
      finishReact();
    });
    es.addEventListener('error', ev => {
      if (!isActive()) return;
      if (typeof ev.data === 'undefined') return;
      touchEvent();
      failReact(ev.data, '深度推理失败，请稍后重试');
    });
    es.onerror = () => {
      if (!isActive()) return;
      es.close();
      if (!receivedAnswer) {
        renderReactError('深度推理连接中断，后端可能已异常退出，请重试');
        ui.showToast('error', '深度推理连接失败');
      }
      finishReact();
    };

    function finishReact() {
      if (finished) return;
      finished = true;
      if (watchdogId) window.clearInterval(watchdogId);
      cancelPendingRender();
      if (rt.requestId === rid) {
        rt.suppressSave = false;
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
    reactEnabled, streamEnabled, enterToSend, model, activeModel, currentKbId, currentKbOrgId, messageInput,
    editingMessageId, editingOriginalText,
    currentSessionSending, currentSessionTitle,
    QUICK_MODEL, EXPERT_MODEL, setQuickMode, setExpertMode, toggleExpertMode,
    init, newSession, switchSession, removeSession, removeSessions, removeAllSessions, renameSession, updateSessionTitle,
    sendMessage, regenerateMessage, setFeedback,
    setCurrentKb, clearCurrentKb, restoreRagContext,
    startEditingMessage, cancelEditingMessage, submitEditedMessage, createShareLink, revokeShare,
    stopGeneration: id => stopSessionGeneration(id ?? sessionId.value, true),
    stopSessionGeneration, ensureRuntime,
    exportCurrentSession, scheduleSave,
  };
});
