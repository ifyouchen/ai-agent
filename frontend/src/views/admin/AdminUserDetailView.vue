<template>
  <div class="admin-page">
    <section class="admin-panel">
      <div class="admin-panel-header">
        <h2 class="admin-panel-title">{{ user.username || '用户详情' }}</h2>
        <button class="admin-small-btn" type="button" @click="router.back()">返回</button>
      </div>
      <div v-if="loading" class="admin-empty">加载中...</div>
      <div v-else class="admin-detail-grid">
        <div><span>用户 ID</span><strong>{{ user.userId }}</strong></div>
        <div><span>昵称</span><strong>{{ user.nickname || '-' }}</strong></div>
        <div><span>邮箱</span><strong>{{ user.email || '-' }}</strong></div>
        <div><span>状态</span><strong>{{ user.enabled ? '正常' : '禁用' }}</strong></div>
        <div><span>组织数量</span><strong>{{ user.orgCount ?? 0 }}</strong></div>
        <div><span>今日成本</span><strong>${{ fmtCost(user.todayCostUsd) }}</strong></div>
        <div><span>近 7 天调用</span><strong>{{ fmtNum(user.usage7d?.callCount) }}</strong></div>
        <div><span>近 7 天成本</span><strong>${{ fmtCost(user.usage7d?.costUsd) }}</strong></div>
        <div><span>创建时间</span><strong>{{ shortTime(user.createdAt) }}</strong></div>
        <div><span>更新时间</span><strong>{{ shortTime(user.updatedAt) }}</strong></div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import * as api from '../../services/api.js';
import { useUiStore } from '../../stores/ui.js';
import { fmtCost, fmtNum, shortTime } from './adminFormat.js';

const route = useRoute();
const router = useRouter();
const ui = useUiStore();
const loading = ref(false);
const user = reactive({});

onMounted(loadUser);

async function loadUser() {
  loading.value = true;
  try {
    Object.assign(user, await api.adminGetUser(route.params.userId));
  } catch (err) {
    ui.showToast('error', err.message || '加载用户详情失败');
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.admin-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(220px, 1fr));
  gap: 12px;
}

.admin-detail-grid div {
  padding: 12px;
  border: 1px solid #eef0f4;
  border-radius: 8px;
  background: #fafbfc;
}

.admin-detail-grid span,
.admin-detail-grid strong {
  display: block;
}

.admin-detail-grid span {
  color: #8b8f9a;
  font-size: 12px;
}

.admin-detail-grid strong {
  margin-top: 6px;
  color: #202124;
  font-size: 14px;
}
</style>
