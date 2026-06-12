/**
 * KB Store — 知识库状态管理
 */
import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import * as api from '../services/api.js';
import { useUiStore } from './ui.js';

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

  const currentKb = computed(() =>
    knowledgeBases.value.find(kb => kb.id === currentKbId.value) || null
  );

  const currentKbName = computed(() => currentKb.value?.name || '');

  // ── 知识库列表 ──────────────────────────────────────────────────────
  async function loadKbs(orgId) {
    kbLoading.value = true;
    try {
      knowledgeBases.value = await api.listKnowledgeBases(orgId);
      // 当前选中的 KB 若不在新列表中，清空
      if (currentKbId.value && !knowledgeBases.value.find(kb => kb.id === currentKbId.value)) {
        currentKbId.value = null;
        docs.value = [];
      }
      if (knowledgeBases.value.length && !currentKbId.value) {
        await selectKb(knowledgeBases.value[0].id, orgId);
      }
    } catch (err) {
      knowledgeBases.value = [];
      ui.showToast('error', err.message || '加载知识库失败');
    } finally {
      kbLoading.value = false;
    }
  }

  async function selectKb(kbId, orgId) {
    currentKbId.value    = kbId;
    kbMembersVisible.value = false;
    await loadDocs(orgId);
  }

  async function createKb(name, description, orgId) {
    await api.createKnowledgeBase(name, description, orgId);
    ui.showToast('success', `知识库「${name}」已创建`);
    await loadKbs(orgId);
  }

  async function updateKb(kbId, name, description, orgId) {
    await api.updateKnowledgeBase(kbId, name, description, orgId);
    ui.showToast('success', '知识库已更新');
    await loadKbs(orgId);
  }

  async function deleteKb(kbId, orgId) {
    await api.deleteKnowledgeBase(kbId, orgId);
    if (currentKbId.value === kbId) { currentKbId.value = null; docs.value = []; }
    await loadKbs(orgId);
  }

  // ── 文档列表 ────────────────────────────────────────────────────────
  async function loadDocs(orgId) {
    if (!currentKbId.value) { docs.value = []; return; }
    docsLoading.value = true;
    try {
      const data = await api.listDocuments(currentKbId.value, orgId);
      docs.value = data.map(mapDoc);
      // 对仍在处理中的文档启动轮询
      docs.value.filter(d => ['PROCESSING','PENDING','PARSING','CHUNKING','EMBEDDING'].includes(d.status))
        .forEach(d => startDocPolling(d.id, orgId));
    } catch (err) {
      if (currentKbId.value) ui.showToast('error', err.message || '加载文档失败');
      docs.value = [];
    } finally {
      docsLoading.value = false;
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
    } catch (err) {
      task.status = 'error';
      task.error  = err.message || '上传失败';
      ui.showToast('error', `上传失败：${task.filename}`);
    }
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
    loadKbs, selectKb, createKb, updateKb, deleteKb,
    loadDocs, uploadFile,
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
