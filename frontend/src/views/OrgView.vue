<template>
  <div class="org-view">
    <div class="kb-panel">
      <div class="org-panel">
        <!-- 头部 -->
        <div class="org-header">
          <h3 class="org-section-title">
            组织管理
            <svg v-if="org.orgLoading" class="inline-spinner" viewBox="0 0 24 24" fill="none" width="14" height="14">
              <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
            </svg>
          </h3>
          <button class="org-create-btn" type="button" @click="handleCreateOrg">+ 创建企业组织</button>
        </div>
        <div class="org-desc">
          组织是多租户的基本单位。个人用户自动拥有「个人空间」，企业可创建组织邀请员工共享知识库。<br>
          <strong>点击组织可切换，知识库页将自动显示该组织的知识库。</strong>
        </div>

        <!-- 组织列表 -->
        <div class="org-list">
          <div v-if="org.orgLoading && !org.organizations.length" class="org-empty-hint">
            <svg class="inline-spinner" viewBox="0 0 24 24" fill="none" width="16" height="16">
              <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
            </svg>
            加载中…
          </div>
          <div v-else-if="!org.orgLoading && !org.organizations.length" class="org-empty-hint">暂无组织</div>
          <div
            v-for="item in org.organizations"
            :key="item.orgId"
            class="org-item"
            :class="{ active: item.orgId === org.currentOrgId }"
            @click="handleSelectOrg(item.orgId)"
          >
            <div class="org-item-icon">{{ item.orgType === 'PERSONAL' ? '个人' : '企业' }}</div>
            <div class="org-item-info">
              <div class="org-item-name">{{ item.orgType === 'PERSONAL' ? '个人空间' : (item.name || item.orgId) }}</div>
              <div class="org-item-meta">
                {{ orgRoleLabel(item.role) }}
                <span v-if="item.orgId === org.currentOrgId" class="org-item-kb-count">· 当前</span>
              </div>
            </div>
            <span v-if="item.orgId === org.currentOrgId" class="org-item-active-badge">当前</span>
            <div v-if="item.orgType === 'ENTERPRISE'" class="org-item-actions" @click.stop>
              <button v-if="item.role === 'OWNER'" class="org-item-action-btn" type="button" title="编辑组织" @click.stop="handleEditOrg(item)">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none">
                  <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                  <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
              </button>
              <button v-if="item.role !== 'OWNER'" class="org-item-action-btn danger" type="button" title="退出组织" @click.stop="handleLeaveOrg(item)">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none">
                  <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
              </button>
              <button v-if="item.role === 'OWNER'" class="org-item-action-btn danger" type="button" title="删除组织" @click.stop="handleDeleteOrg(item)">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/>
                </svg>
              </button>
            </div>
          </div>
        </div>

        <!-- 当前组织操作区 -->
        <div v-if="org.currentOrgId" class="org-actions">
          <h4 class="org-section-title">组织操作</h4>
          <template v-if="org.currentOrg?.orgType === 'ENTERPRISE'">
            <div class="org-invite-wrap">
              <div class="kb-member-search-wrap">
                <input
                  v-model.trim="inviteUsername"
                  type="text"
                  placeholder="输入用户名搜索..."
                  class="kb-member-input"
                  autocomplete="off"
                  @input="searchInviteUsers"
                  @blur="hideInviteSugg"
                  @focus="searchInviteUsers"
                />
                <div v-if="inviteSuggestions.length && inviteSuggVisible" class="kb-member-suggestions">
                  <button
                    v-for="u in inviteSuggestions"
                    :key="u.userId"
                    class="kb-member-suggestion-item"
                    type="button"
                    @mousedown.prevent="selectInviteSugg(u)"
                  >
                    <span class="kb-member-sug-name">{{ u.username }}</span>
                    <span class="kb-member-sug-id">{{ u.userId }}</span>
                  </button>
                </div>
              </div>
              <select v-model="inviteRole" class="kb-member-role-select">
                <option value="MEMBER">成员</option>
                <option value="ADMIN">管理员</option>
              </select>
              <button class="kb-member-add-btn" type="button" @click="doInvite">邀请</button>
            </div>
            <button class="org-action-btn" type="button" @click="showMembers">查看成员</button>
          </template>
          <template v-else>
            <p class="org-personal-hint">
              个人空间为私有隔离空间，不支持邀请成员。<br>如需多人协作，请创建企业组织。
            </p>
          </template>
        </div>
      </div>
    </div>

    <!-- 成员弹窗 -->
    <div v-if="membersModal.visible" class="modal-overlay" @click.self="membersModal.visible = false">
      <div class="modal-content">
        <div class="modal-header">
          <h3>{{ membersModal.title }}</h3>
          <button class="modal-close" type="button" @click="membersModal.visible = false">×</button>
        </div>
        <div class="modal-body">
          <div v-if="!membersModal.members.length" class="empty-hint">暂无成员</div>
          <div v-for="member in membersModal.members" :key="member.userId" class="member-item">
            <div class="member-info">
              <div class="member-name">{{ member.username || member.userId }}</div>
              <div v-if="member.username" class="member-id">{{ member.userId }}</div>
            </div>
            <template v-if="member.role === 'OWNER'">
              <span class="member-role owner-badge">所有者</span>
            </template>
            <template v-else>
              <select
                class="kb-member-role-inline"
                :value="member.role"
                @change="org.updateMemberRole(org.currentOrgId, member.userId, $event.target.value)"
              >
                <option value="MEMBER">成员</option>
                <option value="ADMIN">管理员</option>
              </select>
            </template>
            <button v-if="member.role !== 'OWNER'" class="member-remove" type="button"
                    @click="removeMemberFromModal(member.userId)">移除</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue';
import { useOrgStore } from '../stores/org.js';
import { useKbStore } from '../stores/kb.js';
import { useUiStore } from '../stores/ui.js';
import * as api from '../services/api.js';

const org = useOrgStore();
const kb  = useKbStore();
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

function orgRoleLabel(role) {
  const map = { OWNER: '所有者', ADMIN: '管理员', MEMBER: '成员' };
  return map[role] || role || '';
}

async function handleSelectOrg(orgId) {
  org.selectOrg(orgId);
  await kb.loadKbs(orgId);
}

// ── CRUD ─────────────────────────────────────────────────────────────
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

// ── 邀请成员 ─────────────────────────────────────────────────────────
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

// ── 成员弹窗 ─────────────────────────────────────────────────────────
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
