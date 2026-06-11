/**
 * 应用入口 —— 组装所有模块
 */
import {getUser, logout, requireAuth} from './auth.js';
import {state} from './state.js';
import {initChat} from './chat.js';
import {addMemberToKb, initKnowledgeBase, loadKbMembers} from './knowledge-base.js';
import {initOrganization} from './organization.js';
import {addSession, newSession, renderSessions, switchTab} from './session.js';
import {showToast} from './toast.js';

window.addEventListener('DOMContentLoaded', () => {
    // ── 认证守卫 ────────────────────────────────────────────
    if (!requireAuth()) return;

    // ── 显示当前用户信息 + 登出按钮 ──────────────────────────
    renderUserInfo();

    // 初始化各模块
    initChat();
    initKnowledgeBase();
    initOrganization();

    // 新建对话按钮
    document.getElementById('newChatBtn')?.addEventListener('click', newSession);

    // 标签页切换
    document.querySelectorAll('[data-tab]').forEach(el => {
        el.addEventListener('click', () => switchTab(el.dataset.tab));
    });

    // 顶部栏知识库快捷按钮
    document.getElementById('kbShortcutBtn')?.addEventListener('click', () => switchTab('kb'));

    // 知识库成员管理
    initKbMembers();

    // 组织成员查看
    initOrgMembers();

    // 首次加载 —— 创建初始会话
    const initialId = state.sessionId;
    addSession(initialId, '新对话');
    renderSessions();
});

/**
 * 在侧边栏底部渲染用户信息和登出按钮
 */
function renderUserInfo() {
    const user = getUser();
    if (!user) return;

    const sidebar = document.querySelector('.sidebar-bottom');
    if (!sidebar) return;

    const userHtml = `
        <div class="user-info" style="
            display:flex; align-items:center; gap:8px;
            padding:10px 12px;
            background:rgba(255,255,255,.06);
            border-radius:8px;
            margin-bottom:10px;
        ">
            <div style="
                width:30px; height:30px; border-radius:50%;
                background:linear-gradient(135deg,#6366f1,#8b5cf6);
                display:flex; align-items:center; justify-content:center;
                font-size:13px; color:#fff; font-weight:700; flex-shrink:0;
            ">${(user.username || 'U')[0].toUpperCase()}</div>
            <div style="flex:1; min-width:0;">
                <div style="color:#fff; font-size:13px; font-weight:500;
                            overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">
                    ${user.username || user.userId}
                </div>
                <div style="color:rgba(255,255,255,.35); font-size:11px;">已登录</div>
            </div>
            <button id="logoutBtn" title="退出登录" style="
                background:none; border:none; cursor:pointer;
                color:rgba(255,255,255,.4); font-size:16px; padding:2px 4px;
                border-radius:4px; transition:color 0.15s;
            ">⎋</button>
        </div>
    `;

    sidebar.insertAdjacentHTML('afterbegin', userHtml);

    document.getElementById('logoutBtn')?.addEventListener('click', () => {
        if (confirm('确认退出登录？')) logout();
    });
}

/**
 * 初始化知识库成员管理交互
 */
function initKbMembers() {
    const manageBtn = document.getElementById('kbManageMembersBtn');
    const closeBtn = document.getElementById('kbMembersCloseBtn');
    const addBtn = document.getElementById('kbMemberAddBtn');
    const panel = document.getElementById('kbMembersPanel');

    if (manageBtn) {
        manageBtn.addEventListener('click', async () => {
            if (panel) panel.style.display = 'block';
            const members = await loadKbMembers();
            renderKbMembersList(members);
        });
    }

    if (closeBtn && panel) {
        closeBtn.addEventListener('click', () => { panel.style.display = 'none'; });
    }

    if (addBtn) {
        addBtn.addEventListener('click', async () => {
            const userIdInput = document.getElementById('kbMemberUserId');
            const roleSelect = document.getElementById('kbMemberRole');
            if (!userIdInput?.value?.trim()) {
                showToast('warning', '请输入用户 ID');
                return;
            }
            await addMemberToKb(userIdInput.value.trim(), roleSelect?.value || 'VIEWER');
            userIdInput.value = '';
            // 刷新成员列表
            const members = await loadKbMembers();
            renderKbMembersList(members);
        });
    }
}

function renderKbMembersList(members) {
    const list = document.getElementById('kbMembersList');
    if (!list) return;

    if (!members || members.length === 0) {
        list.innerHTML = '<div class="empty-hint" style="color:#888; font-size:13px; padding:12px;">暂无成员</div>';
        return;
    }

    list.innerHTML = members.map(m => {
        const roleLabel = m.role === 'OWNER' ? '拥有者' : m.role === 'EDITOR' ? '编辑者' : '只读';
        return `
            <div class="kb-member-item">
                <span class="kb-member-id">${m.userId}</span>
                <span class="kb-member-role">${roleLabel}</span>
            </div>
        `;
    }).join('');
}

/**
 * 初始化组织成员查看
 */
function initOrgMembers() {
    const viewBtn = document.getElementById('viewOrgMembersBtn');
    if (viewBtn) {
        viewBtn.addEventListener('click', async () => {
            const {showOrgMembers} = await import('./organization.js');
            if (state.currentOrgId) {
                showOrgMembers(state.currentOrgId);
            } else {
                showToast('warning', '请先选择一个组织');
            }
        });
    }
}

// 将 switchTab 暴露到 window
window.switchTab = switchTab;

