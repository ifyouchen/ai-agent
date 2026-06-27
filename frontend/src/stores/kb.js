/**
 * KB Store — 知识库状态管理
 */
import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import * as api from '../services/api.js';
import { useUiStore } from './ui.js';
import { createTtlCache } from '../js/cache.js';

const kbListCache = createTtlCache(60000);

export const useKbStore = defineStore('kb', () => {
  const ui = useUiStore();

  const knowledgeBases  = ref([]);
  const currentKbId     = ref(null);
  const docs            = ref([]);
  const uploadQueue     = ref([]);
  const kbLoading       = ref(false);
  const docsLoading     = ref(false);
  const kbMembers       = ref([]);
  const kbMembersVisible = ref(false);

  // 文档轮询 timers: {docId: timerId}
  const _pollTimers = {};
  let _kbLoadSeq = 0;
  let _docsLoadSeq = 0;

  const currentKb = computed(() =>
    knowledgeBases.value.find(kb => kb.id === currentKbId.value) || null
  );

  const currentKbName = computed(() => currentKb.value?.name || '');

  // ── 知识库列表 ──────────────────────────────────────────────────────
  function kbPreferenceKey(orgId) {
    const userId = api.getUser()?.userId;
    return userId && orgId ? `ai_agent_recent_kb_${userId}_${orgId}` : null;
  }

  function readStoredKbId(orgId) {
    const key = kbPreferenceKey(orgId);
    if (!key) return null;
    try {
      const raw = localStorage.getItem(key);
      const value = raw ? Number(raw) : null;
      return Number.isFinite(value) ? value : null;
    } catch {
      return null;
    }
  }

  function persistKbId(kbId, orgId) {
    const key = kbPreferenceKey(orgId);
    if (!key || !kbId) return;
    try {
      localStorage.setItem(key, String(kbId));
    } catch {}
  }

  function resolvePreferredKbId(orgId, list) {
    if (!list.length) return null;

    const currentValid = currentKbId.value && list.find(kb => kb.id === currentKbId.value);
    if (currentValid) return currentKbId.value;

    const storedKbId = readStoredKbId(orgId);
    const storedValid = storedKbId && list.find(kb => kb.id === storedKbId);
    if (storedValid) return storedKbId;

    return list[0].id;
  }

  function stopAllDocPolling() {
    Object.keys(_pollTimers).forEach(docId => stopDocPolling(docId));
  }

  function resetSelection() {
    _docsLoadSeq += 1;
    stopAllDocPolling();
    currentKbId.value = null;
    docs.value = [];
    kbMembers.value = [];
    kbMembersVisible.value = false;
  }

  async function loadKbs(orgId, options = {}) {
    const { reset = false } = options;
    const seq = ++_kbLoadSeq;
    if (reset) {
      knowledgeBases.value = [];
      resetSelection();
    }
    kbLoading.value = true;
    try {
      const cacheKey = `list:${orgId}`;
      const cached = kbListCache.get(cacheKey);
      const list = cached || await api.listKnowledgeBases(orgId);
      if (!cached) kbListCache.set(cacheKey, list);
      if (seq !== _kbLoadSeq) return;
      knowledgeBases.value = list;
      const preferredKbId = resolvePreferredKbId(orgId, knowledgeBases.value);
      if (!preferredKbId) {
        resetSelection();
        return;
      }
      if (currentKbId.value !== preferredKbId) {
        await selectKb(preferredKbId, orgId);
      } else if (!docs.value.length) {
        await loadDocs(orgId);
      }
    } catch (err) {
      if (seq === _kbLoadSeq) {
        knowledgeBases.value = [];
        resetSelection();
        ui.showToast('error', err.message || '加载知识库失败');
      }
    } finally {
      if (seq === _kbLoadSeq) kbLoading.value = false;
    }
  }

  async function selectKb(kbId, orgId) {
    currentKbId.value    = kbId;
    kbMembersVisible.value = false;
    persistKbId(kbId, orgId);
    await loadDocs(orgId);
  }

  async function createKb(name, description, orgId) {
    kbListCache.invalidate();
    const created = await api.createKnowledgeBase(name, description, orgId);
    ui.showToast('success', `知识库「${name}」已创建`);
    await loadKbs(orgId);
    const createdId = created?.id ?? created?.kbId;
    if (createdId) await selectKb(Number(createdId), orgId);
    return created;
  }

  async function updateKb(kbId, name, description, orgId) {
    kbListCache.invalidate();
    await api.updateKnowledgeBase(kbId, name, description, orgId);
    ui.showToast('success', '知识库已更新');
    await loadKbs(orgId);
  }

  async function deleteKb(kbId, orgId) {
    kbListCache.invalidate();
    await api.deleteKnowledgeBase(kbId, orgId);
    if (currentKbId.value === kbId) { currentKbId.value = null; docs.value = []; }
    await loadKbs(orgId);
  }

  // ── 文档列表 ────────────────────────────────────────────────────────
  async function loadDocs(orgId) {
    if (!currentKbId.value) { docs.value = []; return; }
    const seq = ++_docsLoadSeq;
    docsLoading.value = true;
    try {
      const data = await api.listDocuments(currentKbId.value, orgId);
      if (seq !== _docsLoadSeq) return;
      docs.value = data.map(mapDoc);
      // 对仍在处理中的文档启动轮询
      docs.value.filter(d => ['PROCESSING','PENDING','PARSING','CHUNKING','EMBEDDING'].includes(d.status))
        .forEach(d => startDocPolling(d.id, orgId));
    } catch (err) {
      if (seq === _docsLoadSeq) {
        if (currentKbId.value) ui.showToast('error', err.message || '加载文档失败');
        docs.value = [];
      }
    } finally {
      if (seq === _docsLoadSeq) docsLoading.value = false;
    }
  }

  function mapDoc(doc) {
    return {
      id:         doc.id,
      filename:   doc.name ?? doc.filename,
      chunks:     doc.chunkCount ?? doc.chunks ?? 0,
      size:       doc.fileSize ?? 0,
      status:     doc.parseStatus ?? 'UNKNOWN',
      parseError: doc.parseError ?? '',
      uploadedAt: doc.createdAt ? new Date(doc.createdAt).toLocaleString() : '',
    };
  }

  /** 启动单文档解析状态轮询（3s interval），完成后自停 */
  function startDocPolling(docId, orgId) {
    if (_pollTimers[docId]) return;  // 已在轮询中
    const kbId = currentKbId.value;
    _pollTimers[docId] = setInterval(async () => {
      try {
        const status = await api.getDocumentStatus(kbId, docId, orgId);
        const doc = docs.value.find(d => d.id === docId);
        if (doc) {
          doc.status     = status.parseStatus;
          doc.chunks     = status.chunkCount;
          doc.parseError = status.parseError || '';
        }
        if (['DONE','FAILED'].includes(status.parseStatus)) {
          stopDocPolling(docId);
        }
      } catch {
        stopDocPolling(docId); // 出错时停止轮询
      }
    }, 3000);
  }

  function stopDocPolling(docId) {
    if (_pollTimers[docId]) {
      clearInterval(_pollTimers[docId]);
      delete _pollTimers[docId];
    }
  }

  // ── 文档上传 ────────────────────────────────────────────────────────
  async function uploadFile(file, orgId) {
    if (!currentKbId.value) { ui.showToast('warning', '请先选择或创建知识库'); return; }
    const taskId = Math.random().toString(36).slice(2);
    const task = {
      id: taskId, filename: file.name, status: 'uploading',
      pct: 0, barWidth: 0,
      loaded: 0, total: file.size,
      loadedText: '0 B', totalText: formatFileSize(file.size),
      speedText: '', etaText: '', error: '',
    };
    uploadQueue.value.push(task);
    const startMs = Date.now();

    try {
      await api.uploadDocument(currentKbId.value, file, prog => {
        task.pct        = prog.pct;
        task.barWidth   = prog.pct;
        task.loaded     = prog.loaded;
        task.loadedText = formatFileSize(prog.loaded);
        const elapsed   = (Date.now() - startMs) / 1000;
        if (elapsed > 0.5 && prog.loaded > 0) {
          const speed = prog.loaded / elapsed;
          task.speedText = `${formatFileSize(speed)}/s`;
          if (prog.total && speed > 0) {
            const eta = (prog.total - prog.loaded) / speed;
            task.etaText = eta < 60 ? `${Math.ceil(eta)}秒` : `${Math.ceil(eta / 60)}分钟`;
          }
        }
      }, orgId);
      task.status   = 'processing';
      task.barWidth = 85;
      await loadDocs(orgId);
      task.status   = 'done';
      task.barWidth = 100;
      // Fix 6: 成功 3 秒后自动从队列移除
      setTimeout(() => {
        const idx = uploadQueue.value.indexOf(task);
        if (idx !== -1) uploadQueue.value.splice(idx, 1);
      }, 3000);
    } catch (err) {
      task.status = 'error';
      task.error  = err.message || '上传失败';
      ui.showToast('error', `上传失败：${task.filename}`);
      // Fix 6: 失败 8 秒后自动从队列移除（给用户足够时间看到错误）
      setTimeout(() => {
        const idx = uploadQueue.value.indexOf(task);
        if (idx !== -1) uploadQueue.value.splice(idx, 1);
      }, 8000);
    }
  }

  // Fix 6: 手动清除已完成（done/error）的上传记录
  function clearCompletedUploads() {
    uploadQueue.value = uploadQueue.value.filter(t => !['done', 'error'].includes(t.status));
  }

  // ── KB 成员 ─────────────────────────────────────────────────────────
  async function loadKbMembers(orgId) {
    if (!currentKbId.value) return;
    try {
      kbMembers.value = await api.listKbMembers(currentKbId.value, orgId);
    } catch {}
  }

  async function addKbMember(userId, role, orgId) {
    await api.addKbMember(currentKbId.value, userId, role, orgId);
    await loadKbMembers(orgId);
  }

  async function removeKbMember(userId, orgId) {
    await api.removeKbMember(currentKbId.value, userId, orgId);
    await loadKbMembers(orgId);
  }

  async function updateKbMemberRole(userId, role, orgId) {
    await api.updateKbMemberRole(currentKbId.value, userId, role, orgId);
    await loadKbMembers(orgId);
  }

  return {
    knowledgeBases, currentKbId, docs, uploadQueue, kbLoading, docsLoading,
    kbMembers, kbMembersVisible, currentKb, currentKbName,
    resetSelection,
    loadKbs, selectKb, createKb, updateKb, deleteKb,
    loadDocs, uploadFile, clearCompletedUploads,
    startDocPolling, stopDocPolling,
    loadKbMembers, addKbMember, removeKbMember, updateKbMemberRole,
  };
});

function formatFileSize(bytes) {
  if (!bytes) return '0 B';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}
