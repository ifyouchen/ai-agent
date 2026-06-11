/**
 * 会话管理模块
 */
import {generateId, state} from './state.js';
import {showToast} from './toast.js';
import {clearChatUI} from './chat.js';

/**
 * 渲染侧边栏会话列表
 */
export function renderSessions() {
    const list = document.getElementById('sessionList');
    if (!list) return;

    if (state.sessions.length === 0) {
        list.innerHTML = '<div class="session-empty">暂无历史对话</div>';
        return;
    }

    list.innerHTML = state.sessions.map(s => `
        <div class="session-item ${s.id === state.sessionId ? 'active' : ''}"
             data-id="${s.id}"
             title="${s.title}">
            <span class="session-icon">💬</span>
            <span class="session-title">${s.title}</span>
            <button class="session-delete" data-id="${s.id}" title="删除会话">✕</button>
        </div>
    `).join('');

    // 点击切换
    list.querySelectorAll('.session-item').forEach(el => {
        el.addEventListener('click', (e) => {
            if (e.target.classList.contains('session-delete')) return;
            switchSession(el.dataset.id);
        });
    });

    // 删除按钮
    list.querySelectorAll('.session-delete').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            removeSession(btn.dataset.id);
        });
    });
}

/**
 * 新建会话
 */
export function newSession() {
    const id = generateId();
    state.sessionId = id;
    addSession(id, '新对话');
    clearChatUI();
    document.getElementById('topbarTitle').textContent = '新对话';
    switchTab('chat');
}

/**
 * 添加会话到列表
 */
export function addSession(id, title) {
    if (state.sessions.find(s => s.id === id)) {
        setActiveSession(id);
        return;
    }
    state.sessions.unshift({ id, title, createdAt: Date.now() });
    renderSessions();
    setActiveSession(id);
}

/**
 * 切换会话
 */
export function switchSession(id) {
    const session = state.sessions.find(s => s.id === id);
    if (!session) return;
    state.sessionId = id;
    setActiveSession(id);
    document.getElementById('topbarTitle').textContent = session.title;
    clearChatUI();
}

/**
 * 删除会话
 */
export function removeSession(id) {
    const idx = state.sessions.findIndex(s => s.id === id);
    if (idx === -1) return;
    const [removed] = state.sessions.splice(idx, 1);
    showToast('info', `已删除会话：${removed.title}`);

    // 如果删除的是当前会话，切换到第一个会话或新建
    if (id === state.sessionId) {
        if (state.sessions.length > 0) {
            switchSession(state.sessions[0].id);
        } else {
            newSession();
        }
    }
    renderSessions();
}

/**
 * 高亮当前活动会话
 */
export function setActiveSession(id) {
    document.querySelectorAll('.session-item').forEach(el => {
        el.classList.toggle('active', el.dataset.id === id);
    });
}

/**
 * 更新会话标题
 */
export function updateSessionTitle(id, title) {
    const session = state.sessions.find(s => s.id === id);
    if (session && session.title === '新对话') {
        session.title = title;
        renderSessions();
        document.getElementById('topbarTitle').textContent = title;
    }
}

/**
 * 标签页切换
 */
export function switchTab(tab) {
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
    const tabEl = document.getElementById('tab-' + tab);
    const panelEl = document.getElementById('panel-' + tab);
    if (tabEl) tabEl.classList.add('active');
    if (panelEl) panelEl.classList.add('active');
}

