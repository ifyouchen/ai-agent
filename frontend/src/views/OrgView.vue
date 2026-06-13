<template>
  <div class="org-view">
    <!-- 顶部标题 -->
    <div class="org-header">
      <h2 class="org-title">
        <div class="org-title-icon">
          <svg viewBox="0 0 24 24" fill="none" width="20" height="20">
            <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            <circle cx="9" cy="7" r="4" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            <path d="M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
        </div>
        组织管理
        <svg v-if="org.orgLoading" class="inline-spinner" viewBox="0 0 24 24" fill="none" width="14" height="14">
          <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
        </svg>
      </h2>
      <button class="org-create-btn" type="button" @click="handleCreateOrg">
        <svg viewBox="0 0 24 24" fill="none" width="14" height="14">
          <circle cx="12" cy="12" r="8" stroke="currentColor" stroke-width="2"/>
          <path d="M12 8v8M8 12h8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        </svg>
        创建企业组织
      </button>
    </div>

    <!-- 统计概览 -->
    <div class="org-stats">
      <div class="org-stat-card">
        <div class="org-stat-value">{{ org.organizations.length }}</div>
        <div class="org-stat-label">我的组织</div>
        <div class="org-stat-sublabel">{{ enterpriseCount }} 个企业 · {{ personalCount }} 个个人</div>
      </div>
      <div class="org-stat-card">
        <div class="org-stat-value">{{ enterpriseCount }}</div>
        <div class="org-stat-label">企业组织</div>
        <div class="org-stat-sublabel">可邀请成员协作</div>
      </div>
      <div class="org-stat-card">
        <div class="org-stat-value">{{ personalCount }}</div>
        <div class="org-stat-label">个人空间</div>
        <div class="org-stat-sublabel">私有隔离空间</div>
      </div>
    </div>

    <!-- 组织列表 -->
    <div class="org-list-title">
      <svg viewBox="0 0 24 24" fill="none" width="16" height="16">
        <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        <circle cx="9" cy="7" r="4" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        <path d="M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
      </svg>
      组织列表
      <span style="font-size:12px; color:var(--text-muted); font-weight:400; margin-left:4px;">点击切换当前组织</span>
    </div>

    <div class="org-list">
      <!-- 加载中 -->
      <div v-if="org.orgLoading && !org.organizations.length" class="empty-state">
        <svg class="inline-spinner" viewBox="0 0 24 24" fill="none" width="20" height="20">
          <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
        </svg>
        <div class="empty-state-text">加载中…</div>
      </div>

      <!-- 空状态 -->
      <div v-else-if="!org.orgLoading && !org.organizations.length" class="empty-state">
        <div class="empty-state-icon">
          <svg viewBox="0 0 24 24" fill="none" width="32" height="32">
            <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            <circle cx="9" cy="7" r="4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            <path d="M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
          </svg>
        </div>
        <div class="empty-state-text">暂无组织</div>
        <div class="empty-state-hint">创建企业组织以开始协作</div>
        <button class="org-create-btn" type="button" @click="handleCreateOrg" style="margin-top:4px;">
          <svg viewBox="0 0 24 24" fill="none" width="14" height="14">
            <circle cx="12" cy="12" r="8" stroke="currentColor" stroke-width="2"/>
            <path d="M12 8v8M8 12h8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
          创建企业组织
        </button>
      </div>

      <!-- 组织卡片 -->
      <div
        v-for="item in org.organizations"
        :key="item.orgId"
        class="org-card"
        :class="{ active: item.orgId === org.currentOrgId }"
        @click="handleSelectOrg(item.orgId)"
      >
        <div class="org-card-icon" :class="item.orgType === 'PERSONAL' ? 'personal' : 'enterprise'">
          <span v-if="item.orgType === 'PERSONAL'">个人</span>
          <svg v-else viewBox="0 0 24 24" fill="none" width="20" height="20">
            <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            <polyline points="9 22 9 12 15 12 15 22" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="org-card-info">
          <div class="org-card-name">
            {{ item.orgType === 'PERSONAL' ? '个人空间' : (item.name || item.orgId) }}
            <span class="org-card-role-badge" :class="item.role.toLowerCase()">{{ orgRoleLabel(item.role) }}</span>
          </div>
          <div class="org-card-meta">
            {{ item.orgType === 'PERSONAL' ? '私有空间' : '企业组织' }}
            <span v-if="item.orgId === org.currentOrgId">· 当前选中</span>
          </div>
        </div>
        <span v-if="item.orgId === org.currentOrgId" class="org-active-badge">当前</span>
        <div v-if="item.orgType === 'ENTERPRISE'" class="org-card-actions" @click.stop>
          <button v-if="item.role === 'OWNER'" class="org-card-action-btn" type="button" title="编辑组织" @click.stop="handleEditOrg(item)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
              <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
          <button v-if="item.role !== 'OWNER'" class="org-card-action-btn danger" type="button" title="退出组织" @click.stop="handleLeaveOrg(item)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
          <button v-if="item.role === 'OWNER'" class="org-card-action-btn danger" type="button" title="删除组织" @click.stop="handleDeleteOrg(item)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
              <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/>
            </svg>
          </button>
        </div>
      </div>
    </div>

    <!-- 当前组织操作区 -->
    <div v-if="org.currentOrgId" class="org-actions-panel">
      <div class="org-actions-title">
        <svg viewBox="0 0 24 24" fill="none" width="16" height="16" style="margin-right:6px;">
          <circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          <path d="M12 1v6m0 6v6m4.22-10.22 4.24-4.24M6.34 6.34 2.1 2.1m18.8 18.8-4.24-4.24M6.34 17.66l-4.24 4.24M23 12h-6m-6 0H1m20.22 4.22-4.24-4.24M6.34 17.66l-4.24 4.24" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        </svg>
        {{ org.currentOrg?.orgType === 'ENTERPRISE' ? (org.currentOrg?.name || '组织') : '个人空间' }} 操作
      </div>

      <template v-if="org.currentOrg?.orgType === 'ENTERPRISE'">
        <div class="org-invite-wrap">
          <div class="org-member-search-wrap">
            <input
              v-model.trim="inviteUsername"
              type="text"
              placeholder="输入用户名搜索..."
              class="org-member-input"
              autocomplete="off"
              @input="searchInviteUsers"
              @blur="hideInviteSugg"
              @focus="searchInviteUsers"
            />
            <div v-if="inviteSuggestions.length && inviteSuggVisible" class="org-member-suggestions">
              <button
                v-for="u in inviteSuggestions"
                :key="u.userId"
                class="org-member-suggestion-item"
                type="button"
                @mousedown.prevent="selectInviteSugg(u)"
              >
                <span class="org-member-sug-name">{{ u.username }}</span>
                <span class="org-member-sug-id">{{ u.userId }}</span>
              </button>
            </div>
          </div>
          <select v-model="inviteRole" class="org-member-role-select">
            <option value="MEMBER">成员</option>
            <option value="ADMIN">管理员</option>
          </select>
          <button class="org-member-add-btn" type="button" @click="doInvite">邀请</button>
        </div>
        <div class="org-action-btns">
          <button class="org-action-btn" type="button" @click="showMembers">
            <svg viewBox="0 0 24 24" fill="none" width="14" height="14">
              <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              <circle cx="9" cy="7" r="4" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              <path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            </svg>
            查看成员
          </button>
        </div>
      </template>
      <template v-else>
        <p class="org-personal-hint">
          个人空间为私有隔离空间，不支持邀请成员。<br>如需多人协作，请创建企业组织。
        </p>
      </template>
    </div>

    <!-- 成员弹窗 -->
    <div v-if="membersModal.visible" class="members-modal-overlay" @click.self="membersModal.visible = false">
      <div class="members-modal">
        <div class="members-modal-header">
          <h3>{{ membersModal.title }}</h3>
          <button class="members-modal-close" type="button" @click="membersModal.visible = false">×</button>
        </div>
        <div class="members-modal-body">
          <div v-if="!membersModal.members.length" class="empty-state">
            <div class="empty-state-text">暂无成员</div>
          </div>
          <div v-for="member in membersModal.members" :key="member.userId" class="member-list-item">
            <div class="member-info">
              <div class="member-name">{{ member.username || member.userId }}</div>
              <div v-if="member.username" class="member-id">{{ member.userId }}</div>
            </div>
            <template v-if="member.role === 'OWNER'">
              <span class="member-role-badge owner">所有者</span>
            </template>
            <template v-else>
              <select
                class="member-role-select"
                :value="member.role"
                @change="org.updateMemberRole(org.currentOrgId, member.userId, $event.target.value)"
              >
                <option value="MEMBER">成员</option>
                <option value="ADMIN">管理员</option>
              </select>
              <button v-if="member.role !== 'OWNER'" class="member-remove-btn" type="button" @click="removeMemberFromModal(member.userId)">移除</button>
            </template>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue';
import { useOrgStore } from '../stores/org.js';
import { useUiStore } from '../stores/ui.js';
import * as api from '../services/api.js';

const org = useOrgStore();
const ui  = useUiStore();

const inviteUsername    = ref('');
const inviteUserId      = ref('');
const inviteRole        = ref('MEMBER');
const inviteSuggestions = ref([]);
const inviteSuggVisible = ref(false);

const membersModal = reactive({
  visible: false,
  title:   '',
  orgId:   null,
  members: [],
});

const enterpriseCount = computed(() =>
  org.organizations.filter(o => o.orgType === 'ENTERPRISE').length
);

const personalCount = computed(() =>
  org.organizations.filter(o => o.orgType === 'PERSONAL').length
);

function orgRoleLabel(role) {
  const map = { OWNER: '所有者', ADMIN: '管理员', MEMBER: '成员' };
  return map[role] || role || '';
}

function handleSelectOrg(orgId) {
  org.selectOrg(orgId);
}

async function handleCreateOrg() {
  const form = await ui.showForm({
    title: '创建企业组织',
    confirmText: '创建',
    fields: [
      { key: 'name',        label: '组织名称', placeholder: '例如：研发团队、产品部门' },
      { key: 'description', label: '描述（可选）', placeholder: '简要描述组织用途', multiline: true },
    ],
  });
  if (!form?.name?.trim()) return;
  try { await org.createOrg(form.name.trim(), form.description?.trim() || ''); }
  catch (err) { ui.showToast('error', err.message || '创建失败'); }
}

async function handleEditOrg(item) {
  const form = await ui.showForm({
    title: '编辑组织',
    confirmText: '保存',
    fields: [
      { key: 'name',        label: '组织名称', defaultValue: item.name },
      { key: 'description', label: '描述', defaultValue: item.description || '', multiline: true },
    ],
  });
  if (!form?.name?.trim()) return;
  try { await org.updateOrg(item.orgId, form.name.trim(), form.description?.trim() || ''); }
  catch (err) { ui.showToast('error', err.message || '更新失败'); }
}

async function handleDeleteOrg(item) {
  const confirmed = await ui.showConfirm({
    title: '删除组织',
    message: `确认删除组织「${item.name}」？此操作不可恢复。`,
    confirmText: '删除',
    variant: 'danger',
  });
  if (!confirmed) return;
  try { await org.deleteOrg(item.orgId); }
  catch (err) { ui.showToast('error', err.message || '删除失败'); }
}

async function handleLeaveOrg(item) {
  const confirmed = await ui.showConfirm({
    title: '退出组织',
    message: `确认退出组织「${item.name}」？`,
    confirmText: '退出',
    variant: 'danger',
  });
  if (!confirmed) return;
  try { await org.leaveOrg(item.orgId); }
  catch (err) { ui.showToast('error', err.message || '退出失败'); }
}

let _searchTimer = null;
function searchInviteUsers() {
  clearTimeout(_searchTimer);
  _searchTimer = setTimeout(async () => {
    if (!inviteUsername.value.trim()) { inviteSuggestions.value = []; return; }
    inviteSuggestions.value = await api.searchUsers(inviteUsername.value.trim());
    inviteSuggVisible.value = true;
  }, 200);
}

function hideInviteSugg() { setTimeout(() => { inviteSuggVisible.value = false; }, 150); }

function selectInviteSugg(u) {
  inviteUsername.value    = u.username;
  inviteUserId.value      = u.userId;
  inviteSuggestions.value = [];
  inviteSuggVisible.value = false;
}

async function doInvite() {
  if (!inviteUserId.value) { ui.showToast('warning', '请先从搜索结果中选择用户'); return; }
  try {
    await org.inviteMember(org.currentOrgId, inviteUserId.value, inviteRole.value);
    ui.showToast('success', `已邀请 ${inviteUsername.value} 加入组织`);
    inviteUsername.value = ''; inviteUserId.value = '';
  } catch (err) {
    ui.showToast('error', err.message || '邀请失败');
  }
}

async function showMembers() {
  const item = org.currentOrg;
  if (!item) return;
  membersModal.visible = true;
  membersModal.title   = `${item.name || '组织'} 的成员`;
  membersModal.orgId   = item.orgId;
  membersModal.members = await org.getOrgMembers(item.orgId);
}

async function removeMemberFromModal(userId) {
  try {
    await org.removeMember(membersModal.orgId, userId);
    membersModal.members = await org.getOrgMembers(membersModal.orgId);
    ui.showToast('success', '成员已移除');
  } catch (err) {
    ui.showToast('error', err.message || '移除失败');
  }
}
</script>

<style scoped>
@import '../css/views/org-view.css';
</style>
