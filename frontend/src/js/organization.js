/**
 * 组织管理模块（企业级多租户）
 *
 * 支持功能：
 * - 查看我的组织列表
 * - 创建企业组织
 * - 邀请/移除组织成员
 * - 切换当前组织
 */
import {state} from './state.js';
import * as api from './api.js';
import {showToast} from './toast.js';

// ── 初始化 ────────────────────────────────────────────────────

export function initOrganization() {
    loadOrganizations();
}

// ── 组织列表 ──────────────────────────────────────────────────

async function loadOrganizations() {
    try {
        const memberships = await api.listOrganizations();
        state.organizations = memberships.map(m => ({
            orgId: m.orgId,
            role: m.role,
            name: m.orgId  // 后端暂不返回组织名称，用 orgId 显示
        }));

        // 设置默认组织
        if (state.organizations.length > 0 && !state.currentOrgId) {
            state.currentOrgId = state.organizations[0].orgId;
        }

        renderOrgList();
    } catch {
        // 加载失败
    }
}

function renderOrgList() {
    const list = document.getElementById('orgList');
    if (!list) return;

    if (state.organizations.length === 0) {
        list.innerHTML = '<div class="org-empty-hint">暂无组织</div>';
        return;
    }

    list.innerHTML = state.organizations.map(org => {
        const typeLabel = org.orgId.startsWith('org_') ? '👤 个人' : '🏢 企业';
        const roleLabel = org.role === 'OWNER' ? '拥有者' : org.role === 'ADMIN' ? '管理员' : '成员';
        const isActive = org.orgId === state.currentOrgId;

        return `
            <div class="org-item ${isActive ? 'active' : ''}" data-org-id="${org.orgId}">
                <div class="org-item-icon">${org.orgId.startsWith('org_') ? '👤' : '🏢'}</div>
                <div class="org-item-info">
                    <div class="org-item-name">${typeLabel} ${org.orgId.startsWith('org_') ? '个人空间' : escHtml(org.name || org.orgId)}</div>
                    <div class="org-item-meta">${roleLabel}</div>
                </div>
            </div>
        `;
    }).join('');

    // 点击切换组织
    list.querySelectorAll('.org-item').forEach(el => {
        el.addEventListener('click', () => {
            state.currentOrgId = el.dataset.orgId;
            renderOrgList();
            showToast('info', '已切换组织');
        });
    });
}

// ── 创建企业组织 ──────────────────────────────────────────────────

async function handleCreateOrg() {
    const name = prompt('请输入企业/组织名称：');
    if (!name || !name.trim()) return;

    const description = prompt('组织描述（可选）：', '') || '';

    try {
        const data = await api.createOrganization(name.trim(), description.trim());
        showToast('success', `✅ 企业组织「${name.trim()}」创建成功`);
        await loadOrganizations();
    } catch (e) {
        showToast('error', `❌ 创建失败：${e.message}`);
    }
}

// ── 组织成员管理 ──────────────────────────────────────────────────

async function showOrgMembers(orgId) {
    try {
        const data = await api.getOrganization(orgId);
        const members = data.members || [];

        const membersHtml = members.map(m => {
            const roleLabel = m.role === 'OWNER' ? '👑 拥有者' : m.role === 'ADMIN' ? '🛡️ 管理员' : '👤 成员';
            const canRemove = m.role !== 'OWNER';
            return `
                <div class="member-item">
                    <div class="member-info">
                        <div class="member-id">${escHtml(m.userId)}</div>
                        <div class="member-role">${roleLabel}</div>
                    </div>
                    ${canRemove ? `<button class="member-remove" data-user-id="${m.userId}" data-org-id="${orgId}">移除</button>` : ''}
                </div>
            `;
        }).join('');

        // 使用模态框展示
        showModal(`组织成员 - ${orgId}`, membersHtml || '<div class="empty-hint">暂无成员</div>');

        // 绑定移除按钮
        document.querySelectorAll('.member-remove').forEach(btn => {
            btn.addEventListener('click', async () => {
                const userId = btn.dataset.userId;
                const orgId = btn.dataset.orgId;
                if (!confirm(`确认移除成员 ${userId}？`)) return;
                try {
                    await api.removeOrgMember(orgId, userId);
                    showToast('success', `✅ 已移除：${userId}`);
                    showOrgMembers(orgId);  // 刷新
                } catch (e) {
                    showToast('error', `❌ 移除失败：${e.message}`);
                }
            });
        });

    } catch (e) {
        showToast('error', `❌ 加载成员失败：${e.message}`);
    }
}

async function handleInviteMember() {
    if (!state.currentOrgId) {
        showToast('warning', '请先选择一个组织');
        return;
    }

    const userId = prompt('请输入要邀请的用户 ID：');
    if (!userId || !userId.trim()) return;

    const role = prompt('请选择角色（输入数字）：\n1. 成员\n2. 管理员', '1');
    const roleMap = { '1': 'MEMBER', '2': 'ADMIN' };
    const roleValue = roleMap[role] || 'MEMBER';

    try {
        await api.inviteOrgMember(state.currentOrgId, userId.trim(), roleValue);
        showToast('success', `✅ 已邀请 ${userId.trim()} 加入组织`);
    } catch (e) {
        showToast('error', `❌ 邀请失败：${e.message}`);
    }
}

// ── 简易模态框 ──────────────────────────────────────────────────

function showModal(title, contentHtml) {
    // 移除已有模态框
    document.getElementById('orgModal')?.remove();

    const modal = document.createElement('div');
    modal.id = 'orgModal';
    modal.className = 'modal-overlay';
    modal.innerHTML = `
        <div class="modal-content">
            <div class="modal-header">
                <h3>${escHtml(title)}</h3>
                <button class="modal-close" id="modalCloseBtn">✕</button>
            </div>
            <div class="modal-body">${contentHtml}</div>
        </div>
    `;
    document.body.appendChild(modal);

    document.getElementById('modalCloseBtn')?.addEventListener('click', () => modal.remove());
    modal.addEventListener('click', (e) => { if (e.target === modal) modal.remove(); });
}

// ── 工具函数 ──────────────────────────────────────────────────

function escHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

// 暴露全局方法
window.handleCreateOrg = handleCreateOrg;
window.handleInviteMember = handleInviteMember;

