/**
 * 知识库面板组件
 */
import {state} from './state.js';
import {deleteDocument, listDocuments, uploadDocument} from './api.js';
import {showToast} from './toast.js';
import {formatFileSize, getFileIcon} from './utils.js';

// ── 初始化 ────────────────────────────────────────────────────

export function initKnowledgeBase() {
    initUploadArea();
    initFileInput();
    loadDocumentList();
}

function initUploadArea() {
    const area = document.getElementById('uploadArea');
    if (!area) return;

    area.addEventListener('click', () => document.getElementById('fileInput').click());

    area.addEventListener('dragover', (e) => {
        e.preventDefault();
        area.classList.add('drag-over');
    });

    area.addEventListener('dragleave', () => area.classList.remove('drag-over'));

    area.addEventListener('drop', (e) => {
        e.preventDefault();
        area.classList.remove('drag-over');
        const files = Array.from(e.dataTransfer.files);
        files.forEach(handleUpload);
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

// ── 文档上传 ──────────────────────────────────────────────────

async function handleUpload(file) {
    // 文件类型校验
    const allowed = ['pdf', 'doc', 'docx', 'txt', 'md'];
    const ext = file.name.split('.').pop().toLowerCase();
    if (!allowed.includes(ext)) {
        showToast('error', `不支持的文件类型：.${ext}`);
        return;
    }

    // 文件大小校验（最大 50MB）
    if (file.size > 50 * 1024 * 1024) {
        showToast('error', `文件过大（最大 50MB）：${file.name}`);
        return;
    }

    showUploadProgress(file.name);

    try {
        const data = await uploadDocument(file);
        finishUploadProgress();

        const doc = {
            id: data.documentId ?? Date.now().toString(),
            filename: file.name,
            chunks: data.chunks ?? 0,
            size: file.size,
            uploadedAt: new Date().toLocaleString()
        };
        state.docs.push(doc);
        addDocToList(doc);
        showToast('success', `✅ ${file.name} 导入成功，共 ${data.chunks} 个片段`);

    } catch (e) {
        hideUploadProgress();
        showToast('error', `❌ 上传失败：${e.message}`);
    }
}

// ── 文档列表渲染 ──────────────────────────────────────────────

async function loadDocumentList() {
    try {
        const docs = await listDocuments();
        state.docs = docs.map(d => ({
            id: d.id,
            filename: d.name ?? d.filename,
            chunks: d.chunkCount ?? d.chunks ?? 0,
            size: d.fileSize ?? 0,
            uploadedAt: d.createdAt ?? new Date().toLocaleString()
        }));
        renderDocList();
    } catch {
        // 加载失败时展示空列表，不报错（服务可能还未启动）
    }
}

function renderDocList() {
    const list = document.getElementById('docList');
    if (!list) return;

    if (state.docs.length === 0) {
        list.innerHTML = '<div class="empty-docs">📭 暂无文档，上传后 AI 可基于文档内容回答</div>';
        return;
    }

    list.innerHTML = '';
    state.docs.forEach(doc => addDocToList(doc));
}

function addDocToList(doc) {
    const list = document.getElementById('docList');
    if (!list) return;

    // 移除空状态提示
    list.querySelector('.empty-docs')?.remove();

    const { icon, cls } = getFileIcon(doc.filename);
    const sizeStr = doc.size ? ` · ${formatFileSize(doc.size)}` : '';

    const item = document.createElement('div');
    item.className = 'doc-item';
    item.dataset.id = doc.id;
    item.innerHTML = `
        <div class="doc-icon ${cls}">${icon}</div>
        <div class="doc-info">
            <div class="doc-name" title="${doc.filename}">${doc.filename}</div>
            <div class="doc-meta">切片数：${doc.chunks}${sizeStr} · ${doc.uploadedAt}</div>
        </div>
        <div class="doc-actions">
            <button class="doc-delete" title="从知识库删除此文档">🗑️</button>
        </div>
    `;

    item.querySelector('.doc-delete').addEventListener('click', () => handleDelete(doc, item));
    list.appendChild(item);
}

async function handleDelete(doc, itemEl) {
    if (!confirm(`确认从知识库删除：${doc.filename}？\n\n此操作不可恢复，删除后 AI 将无法再引用该文档内容。`)) return;

    try {
        await deleteDocument(doc.id);
        itemEl.remove();
        state.docs = state.docs.filter(d => d.id !== doc.id);

        // 全部删完后显示空状态
        if (state.docs.length === 0) {
            document.getElementById('docList').innerHTML =
                '<div class="empty-docs">📭 暂无文档，上传后 AI 可基于文档内容回答</div>';
        }
        showToast('success', `✅ 已删除：${doc.filename}`);
    } catch (e) {
        showToast('error', `❌ 删除失败：${e.message}`);
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

