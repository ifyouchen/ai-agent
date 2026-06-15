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
              <details class="org-id-details">
                <summary class="org-id-summary">组织 ID</summary>
                <div class="org-id-row">
                  <code class="org-id-value">{{ org.currentOrg.orgId }}</code>
                  <button class="org-id-copy" type="button" @click="copyOrgId(org.currentOrg.orgId)">复制</button>
                </div>
              </details>
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

        <div class="org-management-body">
          <nav class="org-section-tabs" aria-label="组织管理功能">
            <button
              class="org-section-tab"
              :class="{ active: activeSectionKey === 'members' }"
              type="button"
              @click="activeSection = 'members'"
            >
              <span>成员</span>
              <span class="org-section-tab-count">{{ filteredMembers.length }}</span>
            </button>
            <button
              v-if="canManageMembers"
              class="org-section-tab"
              :class="{ active: activeSectionKey === 'invite' }"
              type="button"
              @click="activeSection = 'invite'"
            >
              <span>邀请成员</span>
            </button>
            <button
              v-if="canManageMembers"
              class="org-section-tab"
              :class="{ active: activeSectionKey === 'invitations' }"
              type="button"
              @click="activeSection = 'invitations'"
            >
              <span>待处理邀请</span>
              <span class="org-section-tab-count">{{ pendingInvitations.length }}</span>
            </button>
            <button
              v-if="canManageMembers"
              class="org-section-tab"
              :class="{ active: activeSectionKey === 'orgJoinRequests' }"
              type="button"
              @click="activeSection = 'orgJoinRequests'"
            >
              <span>加入审批</span>
              <span class="org-section-tab-count">{{ pendingJoinRequests.length }}</span>
            </button>
            <button
              v-if="isPersonalOrg"
              class="org-section-tab"
              :class="{ active: activeSectionKey === 'myJoinRequests' }"
              type="button"
              @click="activeSection = 'myJoinRequests'"
            >
              <span>我的申请</span>
              <span class="org-section-tab-count">{{ pendingMyJoinRequests.length }}</span>
            </button>
            <button
              v-if="isPersonalOrg"
              class="org-section-tab"
              :class="{ active: activeSectionKey === 'myInvitations' }"
              type="button"
              @click="activeSection = 'myInvitations'"
            >
              <span>收到的邀请</span>
              <span class="org-section-tab-count">{{ myInvitations.length }}</span>
            </button>
          </nav>

          <section v-if="activeSectionKey === 'members'" class="org-members-panel">
            <div class="section-heading">
              <h4>成员</h4>
              <span>{{ filteredMembers.length }} 人</span>
            </div>

            <div v-if="org.currentOrg.orgType === 'PERSONAL'" class="org-notice inline">
              个人空间仅自己可见，不支持成员协作。需要协作时请创建企业组织，或在下方申请加入已有组织。
            </div>
            <!-- Fix 10：个人空间直接展示申请加入表单，无需切 Tab -->
            <div v-if="org.currentOrg.orgType === 'PERSONAL'" class="org-join-shortcut">
              <div class="org-join-shortcut-title">申请加入组织</div>
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
              <div v-if="pendingMyJoinRequests.length" class="org-join-pending-hint">
                有 {{ pendingMyJoinRequests.length }} 个待处理申请
                <span class="org-text-link" @click="activeSection = 'myJoinRequests'">查看详情 →</span>
              </div>
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
                      :disabled="member.role === role.value"
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
            <!-- Fix 9: 已注册用户快速搜索结果 -->
            <div v-if="inviteSearchResults.length" class="invite-search-results">
              <div class="invite-search-hint">以下用户已注册，可直接添加（无需邮件邀请）</div>
              <div v-for="u in inviteSearchResults" :key="u.userId" class="invite-search-item">
                <span class="invite-search-avatar">{{ (u.username || 'U').slice(0,1).toUpperCase() }}</span>
                <span class="invite-search-name">{{ u.username }}</span>
                <span class="invite-search-uid">{{ u.userId?.slice(0, 8) }}…</span>
                <button class="org-text-btn primary sm" type="button" :disabled="inviteSending" @click="quickAddMember(u)">
                  直接添加
                </button>
              </div>
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

          <section v-if="activeSectionKey === 'orgJoinRequests' && canManageMembers" class="org-invite-panel">
            <div class="section-heading">
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
          </section>

          <section v-if="activeSectionKey === 'myJoinRequests' && isPersonalOrg" class="org-invite-panel">
            <div class="section-heading">
              <h4>我发出的申请</h4>
              <span>{{ myJoinRequests.length }} 条记录</span>
            </div>

            <div v-if="myJoinRequestsLoading" class="org-loading">
              <svg class="inline-spinner" viewBox="0 0 24 24" fill="none" width="18" height="18">
                <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
              </svg>
              加载申请记录中
            </div>
            <div v-else-if="!myJoinRequests.length" class="org-empty compact">
              <div class="org-empty-title">暂无申请记录</div>
            </div>
            <div v-else class="invitation-list">
              <div v-for="req in myJoinRequests" :key="req.id" class="invitation-row">
                <div class="invitation-main">
                  <div class="invitation-email">{{ req.orgName || req.orgId }}</div>
                  <div v-if="req.message" class="invitation-message">{{ req.message }}</div>
                  <div class="invitation-meta">
                    <span class="invitation-role">{{ req.orgId }}</span>
                    <span class="invitation-expires">{{ formatTime(req.createdAt) }}</span>
                  </div>
                </div>
                <span class="request-status" :class="requestStatusClass(req.status)">
                  {{ requestStatusLabel(req.status) }}
                </span>
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
          </section>

          <section v-if="activeSectionKey === 'myInvitations' && isPersonalOrg" class="org-invite-panel">
            <div class="section-heading">
              <h4>收到的邀请</h4>
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
          </section>
        </div>
      </template>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { copyText } from '../js/utils.js';
import { useAuthStore } from '../stores/auth.js';
import { useOrgStore } from '../stores/org.js';
import { useUiStore } from '../stores/ui.js';
import * as api from '../services/api.js';

const auth = useAuthStore();
const org = useOrgStore();
const ui  = useUiStore();
const route = useRoute();
const router = useRouter();

const inviteEmailOrUsername = ref('');
const inviteSending         = ref(false);
const inviteSearchResults   = ref([]);   // 快速搜索已注册用户结果
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
const myJoinRequests        = ref([]);
const myJoinRequestsLoading = ref(false);
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

const isPersonalOrg = computed(() =>
  org.currentOrg?.orgType === 'PERSONAL'
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

const pendingMyJoinRequests = computed(() =>
  myJoinRequests.value.filter(item => item.status === 'PENDING')
);

const activeSectionKey = computed(() => {
  let validKeys = ['members'];
  if (canManageMembers.value) {
    validKeys = ['members', 'invite', 'invitations', 'orgJoinRequests'];
  } else if (isPersonalOrg.value) {
    validKeys = ['members', 'myJoinRequests', 'myInvitations'];
  }
  return validKeys.includes(activeSection.value) ? activeSection.value : 'members';
});

watch(() => org.currentOrgId, () => {
  activeSection.value = 'members';
  clearInvite();
  loadMembers();
  loadInvitations();
  loadMyInvitations();
  loadMyJoinRequests();
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

function requestStatusLabel(status) {
  const map = { PENDING: '待处理', APPROVED: '已通过', REJECTED: '已拒绝' };
  return map[status] || status || '';
}

function requestStatusClass(status) {
  return {
    pending: status === 'PENDING',
    approved: status === 'APPROVED',
    rejected: status === 'REJECTED',
  };
}

async function copyOrgId(orgId) {
  const copied = await copyText(orgId);
  ui.showToast(copied ? 'success' : 'warning', copied ? '组织 ID 已复制' : '复制失败，请手动复制');
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

// Fix 9：实时搜索已注册用户，支持直接添加（无需发邮件邀请）
let _inviteSearchTimer = null;
watch(inviteEmailOrUsername, (val) => {
  clearTimeout(_inviteSearchTimer);
  if (!val || val.length < 2 || !canManageMembers.value) {
    inviteSearchResults.value = [];
    return;
  }
  _inviteSearchTimer = setTimeout(async () => {
    try {
      const res = await api.searchUsers(val);
      inviteSearchResults.value = (res || [])
        .filter(u => !members.value.find(m => m.userId === u.userId))
        .slice(0, 5);
    } catch { inviteSearchResults.value = []; }
  }, 300);
});

async function quickAddMember(user) {
  inviteSending.value = true;
  try {
    await org.inviteMember(org.currentOrgId, user.username, 'MEMBER');
    ui.showToast('success', `「${user.username}」已加入组织`);
    await loadMembers();
    inviteEmailOrUsername.value = '';
    inviteSearchResults.value = [];
    await loadInvitations();
  } catch (err) {
    ui.showToast('error', err.message || '添加失败');
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
    await loadMyJoinRequests();
  } catch (err) {
    ui.showToast('error', err.message || '申请失败');
  } finally {
    applySending.value = false;
  }
}

async function loadMyInvitations() {
  if (!isPersonalOrg.value) {
    myInvitations.value = [];
    return;
  }
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

async function loadMyJoinRequests() {
  if (!isPersonalOrg.value) {
    myJoinRequests.value = [];
    return;
  }
  myJoinRequestsLoading.value = true;
  try {
    myJoinRequests.value = await org.listMyJoinRequests();
  } catch (err) {
    ui.showToast('error', err.message || '加载申请记录失败');
    myJoinRequests.value = [];
  } finally {
    myJoinRequestsLoading.value = false;
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
  const memberName = member.username || member.userId;
  const confirmed = await ui.showConfirm({
    title: '确认调整成员角色',
    message: `确认将「${memberName}」从「${orgRoleLabel(member.role)}」调整为「${orgRoleLabel(role)}」？角色变更会立即影响该成员在组织内的管理权限。`,
    confirmText: '确认调整',
  });
  if (!confirmed) return;
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
  inviteSearchResults.value = [];
  memberSearch.value = '';
}
</script>

<style scoped>
@import '../css/views/org-view.css';
</style>
