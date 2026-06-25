/**
 * Org Store — 组织状态管理
 */
import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import * as api from '../services/api.js';
import { useUiStore } from './ui.js';


export const useOrgStore = defineStore('org', () => {
  const ui = useUiStore();

  const organizations      = ref([]);
  const currentOrgId       = ref(null);
  const orgLoading         = ref(false);
  // Fix 14: 待处理通知计数（收到的邀请 + 待处理的加入申请状态）
  const pendingNoticeCount = ref(0);

  const currentOrg = computed(() =>
    organizations.value.find(o => o.orgId === currentOrgId.value) || null
  );

  const currentOrgName = computed(() => {
    const org = currentOrg.value;
    if (!org) return '个人空间';
    return org.orgType === 'PERSONAL' ? '个人空间' : (org.name || org.orgId);
  });

  function orgPreferenceKey() {
    const userId = api.getUser()?.userId;
    return userId ? `ai_agent_current_org_${userId}` : null;
  }

  function readStoredOrgId() {
    const key = orgPreferenceKey();
    if (!key) return null;
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  }

  function persistOrgId(orgId) {
    const key = orgPreferenceKey();
    if (!key || !orgId) return;
    try {
      localStorage.setItem(key, orgId);
    } catch {}
  }

  function resolvePreferredOrgId(list) {
    if (!list.length) return null;

    const currentValid = currentOrgId.value && list.find(o => o.orgId === currentOrgId.value);
    if (currentValid) return currentOrgId.value;

    const storedOrgId = readStoredOrgId();
    const storedValid = storedOrgId && list.find(o => o.orgId === storedOrgId);
    if (storedValid) return storedOrgId;

    const personal = list.find(o => o.orgType === 'PERSONAL');
    return personal?.orgId || list[0].orgId;
  }

  // ── 加载 ────────────────────────────────────────────────────────────
  async function loadOrgs() {
    orgLoading.value = true;
    try {
      organizations.value = await api.listOrganizations();
      const preferredOrgId = resolvePreferredOrgId(organizations.value);
      currentOrgId.value = preferredOrgId;
      if (preferredOrgId) {
        persistOrgId(preferredOrgId);
      }
      // Fix 14: 加载完组织后刷新通知计数
      refreshNoticeCount();
    } catch (err) {
      ui.showToast('error', err.message || '加载组织失败');
    } finally {
      orgLoading.value = false;
    }
  }

  // Fix 14: 计算未读通知数（收到的待处理邀请 + 我的待处理申请）
  async function refreshNoticeCount() {
    try {
      const [invites, requests] = await Promise.all([
        api.listMyInvitations().catch(() => []),
        api.listMyJoinRequests().catch(() => []),
      ]);
      pendingNoticeCount.value =
        (invites || []).filter(i => i.status === 'PENDING').length +
        (requests || []).filter(r => r.status === 'PENDING').length;
    } catch {
      // 静默失败，不影响正常流程
    }
  }

  function selectOrg(orgId) {
    currentOrgId.value = orgId;
    persistOrgId(orgId);
  }

  async function createOrg(name, description) {
    const org = await api.createOrganization(name, description);
    ui.showToast('success', `组织「${name}」已创建`);
    await loadOrgs();
    return org;
  }

  async function updateOrg(orgId, name, description) {
    await api.updateOrganization(orgId, name, description);
    ui.showToast('success', '组织信息已更新');
    await loadOrgs();
  }

  async function deleteOrg(orgId) {
    await api.deleteOrganization(orgId);
    ui.showToast('success', '组织已删除');
    await loadOrgs();
  }

  async function leaveOrg(orgId) {
    await api.leaveOrganization(orgId);
    ui.showToast('success', '已退出组织');
    await loadOrgs();
  }

  async function getOrgMembers(orgId) {
    return api.getOrganization(orgId).then(r => r.members || []);
  }

  async function inviteMember(orgId, emailOrUsername, role) {
    return api.inviteOrgMember(orgId, emailOrUsername, role);
  }

  async function listInvitations(orgId) {
    return api.listOrgInvitations(orgId);
  }

  async function cancelInvitation(orgId, invitationId) {
    await api.cancelOrgInvitation(orgId, invitationId);
  }

  async function acceptInvitation(token) {
    await api.acceptOrgInvitation(token);
    await loadOrgs();
  }

  async function rejectInvitation(token) {
    await api.rejectOrgInvitation(token);
  }

  async function listMyInvitations() {
    return api.listMyInvitations();
  }

  async function applyJoin(orgId, message) {
    await api.applyJoinOrg(orgId, message);
  }

  async function listJoinRequests(orgId) {
    return api.listOrgJoinRequests(orgId);
  }

  async function approveJoinRequest(requestId) {
    await api.approveOrgJoinRequest(requestId);
  }

  async function rejectJoinRequest(requestId) {
    await api.rejectOrgJoinRequest(requestId);
  }

  async function listMyJoinRequests() {
    return api.listMyJoinRequests();
  }

  async function removeMember(orgId, userId) {
    await api.removeOrgMember(orgId, userId);
  }

  async function updateMemberRole(orgId, userId, role) {
    await api.updateOrgMemberRole(orgId, userId, role);
  }

  return {
    organizations, currentOrgId, orgLoading, pendingNoticeCount,
    currentOrg, currentOrgName,
    loadOrgs, selectOrg, createOrg, updateOrg, deleteOrg, leaveOrg,
    getOrgMembers, inviteMember, listInvitations, cancelInvitation,
    acceptInvitation, rejectInvitation, listMyInvitations,
    applyJoin, listJoinRequests, approveJoinRequest, rejectJoinRequest, listMyJoinRequests,
    removeMember, updateMemberRole,
    refreshNoticeCount,
  };
});
