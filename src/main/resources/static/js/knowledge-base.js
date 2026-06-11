/**
 * 知识库面板组件（V2 企业级多租户）
 *
 * 支持功能：
 * - 多知识库列表与切换
 * - 创建/删除知识库
 * - 文档上传到指定知识库
 * - 文档列表展示与删除
 * - 知识库问答
 * - 成员管理（OWNER/EDITOR/VIEWER）
 */
import {state} from './state.js';
import * as api from './api.js';
import {showToast} from './toast.js';
import {formatFileSize, getFileIcon} from './utils.js';

// ── 初始化 ────────────────────────────────────────────────────

export function initKnowledgeBase() {
    initUploadArea();
    initFileInput();
    loadKnowledgeBases();
}

// ── 知识库列表 ──────────────────────────────────────────────────

async function loadKnowledgeBases() {
    try {
        state.knowledgeBases = await api.listKnowledgeBases();
        renderKbList();

        // 如果有知识库，默认选中第一个
        if (state.knowledgeBases.length > 0 && !state.currentKbId) {
            selectKb(state.knowledgeBases[0].id);
        }
    } catch {
        // 加载失败
    }
}

function renderKbList() {
    const list = document.getElementById('kbList');
    if (!list) return;

    if (state.knowledgeBases.length === 0) {
        list.innerHTML = '<div class="kb-empty-hint">暂无知识库，点击上方按钮创建</div>';
        return;
    }

    list.innerHTML = state.knowledgeBases.map(kb => `
        <div class="kb-item ${kb.id === state.currentKbId ? 'active' : ''}" data-id="${kb.id}">
            <div class="kb-item-icon"></div>
            <div class="kb-item-info">
                <div class="kb-item-name">${escHtml(kb.name)}</div>
                <div class="kb-item-meta">${kb.docCount || 0} 篇文档</div>
            </div>
            <button class="kb-item-delete" data-id="${kb.id}" title="删除知识库"></button>
        </div>
    `).join('');

    // 点击选中
    list.querySelectorAll('.kb-item').forEach(el => {
        el.addEventListener('click', (e) => {
            if (e.target.closest('.kb-item-delete')) return;
            selectKb(Number(el.dataset.id));
        });
    });

    // 删除按钮
    list.querySelectorAll('.kb-item-delete').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            handleDeleteKb(Number(btn.dataset.id));
        });
    });
}

function selectKb(kbId) {
    state.currentKbId = kbId;
    state.currentKb = state.knowledgeBases.find(kb => kb.id === kbId) || null;

    // 更新列表高亮
    document.querySelectorAll('.kb-item').forEach(el => {
        el.classList.toggle('active', Number(el.dataset.id) === kbId);
    });

    // 更新上传区域状态
    const uploadArea = document.getElementById('uploadArea');
    if (uploadArea) {
        uploadArea.style.opacity = kbId ? '1' : '0.5';
        uploadArea.style.pointerEvents = kbId ? 'auto' : 'none';
    }

    // 加载文档列表
    loadDocumentList();
}

async function handleCreateKb() {
    const name = prompt('请输入知识库名称：');
    if (!name || !name.trim()) return;

    try {
        await api.createKnowledgeBase(name.trim(), '');
        showToast('success', `知识库「${name.trim()}」创建成功`);
        await loadKnowledgeBases();
    } catch (e) {
        showToast('error', `创建失败：${e.message}`);
    }
}

async function handleDeleteKb(kbId) {
    const kb = state.knowledgeBases.find(k => k.id === kbId);
    if (!kb) return;

    if (!confirm(`确认删除知识库「${kb.name}」？\n\n此操作不可恢复，所有文档和切片将被永久删除。`)) return;

    try {
        await api.deleteKnowledgeBase(kbId);
        showToast('success', `已删除：${kb.name}`);
        state.knowledgeBases = state.knowledgeBases.filter(k => k.id !== kbId);
        if (state.currentKbId === kbId) {
            state.currentKbId = null;
            state.currentKb = null;
        }
        renderKbList();
        if (state.knowledgeBases.length > 0) {
            selectKb(state.knowledgeBases[0].id);
        } else {
            renderDocList();
        }
    } catch (e) {
        showToast('error', `删除失败：${e.message}`);
    }
}

// ── 文档上传 ──────────────────────────────────────────────────

function initUploadArea() {
    const area = document.getElementById('uploadArea');
    if (!area) return;

    area.addEventListener('click', () => {
        if (!state.currentKbId) {
            showToast('warning', '请先选择或创建知识库');
            return;
        }
        document.getElementById('fileInput').click();
    });

    area.addEventListener('dragover', (e) => {
        e.preventDefault();
        area.classList.add('drag-over');
    });

    area.addEventListener('dragleave', () => area.classList.remove('drag-over'));

    area.addEventListener('drop', (e) => {
        e.preventDefault();
        area.classList.remove('drag-over');
        if (!state.currentKbId) {
            showToast('warning', '请先选择或创建知识库');
            return;
        }
        Array.from(e.dataTransfer.files).forEach(handleUpload);
    });
}

function initFileInput() {
    const input = document.getElementById('fileInput');
    if (!input) return;

    input.addEventListener('change', (e) => {
        Array.from(e.target.files).forEach(handleUpload);
        e.target.value = '';
    });
}

async function handleUpload(file) {
    if (!state.currentKbId) {
        showToast('warning', '请先选择或创建知识库');
        return;
    }

    const allowed = ['pdf', 'doc', 'docx', 'txt', 'md'];
    const ext = file.name.split('.').pop().toLowerCase();
    if (!allowed.includes(ext)) {
        showToast('error', `不支持的文件类型：.${ext}`);
        return;
    }

    if (file.size > 50 * 1024 * 1024) {
        showToast('error', `文件过大（最大 50MB）：${file.name}`);
        return;
    }

    showUploadProgress(file.name);

    try {
        const data = await api.uploadDocument(state.currentKbId, file);
        finishUploadProgress();

        const doc = {
            id: data.documentId ?? Date.now().toString(),
            filename: file.name,
            chunks: data.chunkCount ?? 0,
            size: file.size,
            uploadedAt: new Date().toLocaleString()
        };
        state.docs.push(doc);
        addDocToList(doc);
        showToast('success', `${file.name} 导入成功，共 ${doc.chunks} 个片段`);

        // 刷新知识库列表中的文档计数
        await loadKnowledgeBases();

    } catch (e) {
        hideUploadProgress();
        showToast('error', `上传失败：${e.message}`);
    }
}

// ── 文档列表 ──────────────────────────────────────────────────

async function loadDocumentList() {
    if (!state.currentKbId) {
        state.docs = [];
        renderDocList();
        return;
    }

    try {
        const docs = await api.listDocuments(state.currentKbId);
        state.docs = docs.map(d => ({
            id: d.id,
            filename: d.name ?? d.filename,
            chunks: d.chunkCount ?? d.chunks ?? 0,
            size: d.fileSize ?? 0,
            status: d.parseStatus ?? 'UNKNOWN',
            uploadedAt: d.createdAt ?? new Date().toLocaleString()
        }));
        renderDocList();
    } catch {
        state.docs = [];
        renderDocList();
    }
}

function renderDocList() {
    const list = document.getElementById('docList');
    if (!list) return;

    if (!state.currentKbId) {
        list.innerHTML = '<div class="empty-docs">请先选择一个知识库</div>';
        return;
    }

    if (state.docs.length === 0) {
        list.innerHTML = '<div class="empty-docs">暂无文档，上传后 AI 可基于文档内容回答</div>';
        return;
    }

    list.innerHTML = '';
    state.docs.forEach(doc => addDocToList(doc));
}

function addDocToList(doc) {
    const list = document.getElementById('docList');
    if (!list) return;

    list.querySelector('.empty-docs')?.remove();

    const { icon, cls } = getFileIcon(doc.filename);
    const sizeStr = doc.size ? ` · ${formatFileSize(doc.size)}` : '';
    const statusIcon = doc.status === 'DONE' ? '完成' : doc.status === 'FAILED' ? '失败' : '⏳';

    const item = document.createElement('div');
    item.className = 'doc-item';
    item.dataset.id = doc.id;
    item.innerHTML = `
        <div class="doc-icon ${cls}">${icon}</div>
        <div class="doc-info">
            <div class="doc-name" title="${escHtml(doc.filename)}">${escHtml(doc.filename)}</div>
            <div class="doc-meta">${statusIcon} ${doc.chunks} 个切片${sizeStr} · ${doc.uploadedAt}</div>
        </div>
        <div class="doc-actions">
            <button class="doc-delete" title="从知识库删除此文档"></button>
        </div>
    `;

    item.querySelector('.doc-delete').addEventListener('click', () => handleDeleteDoc(doc, item));
    list.appendChild(item);
}

async function handleDeleteDoc(doc, itemEl) {
    if (!confirm(`确认从知识库删除：${doc.filename}？\n\n此操作不可恢复。`)) return;

    try {
        await api.deleteDocument(state.currentKbId, doc.id);
        itemEl.remove();
        state.docs = state.docs.filter(d => d.id !== doc.id);

        if (state.docs.length === 0) {
            document.getElementById('docList').innerHTML =
                '<div class="empty-docs">暂无文档，上传后 AI 可基于文档内容回答</div>';
        }
        showToast('success', `已删除：${doc.filename}`);
        await loadKnowledgeBases();
    } catch (e) {
        showToast('error', `删除失败：${e.message}`);
    }
}

// ── 知识库问答 ──────────────────────────────────────────────────

export async function queryKb(question) {
    if (!state.currentKbId) {
        showToast('warning', '请先选择一个知识库');
        return null;
    }
    try {
        return await api.queryKnowledgeBase(state.currentKbId, question);
    } catch (e) {
        showToast('error', `知识库问答失败：${e.message}`);
        return null;
    }
}

// ── 成员管理 ──────────────────────────────────────────────────

export async function loadKbMembers() {
    if (!state.currentKbId) return [];
    try {
        return await api.listKbMembers(state.currentKbId);
    } catch { return []; }
}

export async function addMemberToKb(userId, role) {
    if (!state.currentKbId) return;
    try {
        await api.addKbMember(state.currentKbId, userId, role);
        showToast('success', `已添加成员：${userId}`);
    } catch (e) {
        showToast('error', `添加成员失败：${e.message}`);
    }
}

// ── 上传进度 ──────────────────────────────────────────────────

let _progressTimer = null;

function showUploadProgress(filename) {
    const progress = document.getElementById('uploadProgress');
    const fill = document.getElementById('progressFill');
    const fnEl = document.getElementById('progressFilename');
    const pctEl = document.getElementById('progressPct');

    if (!progress) return;
    progress.style.display = 'block';
    if (fnEl) fnEl.textContent = filename;

    let p = 0;
    _progressTimer = setInterval(() => {
        p = Math.min(p + Math.random() * 12, 88);
        if (fill) fill.style.width = p + '%';
        if (pctEl) pctEl.textContent = Math.round(p) + '%';
    }, 200);
}

function finishUploadProgress() {
    clearInterval(_progressTimer);
    const fill = document.getElementById('progressFill');
    const pctEl = document.getElementById('progressPct');
    if (fill) fill.style.width = '100%';
    if (pctEl) pctEl.textContent = '100%';
    setTimeout(hideUploadProgress, 800);
}

function hideUploadProgress() {
    clearInterval(_progressTimer);
    const progress = document.getElementById('uploadProgress');
    if (progress) progress.style.display = 'none';
    const fill = document.getElementById('progressFill');
    if (fill) fill.style.width = '0%';
}

// ── 工具函数 ──────────────────────────────────────────────────

function escHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

// 将创建知识库按钮事件绑定暴露出去（供 index.html 中的按钮调用）
window.handleCreateKb = handleCreateKb;

