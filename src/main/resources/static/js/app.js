/**
 * 应用入口 —— 组装所有模块
 */
import {state} from './state.js';
import {initChat} from './chat.js';
import {initKnowledgeBase} from './knowledge-base.js';
import {addSession, newSession, renderSessions, switchTab} from './session.js';

window.addEventListener('DOMContentLoaded', () => {
    // 初始化各模块
    initChat();
    initKnowledgeBase();

    // 新建对话按钮
    document.getElementById('newChatBtn')?.addEventListener('click', newSession);

    // 标签页切换
    document.querySelectorAll('[data-tab]').forEach(el => {
        el.addEventListener('click', () => switchTab(el.dataset.tab));
    });

    // 顶部栏知识库快捷按钮
    document.getElementById('kbShortcutBtn')?.addEventListener('click', () => switchTab('kb'));

    // 首次加载 —— 创建初始会话
    const initialId = state.sessionId;
    addSession(initialId, '新对话');
    renderSessions();
});

// 将 switchTab 暴露到 window 供 HTML onclick 调用（兼容旧写法）
window.switchTab = switchTab;

