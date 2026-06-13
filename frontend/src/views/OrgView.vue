<template>
  <div class="org-view">
    <aside class="org-sidebar-panel">
      <div class="org-sidebar-header">
        <div>
          <h2 class="org-title">组织管理</h2>
          <p class="org-subtitle">切换空间、邀请成员和维护权限</p>
        </div>
        <button class="org-icon-btn primary" type="button" title="创建企业组织" @click="handleCreateOrg">
          <svg viewBox="0 0 24 24" fill="none" width="16" height="16">
            <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
        </button>
      </div>

      <div class="org-list">
        <div v-if="org.orgLoading && !org.organizations.length" class="org-loading">
          <svg class="inline-spinner" viewBox="0 0 24 24" fill="none" width="18" height="18">
            <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
          </svg>
          加载组织中
        </div>

        <div v-else-if="!org.orgLoading && !org.organizations.length" class="org-empty">
          <div class="org-empty-title">暂无组织</div>
          <button class="org-create-inline" type="button" @click="handleCreateOrg">创建企业组织</button>
        </div>

        <button
          v-for="item in org.organizations"
          :key="item.orgId"
          class="org-list-item"
          :class="{ active: item.orgId === org.currentOrgId }"
          type="button"
          @click="handleSelectOrg(item.orgId)"
        >
          <span class="org-list-icon" :class="item.orgType === 'PERSONAL' ? 'personal' : 'enterprise'">
            <svg v-if="item.orgType === 'PERSONAL'" viewBox="0 0 24 24" fill="none" width="18" height="18">
              <circle cx="12" cy="8" r="4" stroke="currentColor" stroke-width="2"/>
              <path d="M4 21c0-4 3.6-7 8-7s8 3 8 7" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            </svg>
            <svg v-else viewBox="0 0 24 24" fill="none" width="18" height="18">
              <path d="M3 21h18M5 21V7l7-4 7 4v14M9 21v-8h6v8" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </span>
          <span class="org-list-main">
            <span class="org-list-name">{{ displayOrgName(item) }}</span>
            <span class="org-list-meta">{{ orgTypeLabel(item.orgType) }} · {{ orgRoleLabel(item.role) }}</span>
          </span>
          <span v-if="item.orgId === org.currentOrgId" class="org-current-dot"></span>
        </button>
      </div>
    </aside>

    <main class="org-detail-panel">
      <div v-if="!org.currentOrg" class="org-detail-empty">
        <div class="org-empty-title">请选择组织</div>
        <p>从左侧选择一个组织查看详情和成员。</p>
      </div>

      <template v-else>
        <section class="org-detail-header">
          <div class="org-detail-heading">
            <div class="org-detail-icon" :class="org.currentOrg.orgType === 'PERSONAL' ? 'personal' : 'enterprise'">
              <svg v-if="org.currentOrg.orgType === 'PERSONAL'" viewBox="0 0 24 24" fill="none" width="22" height="22">
                <circle cx="12" cy="8" r="4" stroke="currentColor" stroke-width="2"/>
                <path d="M4 21c0-4 3.6-7 8-7s8 3 8 7" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              </svg>
              <svg v-else viewBox="0 0 24 24" fill="none" width="22" height="22">
                <path d="M3 21h18M5 21V7l7-4 7 4v14M9 21v-8h6v8" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
            <div>
              <h3>{{ displayOrgName(org.currentOrg) }}</h3>
              <p>{{ orgTypeLabel(org.currentOrg.orgType) }} · 我的角色：{{ orgRoleLabel(org.currentOrg.role) }}</p>
            </div>
          </div>
          <div class="org-detail-actions">
            <button v-if="canEditOrg" class="org-text-btn" type="button" @click="handleEditOrg(org.currentOrg)">
              编辑
            </button>
            <button v-if="canLeaveOrg" class="org-text-btn danger" type="button" @click="handleLeaveOrg(org.currentOrg)">
              退出
            </button>
            <button v-if="canDeleteOrg" class="org-text-btn danger" type="button" @click="handleDeleteOrg(org.currentOrg)">
              删除
            </button>
          </div>
        </section>

        <section v-if="org.currentOrg.orgType === 'PERSONAL'" class="org-notice">
          个人空间仅自己可见，不支持邀请成员。需要协作时请创建企业组织。
        </section>

        <template v-else>
          <section v-if="canManageMembers" class="org-invite-panel">
            <div class="section-heading">
              <h4>邀请成员</h4>
              <span>搜索用户后选择角色加入当前组织</span>
            </div>
            <div class="org-invite-row">
              <div class="org-member-search-wrap">
                <input
                  v-model.trim="inviteUsername"
                  type="text"
                  placeholder="输入用户名搜索"
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
              <div class="role-segmented" aria-label="邀请角色">
                <button
                  v-for="role in editableRoles"
                  :key="role.value"
                  type="button"
                  :class="{ active: inviteRole === role.value }"
                  @click="inviteRole = role.value"
                >
                  {{ role.label }}
                </button>
              </div>
              <button class="org-primary-btn" type="button" :disabled="!inviteUserId" @click="doInvite">
                邀请
              </button>
            </div>
          </section>

          <section class="org-members-panel">
            <div class="section-heading">
              <h4>成员</h4>
              <span>{{ members.length }} 人</span>
            </div>

            <div v-if="membersLoading" class="org-loading members-loading">
              <svg class="inline-spinner" viewBox="0 0 24 24" fill="none" width="18" height="18">
                <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
              </svg>
              加载成员中
            </div>

            <div v-else-if="!members.length" class="org-empty compact">
              <div class="org-empty-title">暂无成员</div>
            </div>

            <div v-else class="member-list">
              <div v-for="member in members" :key="member.userId" class="member-row">
                <div class="member-avatar">{{ memberInitial(member) }}</div>
                <div class="member-main">
                  <div class="member-name">
                    {{ member.username || member.userId }}
                    <span v-if="member.userId === auth.user?.userId" class="self-badge">你</span>
                  </div>
                  <div class="member-id">{{ member.userId }}</div>
                </div>

                <span v-if="member.role === 'OWNER'" class="member-role-badge owner">所有者</span>
                <div v-else-if="canManageMembers" class="member-role-actions">
                  <button
                    v-for="role in editableRoles"
                    :key="role.value"
                    class="member-role-chip"
                    :class="{ active: member.role === role.value }"
                    type="button"
                    @click="updateMemberRoleInline(member, role.value)"
                  >
                    {{ role.label }}
                  </button>
                </div>
                <span v-else class="member-role-badge">{{ orgRoleLabel(member.role) }}</span>

                <button
                  v-if="canManageMembers && member.role !== 'OWNER'"
                  class="member-remove-btn"
                  type="button"
                  @click="removeMemberInline(member)"
                >
                  移除
                </button>
              </div>
            </div>
          </section>
        </template>
      </template>
    </main>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue';
import { useAuthStore } from '../stores/auth.js';
import { useOrgStore } from '../stores/org.js';
import { useUiStore } from '../stores/ui.js';
import * as api from '../services/api.js';

const auth = useAuthStore();
const org = useOrgStore();
const ui  = useUiStore();

const inviteUsername    = ref('');
const inviteUserId      = ref('');
const inviteRole        = ref('MEMBER');
const inviteSuggestions = ref([]);
const inviteSuggVisible = ref(false);
const members           = ref([]);
const membersLoading    = ref(false);

const editableRoles = [
  { value: 'MEMBER', label: '成员' },
  { value: 'ADMIN', label: '管理员' },
];

const canManageMembers = computed(() =>
  org.currentOrg?.orgType === 'ENTERPRISE'
  && ['OWNER', 'ADMIN'].includes(org.currentOrg?.role)
);

const canEditOrg = computed(() =>
  org.currentOrg?.orgType === 'ENTERPRISE' && org.currentOrg?.role === 'OWNER'
);

const canDeleteOrg = computed(() =>
  org.currentOrg?.orgType === 'ENTERPRISE' && org.currentOrg?.role === 'OWNER'
);

const canLeaveOrg = computed(() =>
  org.currentOrg?.orgType === 'ENTERPRISE' && org.currentOrg?.role !== 'OWNER'
);

watch(() => org.currentOrgId, () => {
  clearInvite();
  loadMembers();
}, { immediate: true });

function displayOrgName(item) {
  if (!item) return '';
  return item.orgType === 'PERSONAL' ? '个人空间' : (item.name || item.orgId);
}

function orgTypeLabel(type) {
  return type === 'PERSONAL' ? '个人空间' : '企业组织';
}

function orgRoleLabel(role) {
  const map = { OWNER: '所有者', ADMIN: '管理员', MEMBER: '成员' };
  return map[role] || role || '';
}

function memberInitial(member) {
  return String(member.username || member.userId || 'U').slice(0, 1).toUpperCase();
}

async function handleSelectOrg(orgId) {
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

function hideInviteSugg() {
  setTimeout(() => { inviteSuggVisible.value = false; }, 150);
}

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
    clearInvite();
    await loadMembers();
  } catch (err) {
    ui.showToast('error', err.message || '邀请失败');
  }
}

async function loadMembers() {
  if (!org.currentOrgId || org.currentOrg?.orgType !== 'ENTERPRISE') {
    members.value = [];
    return;
  }
  membersLoading.value = true;
  try {
    members.value = await org.getOrgMembers(org.currentOrgId);
  } catch (err) {
    ui.showToast('error', err.message || '加载成员失败');
    members.value = [];
  } finally {
    membersLoading.value = false;
  }
}

async function updateMemberRoleInline(member, role) {
  if (member.role === role) return;
  try {
    await org.updateMemberRole(org.currentOrgId, member.userId, role);
    member.role = role;
    ui.showToast('success', '成员角色已更新');
  } catch (err) {
    ui.showToast('error', err.message || '角色更新失败');
  }
}

async function removeMemberInline(member) {
  const confirmed = await ui.showConfirm({
    title: '移除成员',
    message: `确认移除「${member.username || member.userId}」？`,
    confirmText: '移除',
    variant: 'danger',
  });
  if (!confirmed) return;
  try {
    await org.removeMember(org.currentOrgId, member.userId);
    members.value = members.value.filter(item => item.userId !== member.userId);
    ui.showToast('success', '成员已移除');
  } catch (err) {
    ui.showToast('error', err.message || '移除失败');
  }
}

function clearInvite() {
  inviteUsername.value = '';
  inviteUserId.value = '';
  inviteRole.value = 'MEMBER';
  inviteSuggestions.value = [];
  inviteSuggVisible.value = false;
}
</script>

<style scoped>
@import '../css/views/org-view.css';
</style>
