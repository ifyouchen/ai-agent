<template>
  <div ref="rootEl" class="workspace-switcher">
    <button
      class="workspace-trigger"
      type="button"
      :class="{ active: open }"
      title="切换当前工作空间"
      @click="toggleOpen"
    >
      <span class="workspace-mark">{{ workspaceInitial }}</span>
      <span class="workspace-copy">
        <small>当前工作空间</small>
        <strong>{{ org.currentOrgName }}</strong>
      </span>
      <svg viewBox="0 0 24 24" fill="none" width="14" height="14">
        <path d="m6 9 6 6 6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
      </svg>
    </button>

    <div v-if="open" class="workspace-menu">
      <div class="workspace-menu-head">
        <span>工作空间</span>
        <router-link to="/org" @click="open = false">组织设置</router-link>
      </div>
      <div v-if="org.orgLoading" class="workspace-empty">正在加载组织…</div>
      <div v-else-if="!org.organizations.length" class="workspace-empty">
        暂无工作空间，请先创建或加入组织
      </div>
      <template v-else>
        <button
          v-for="item in org.organizations"
          :key="item.orgId"
          class="workspace-option"
          :class="{ selected: item.orgId === org.currentOrgId }"
          type="button"
          @click="selectWorkspace(item)"
        >
          <span class="workspace-option-icon">{{ optionInitial(item) }}</span>
          <span class="workspace-option-main">
            <strong>{{ displayName(item) }}</strong>
            <small>{{ orgTypeLabel(item.orgType) }} · {{ orgRoleLabel(item.role) }}</small>
          </span>
          <span v-if="item.orgId === org.currentOrgId" class="workspace-current">当前</span>
        </button>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useKbStore } from '../../stores/kb.js';
import { useOrgStore } from '../../stores/org.js';
import { useSessionStore } from '../../stores/sessions.js';
import { useUiStore } from '../../stores/ui.js';

const org = useOrgStore();
const kb = useKbStore();
const sess = useSessionStore();
const ui = useUiStore();

const rootEl = ref(null);
const open = ref(false);

const workspaceInitial = computed(() => org.currentOrgName.slice(0, 1).toUpperCase() || '工');

onMounted(() => document.addEventListener('click', handleOutsideClick));
onBeforeUnmount(() => document.removeEventListener('click', handleOutsideClick));

function toggleOpen() {
  open.value = !open.value;
}

function handleOutsideClick(event) {
  if (!open.value || rootEl.value?.contains(event.target)) return;
  open.value = false;
}

async function selectWorkspace(item) {
  if (!item?.orgId || item.orgId === org.currentOrgId) {
    open.value = false;
    return;
  }
  const shouldClearKb = Boolean(sess.currentKbId && sess.currentKbOrgId !== item.orgId);
  org.selectOrg(item.orgId);
  kb.resetSelection();
  if (shouldClearKb) {
    sess.clearCurrentKb();
    ui.showToast('info', '已切换工作空间，请重新选择知识库');
  }
  open.value = false;
}

function displayName(item) {
  return item.orgType === 'PERSONAL' ? '个人空间' : (item.name || item.orgId);
}

function optionInitial(item) {
  return displayName(item).slice(0, 1).toUpperCase();
}

function orgTypeLabel(type) {
  return type === 'PERSONAL' ? '私人空间' : '协作空间';
}

function orgRoleLabel(role) {
  return { OWNER: '拥有者', ADMIN: '管理员', MEMBER: '成员' }[role] || role || '成员';
}
</script>

<style scoped>
@import '../../css/components/workspace-switcher.css';
</style>
