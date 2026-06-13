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
              <div class="org-id-row">
                <span class="org-id-label">组织 ID</span>
                <code class="org-id-value">{{ org.currentOrg.orgId }}</code>
                <button class="org-id-copy" type="button" @click="copyOrgId(org.currentOrg.orgId)">复制</button>
              </div>
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

        <template>
          <nav class="org-section-tabs" aria-label="组织管理功能">
            <button
              v-for="tab in visibleTabs"
              :key="tab.key"
              class="org-section-tab"
              :class="{ active: activeSectionKey === tab.key }"
              type="button"
              @click="activeSection = tab.key"
            >
              <span>{{ tab.label }}</span>
              <span v-if="tab.count !== null" class="org-section-tab-count">{{ tab.count }}</span>
            </button>
          </nav>

          <section v-if="activeSectionKey === 'members'" class="org-members-panel">
            <div class="section-heading">
              <h4>成员</h4>
              <span>{{ filteredMembers.length }} 人</span>
            </div>

            <div v-if="org.currentOrg.orgType === 'PERSONAL'" class="org-notice inline">
              个人空间仅自己可见，不支持成员协作。需要协作时请创建企业组织，或在「加入申请」中申请加入其他组织。
            </div>

            <template v-else>
              <div class="member-search-row">
                <input
                  v-model.trim="memberSearch"
                  type="text"
                  placeholder="搜索用户名或用户 ID"
                  class="org-member-input"
                />
              </div>

              <div v-if="membersLoading" class="org-loading members-loading">
                <svg class="inline-spinner" viewBox="0 0 24 24" fill="none" width="18" height="18">
                  <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
                </svg>
                加载成员中
              </div>

              <div v-else-if="!filteredMembers.length" class="org-empty compact">
                <div class="org-empty-title">{{ memberSearch ? '无匹配成员' : '暂无成员' }}</div>
              </div>

              <div v-else class="member-list">
                <div v-for="member in filteredMembers" :key="member.userId" class="member-row">
                  <div class="member-avatar">{{ memberInitial(member) }}</div>
                  <div class="member-main">
                    <div class="member-name">
                      {{ member.username || member.userId }}
                      <span v-if="member.userId === auth.user?.userId" class="self-badge">你</span>
                    </div>
                    <div class="member-id">{{ member.userId }}</div>
                  </div>

                  <span v-if="member.role === 'OWNER'" class="member-role-badge owner">所有者</span>
                  <div v-else-if="isOwner" class="member-role-actions">
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
            </template>
          </section>

          <section v-if="activeSectionKey === 'invite' && canManageMembers" class="org-invite-panel">
            <div class="section-heading">
              <h4>邀请成员</h4>
              <span>输入已注册用户的邮箱或用户名，默认角色为成员</span>
            </div>
            <div class="org-invite-row compact">
              <input
                v-model.trim="inviteEmailOrUsername"
                type="text"
                placeholder="邮箱或用户名"
                class="org-member-input invite-input"
                autocomplete="off"
              />
              <button class="org-primary-btn invite-btn" type="button" :disabled="!inviteEmailOrUsername || inviteSending" @click="doInvite">
                <svg v-if="inviteSending" class="inline-spinner" viewBox="0 0 24 24" fill="none" width="14" height="14">
                  <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
                </svg>
                {{ inviteSending ? '发送中' : '发送邀请' }}
              </button>
            </div>
          </section>

          <section v-if="activeSectionKey === 'invitations' && canManageMembers" class="org-invite-panel">
            <div class="section-heading">
              <h4>待处理邀请</h4>
              <span>{{ pendingInvitations.length }} 条</span>
            </div>
            <div v-if="invitationsLoading" class="org-loading">
              <svg class="inline-spinner" viewBox="0 0 24 24" fill="none" width="18" height="18">
                <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
              </svg>
              加载邀请中
            </div>
            <div v-else-if="!pendingInvitations.length" class="org-empty compact">
              <div class="org-empty-title">暂无待处理邀请</div>
            </div>
            <div v-else class="invitation-list">
              <div v-for="inv in pendingInvitations" :key="inv.id" class="invitation-row">
                <div class="invitation-main">
                  <div class="invitation-email">{{ inv.email }}</div>
                  <div class="invitation-meta">
                    <span v-if="inv.username" class="invitation-user">{{ inv.username }}</span>
                    <span class="invitation-role">{{ orgRoleLabel(inv.role) }}</span>
                    <span class="invitation-expires">{{ formatExpires(inv.expiresAt) }}</span>
                  </div>
                </div>
                <button class="member-remove-btn" type="button" @click="cancelInvitation(inv.id)">撤销</button>
              </div>
            </div>
          </section>

          <section v-if="activeSectionKey === 'joinRequests'" class="org-invite-panel">
            <div class="section-heading">
              <h4>加入申请</h4>
              <span>{{ joinRequestTabCount }} 条待处理</span>
            </div>

            <div class="section-heading sub-heading first">
              <h4>我收到的邀请</h4>
              <span>{{ myInvitations.length }} 条待处理</span>
            </div>
            <div v-if="myInvitationsLoading" class="org-loading">
              <svg class="inline-spinner" viewBox="0 0 24 24" fill="none" width="18" height="18">
                <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
              </svg>
              加载邀请中
            </div>
            <div v-else-if="!myInvitations.length" class="org-empty compact">
              <div class="org-empty-title">暂无收到的组织邀请</div>
            </div>
            <div v-else class="invitation-list">
              <div v-for="inv in myInvitations" :key="inv.id" class="invitation-row">
                <div class="invitation-main">
                  <div class="invitation-email">{{ inv.orgName || inv.orgId }}</div>
                  <div class="invitation-meta">
                    <span class="invitation-role">{{ orgRoleLabel(inv.role) }}</span>
                    <span class="invitation-expires">{{ formatExpires(inv.expiresAt) }}</span>
                  </div>
                </div>
                <div class="join-request-actions">
                  <button class="org-text-btn" type="button" @click="acceptMyInvitation(inv.token)">接受</button>
                  <button class="member-remove-btn" type="button" @click="rejectMyInvitation(inv.token)">拒绝</button>
                </div>
              </div>
            </div>

            <div class="section-heading sub-heading">
              <h4>申请加入组织</h4>
            </div>
            <div class="org-apply-card">
              <div>
                <div class="org-apply-title">加入其他组织</div>
                <p class="org-apply-desc">输入组织 ID，向该组织管理员提交加入申请。</p>
              </div>
              <div class="org-apply-row">
                <input
                  v-model.trim="applyOrgId"
                  type="text"
                  placeholder="输入组织 ID"
                  class="org-member-input"
                />
                <button class="org-primary-btn" type="button" :disabled="!applyOrgId || applySending" @click="doApplyJoin">
                  <svg v-if="applySending" class="inline-spinner" viewBox="0 0 24 24" fill="none" width="14" height="14">
                    <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
                  </svg>
                  {{ applySending ? '申请中' : '申请加入' }}
                </button>
              </div>
            </div>

            <template v-if="canManageMembers">
              <div class="section-heading sub-heading">
                <h4>收到的加入申请</h4>
                <span>{{ pendingJoinRequests.length }} 条待处理</span>
              </div>

              <div v-if="joinRequestsLoading" class="org-loading">
              <svg class="inline-spinner" viewBox="0 0 24 24" fill="none" width="18" height="18">
                <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
              </svg>
              加载申请中
              </div>
              <div v-else-if="!pendingJoinRequests.length" class="org-empty compact">
                <div class="org-empty-title">暂无加入申请</div>
              </div>
              <div v-else class="invitation-list">
                <div v-for="req in pendingJoinRequests" :key="req.id" class="invitation-row">
                  <div class="invitation-main">
                    <div class="invitation-email">{{ req.username || req.userId }}</div>
                    <div v-if="req.message" class="invitation-message">{{ req.message }}</div>
                    <div class="invitation-meta">
                      <span class="invitation-expires">{{ formatTime(req.createdAt) }}</span>
                    </div>
                  </div>
                  <div class="join-request-actions">
                    <button class="org-text-btn" type="button" @click="approveJoinRequest(req.id)">通过</button>
                    <button class="member-remove-btn" type="button" @click="rejectJoinRequest(req.id)">拒绝</button>
                  </div>
                </div>
              </div>
            </template>
          </section>
        </template>
      </template>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '../stores/auth.js';
import { useOrgStore } from '../stores/org.js';
import { useUiStore } from '../stores/ui.js';

const auth = useAuthStore();
const org = useOrgStore();
const ui  = useUiStore();
const route = useRoute();
const router = useRouter();

const inviteEmailOrUsername = ref('');
const inviteSending         = ref(false);
const applyOrgId            = ref('');
const applySending          = ref(false);
const activeSection         = ref('members');
const members               = ref([]);
const membersLoading        = ref(false);
const memberSearch          = ref('');
const pendingInvitations    = ref([]);
const invitationsLoading    = ref(false);
const myInvitations         = ref([]);
const myInvitationsLoading  = ref(false);
const pendingJoinRequests   = ref([]);
const joinRequestsLoading   = ref(false);

const editableRoles = [
  { value: 'MEMBER', label: '成员' },
  { value: 'ADMIN', label: '管理员' },
];

const canManageMembers = computed(() =>
  org.currentOrg?.orgType === 'ENTERPRISE'
  && ['OWNER', 'ADMIN'].includes(org.currentOrg?.role)
);

const isOwner = computed(() =>
  org.currentOrg?.orgType === 'ENTERPRISE' && org.currentOrg?.role === 'OWNER'
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

const filteredMembers = computed(() => {
  const keyword = memberSearch.value.trim().toLowerCase();
  if (!keyword) return members.value;
  return members.value.filter(m =>
    (m.username || '').toLowerCase().includes(keyword) ||
    (m.userId || '').toLowerCase().includes(keyword)
  );
});

const joinRequestTabCount = computed(() =>
  myInvitations.value.length + (canManageMembers.value ? pendingJoinRequests.value.length : 0)
);

const visibleTabs = computed(() => {
  const tabs = [
    { key: 'members', label: '成员', count: filteredMembers.value.length },
  ];
  if (canManageMembers.value) {
    tabs.push(
      { key: 'invite', label: '邀请成员', count: null },
      { key: 'invitations', label: '待处理邀请', count: pendingInvitations.value.length },
    );
  }
  tabs.push({
    key: 'joinRequests',
    label: '加入申请',
    count: joinRequestTabCount.value,
  });
  return tabs;
});

const activeSectionKey = computed(() => {
  const validKeys = visibleTabs.value.map(tab => tab.key);
  return validKeys.includes(activeSection.value) ? activeSection.value : 'members';
});

watch(() => org.currentOrgId, () => {
  activeSection.value = 'members';
  clearInvite();
  loadMembers();
  loadInvitations();
  loadMyInvitations();
  loadJoinRequests();
}, { immediate: true });

onMounted(() => {
  const inviteToken = route.query.inviteToken || route.params.token;
  if (inviteToken) handleAcceptInvite(inviteToken);
});

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

function formatExpires(iso) {
  if (!iso) return '';
  const date = new Date(iso);
  return `有效期至 ${date.toLocaleDateString()} ${date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
}

function formatTime(iso) {
  if (!iso) return '';
  return new Date(iso).toLocaleString();
}

async function copyOrgId(orgId) {
  try {
    await navigator.clipboard.writeText(orgId);
    ui.showToast('success', '组织 ID 已复制');
  } catch {
    ui.showToast('info', orgId);
  }
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

async function doInvite() {
  if (!inviteEmailOrUsername.value.trim()) { ui.showToast('warning', '请输入邮箱或用户名'); return; }
  inviteSending.value = true;
  try {
    await org.inviteMember(org.currentOrgId, inviteEmailOrUsername.value.trim(), 'MEMBER');
    ui.showToast('success', '邀请邮件已发送');
    clearInvite();
    await loadInvitations();
  } catch (err) {
    ui.showToast('error', err.message || '邀请失败');
  } finally {
    inviteSending.value = false;
  }
}

async function doApplyJoin() {
  if (!applyOrgId.value.trim()) { ui.showToast('warning', '请输入组织 ID'); return; }
  applySending.value = true;
  try {
    await org.applyJoin(applyOrgId.value.trim(), null);
    ui.showToast('success', '加入申请已提交');
    applyOrgId.value = '';
  } catch (err) {
    ui.showToast('error', err.message || '申请失败');
  } finally {
    applySending.value = false;
  }
}

async function loadMyInvitations() {
  myInvitationsLoading.value = true;
  try {
    myInvitations.value = await org.listMyInvitations();
  } catch (err) {
    ui.showToast('error', err.message || '加载收到的邀请失败');
    myInvitations.value = [];
  } finally {
    myInvitationsLoading.value = false;
  }
}

async function acceptMyInvitation(token) {
  try {
    await org.acceptInvitation(token);
    myInvitations.value = myInvitations.value.filter(item => item.token !== token);
    ui.showToast('success', '已接受组织邀请');
    await loadMembers();
  } catch (err) {
    ui.showToast('error', err.message || '接受邀请失败');
  }
}

async function rejectMyInvitation(token) {
  try {
    await org.rejectInvitation(token);
    myInvitations.value = myInvitations.value.filter(item => item.token !== token);
    ui.showToast('success', '已拒绝组织邀请');
  } catch (err) {
    ui.showToast('error', err.message || '拒绝邀请失败');
  }
}

async function loadInvitations() {
  if (!org.currentOrgId || org.currentOrg?.orgType !== 'ENTERPRISE' || !canManageMembers.value) {
    pendingInvitations.value = [];
    return;
  }
  invitationsLoading.value = true;
  try {
    pendingInvitations.value = await org.listInvitations(org.currentOrgId);
  } catch (err) {
    ui.showToast('error', err.message || '加载邀请失败');
    pendingInvitations.value = [];
  } finally {
    invitationsLoading.value = false;
  }
}

async function cancelInvitation(invitationId) {
  const confirmed = await ui.showConfirm({
    title: '撤销邀请',
    message: '确认撤销该邀请？',
    confirmText: '撤销',
    variant: 'danger',
  });
  if (!confirmed) return;
  try {
    await org.cancelInvitation(org.currentOrgId, invitationId);
    pendingInvitations.value = pendingInvitations.value.filter(i => i.id !== invitationId);
    ui.showToast('success', '邀请已撤销');
  } catch (err) {
    ui.showToast('error', err.message || '撤销失败');
  }
}

async function loadJoinRequests() {
  if (!org.currentOrgId || org.currentOrg?.orgType !== 'ENTERPRISE' || !canManageMembers.value) {
    pendingJoinRequests.value = [];
    return;
  }
  joinRequestsLoading.value = true;
  try {
    pendingJoinRequests.value = await org.listJoinRequests(org.currentOrgId);
  } catch (err) {
    ui.showToast('error', err.message || '加载加入申请失败');
    pendingJoinRequests.value = [];
  } finally {
    joinRequestsLoading.value = false;
  }
}

async function approveJoinRequest(requestId) {
  try {
    await org.approveJoinRequest(requestId);
    pendingJoinRequests.value = pendingJoinRequests.value.filter(r => r.id !== requestId);
    ui.showToast('success', '已通过加入申请');
    await loadMembers();
  } catch (err) {
    ui.showToast('error', err.message || '操作失败');
  }
}

async function rejectJoinRequest(requestId) {
  try {
    await org.rejectJoinRequest(requestId);
    pendingJoinRequests.value = pendingJoinRequests.value.filter(r => r.id !== requestId);
    ui.showToast('success', '已拒绝加入申请');
  } catch (err) {
    ui.showToast('error', err.message || '操作失败');
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

async function handleAcceptInvite(token) {
  try {
    await org.acceptInvitation(token);
    ui.showToast('success', '已接受组织邀请');
  } catch (err) {
    ui.showToast('error', err.message || '接受邀请失败');
  } finally {
    router.replace('/org');
  }
}

function clearInvite() {
  inviteEmailOrUsername.value = '';
  memberSearch.value = '';
}
</script>

<style scoped>
@import '../css/views/org-view.css';
</style>
