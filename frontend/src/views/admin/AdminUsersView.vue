<template>
  <div class="admin-page">
    <section class="admin-panel">
      <div class="admin-panel-header">
        <h2 class="admin-panel-title">用户列表</h2>
        <span class="admin-muted">共 {{ users.total || 0 }} 个用户</span>
      </div>
      <div class="admin-filter-row">
        <input v-model.trim="keyword" class="admin-input" placeholder="搜索用户名" @input="onSearch" />
        <button class="admin-small-btn" type="button" :disabled="loading" @click="loadUsers">刷新</button>
      </div>

      <div v-if="!users.items?.length" class="admin-empty">暂无用户数据</div>
      <div v-else class="admin-table-wrap">
        <table class="admin-table">
          <thead>
            <tr><th>用户名</th><th>昵称</th><th>角色</th><th>状态</th><th>创建时间</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="u in users.items" :key="u.userId">
              <td>{{ u.username }}</td>
              <td>{{ u.nickname || '-' }}</td>
              <td>
                <span v-for="r in u.roles || []" :key="r" class="admin-badge" :class="r === 'ROLE_ADMIN' ? 'info' : ''">
                  {{ r === 'ROLE_ADMIN' ? '管理员' : '用户' }}
                </span>
              </td>
              <td><span class="admin-badge" :class="u.enabled ? 'ok' : 'error'">{{ u.enabled ? '正常' : '禁用' }}</span></td>
              <td>{{ shortTime(u.createdAt) }}</td>
              <td>
                <div class="admin-row-actions">
                  <button class="admin-small-btn" type="button" @click="router.push(`/admin/users/${u.userId}`)">详情</button>
                  <button v-if="u.enabled" class="admin-danger-btn" type="button" @click="disableUser(u.userId)">禁用</button>
                  <button v-else class="admin-small-btn" type="button" @click="enableUser(u.userId)">启用</button>
                  <button v-if="!u.roles?.includes('ROLE_ADMIN')" class="admin-small-btn" type="button" @click="promoteUser(u.userId)">设为管理员</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="admin-pagination">
        <button class="admin-small-btn" :disabled="page === 0" @click="page--; loadUsers()">上一页</button>
        <span class="admin-muted">第 {{ page + 1 }} / {{ users.totalPages || 1 }} 页</span>
        <button class="admin-small-btn" :disabled="page >= (users.totalPages || 1) - 1" @click="page++; loadUsers()">下一页</button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import * as api from '../../services/api.js';
import { useUiStore } from '../../stores/ui.js';
import { shortTime } from './adminFormat.js';

const router = useRouter();
const ui = useUiStore();
const loading = ref(false);
const page = ref(0);
const keyword = ref('');
const users = reactive({ items: [], total: 0, totalPages: 0 });
let searchTimer = null;

onMounted(loadUsers);

async function loadUsers() {
  loading.value = true;
  try {
    Object.assign(users, await api.adminListUsers(page.value, 10, keyword.value));
  } catch (err) {
    ui.showToast('error', err.message || '加载用户失败');
  } finally {
    loading.value = false;
  }
}

function onSearch() {
  clearTimeout(searchTimer);
  page.value = 0;
  searchTimer = setTimeout(loadUsers, 300);
}

async function enableUser(userId) {
  await api.adminEnableUser(userId);
  await loadUsers();
  ui.showToast('success', '已启用用户');
}

async function disableUser(userId) {
  const ok = await ui.showConfirm({ title: '禁用用户', message: '禁用后该用户将无法登录。', confirmText: '禁用', variant: 'danger' });
  if (!ok) return;
  await api.adminDisableUser(userId);
  await loadUsers();
  ui.showToast('success', '已禁用用户');
}

async function promoteUser(userId) {
  const ok = await ui.showConfirm({ title: '提升为管理员', message: '管理员拥有全部操作权限。', confirmText: '确认', variant: 'danger' });
  if (!ok) return;
  await api.adminSetRole(userId, 'ROLE_ADMIN');
  await loadUsers();
  ui.showToast('success', '角色已更新');
}
</script>
