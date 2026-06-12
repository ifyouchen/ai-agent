<template>
  <div class="admin-view">
    <div class="kb-panel monitor-panel">

      <!-- 用户管理 -->
      <div class="monitor-section">
        <div class="monitor-section-title">
          <span>用户管理</span>
          <button class="monitor-refresh-btn" type="button" @click="loadUsers" :disabled="loading">
            <svg viewBox="0 0 24 24" fill="none" width="13" height="13" :class="{ spinning: loading }">
              <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              <path d="M21 3v5h-5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              <path d="M8 16H3v5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            刷新
          </button>
        </div>

        <!-- 搜索框 -->
        <div class="admin-search-row">
          <input v-model.trim="searchKeyword" type="text" class="admin-search-input"
                 placeholder="搜索用户名..." @input="onSearchInput" />
          <span class="admin-total">共 {{ users.total ?? 0 }} 个用户</span>
        </div>

        <div v-if="!users.items?.length" class="empty-hint">暂无用户数据</div>
        <table v-else class="monitor-table">
          <thead>
            <tr>
              <th>用户名</th>
              <th>用户 ID</th>
              <th>昵称</th>
              <th>角色</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="u in users.items" :key="u.userId">
              <td>{{ u.username }}</td>
              <td class="user-id-cell">{{ u.userId }}</td>
              <td>{{ u.nickname || '—' }}</td>
              <td>
                <span v-for="r in (u.roles || [])" :key="r"
                      class="role-badge" :class="r === 'ROLE_ADMIN' ? 'role-admin' : 'role-user'">
                  {{ r === 'ROLE_ADMIN' ? '管理员' : '用户' }}
                </span>
              </td>
              <td>
                <span :class="u.enabled ? 'status-enabled' : 'status-disabled'">
                  {{ u.enabled ? '正常' : '禁用' }}
                </span>
              </td>
              <td>
                <div class="admin-actions">
                  <button v-if="u.enabled" class="admin-user-btn danger" type="button"
                          @click="disableUser(u.userId)">禁用</button>
                  <button v-else class="admin-user-btn" type="button"
                          @click="enableUser(u.userId)">启用</button>
                  <button v-if="!u.roles?.includes('ROLE_ADMIN')" class="admin-user-btn" type="button"
                          title="提升为管理员" @click="promoteUser(u.userId)">设为管理员</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>

        <!-- 分页 -->
        <div v-if="users.totalPages > 1" class="monitor-pagination">
          <button :disabled="page === 0" class="monitor-page-btn" type="button"
                  @click="page--; loadUsers()">上一页</button>
          <span>第 {{ page + 1 }} / {{ users.totalPages }} 页</span>
          <button :disabled="page >= users.totalPages - 1" class="monitor-page-btn" type="button"
                  @click="page++; loadUsers()">下一页</button>
        </div>
      </div>

    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue';
import * as api from '../services/api.js';
import { useUiStore } from '../stores/ui.js';

const ui = useUiStore();
const loading        = ref(false);
const page           = ref(0);
const searchKeyword  = ref('');
const users          = reactive({ items: [], total: 0, totalPages: 0 });
let _searchTimer     = null;

onMounted(() => loadUsers());

async function loadUsers() {
  loading.value = true;
  try {
    const res = await api.adminListUsers(page.value, 20, searchKeyword.value);
    Object.assign(users, res);
  } catch (err) {
    ui.showToast('error', err.message || '加载用户失败');
  } finally {
    loading.value = false;
  }
}

function onSearchInput() {
  clearTimeout(_searchTimer);
  page.value = 0;
  _searchTimer = setTimeout(loadUsers, 300);
}

async function enableUser(userId) {
  try {
    await api.adminEnableUser(userId);
    await loadUsers();
    ui.showToast('success', '已启用用户');
  } catch (err) { ui.showToast('error', err.message || '操作失败'); }
}

async function disableUser(userId) {
  const ok = await ui.showConfirm({
    title: '禁用用户',
    message: `确认禁用此用户？禁用后该用户将无法登录。`,
    confirmText: '禁用',
    variant: 'danger',
  });
  if (!ok) return;
  try {
    await api.adminDisableUser(userId);
    await loadUsers();
    ui.showToast('success', '已禁用用户');
  } catch (err) { ui.showToast('error', err.message || '操作失败'); }
}

async function promoteUser(userId) {
  const ok = await ui.showConfirm({
    title: '提升为管理员',
    message: `确认将此用户提升为管理员？管理员拥有全部操作权限。`,
    confirmText: '确认',
    variant: 'danger',
  });
  if (!ok) return;
  try {
    await api.adminSetRole(userId, 'ROLE_ADMIN');
    await loadUsers();
    ui.showToast('success', '角色已更新');
  } catch (err) { ui.showToast('error', err.message || '操作失败'); }
}
</script>

<style scoped>
.admin-view { height: 100%; overflow-y: auto; }
.admin-search-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.admin-search-input {
  flex: 1;
  max-width: 300px;
  padding: 7px 12px;
  border: 1.5px solid #EBEBEB;
  border-radius: 8px;
  font-size: 13px;
  outline: none;
  transition: border-color .2s;
}
.admin-search-input:focus { border-color: var(--primary, #4D6BFE); }
.admin-total { font-size: 12px; color: #999; }
.admin-actions { display: flex; gap: 6px; }
.role-badge {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
}
.role-admin { background: #EEF1FF; color: #4D6BFE; }
.role-user  { background: #F5F5F5; color: #666; }
</style>
