<template>
  <div class="admin-page">
    <section class="admin-panel">
      <div class="admin-panel-header">
        <h2 class="admin-panel-title">知识库资产</h2>
        <span class="admin-muted">共 {{ data.total || 0 }} 个知识库</span>
      </div>
      <div class="admin-filter-row">
        <input v-model.trim="filters.keyword" class="admin-input" placeholder="搜索知识库名称" @input="onSearch" />
        <select v-model="filters.status" class="admin-select" @change="reload">
          <option value="">全部状态</option>
          <option value="1">正常</option>
          <option value="0">归档</option>
        </select>
        <button class="admin-small-btn" type="button" :disabled="loading" @click="loadKbs">刷新</button>
      </div>

      <div v-if="!data.items?.length" class="admin-empty">暂无知识库数据</div>
      <div v-else class="admin-table-wrap">
        <table class="admin-table">
          <thead><tr><th>名称</th><th>组织</th><th>创建者</th><th>文档数</th><th>状态</th><th>更新时间</th></tr></thead>
          <tbody>
            <tr v-for="kb in data.items" :key="kb.id">
              <td>{{ kb.name }}</td>
              <td>{{ kb.orgName || kb.tenantId }}</td>
              <td>{{ kb.creatorUsername || kb.createdBy || '-' }}</td>
              <td>{{ fmtNum(kb.docCount) }}</td>
              <td><span class="admin-badge" :class="Number(kb.status) === 1 ? 'ok' : 'warn'">{{ Number(kb.status) === 1 ? '正常' : '归档' }}</span></td>
              <td>{{ shortTime(kb.updatedAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <Pager :page="page" :total-pages="data.totalPages" @prev="page--; loadKbs()" @next="page++; loadKbs()" />
    </section>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue';
import * as api from '../../services/api.js';
import { useUiStore } from '../../stores/ui.js';
import { fmtNum, shortTime } from './adminFormat.js';
import Pager from './Pager.vue';

const ui = useUiStore();
const loading = ref(false);
const page = ref(0);
const filters = reactive({ keyword: '', status: '' });
const data = reactive({ items: [], total: 0, totalPages: 0 });
let timer = null;

onMounted(loadKbs);

function reload() {
  page.value = 0;
  loadKbs();
}

function onSearch() {
  clearTimeout(timer);
  page.value = 0;
  timer = setTimeout(loadKbs, 300);
}

async function loadKbs() {
  loading.value = true;
  try {
    Object.assign(data, await api.adminListKbs({ page: page.value, size: 10, keyword: filters.keyword, status: filters.status || undefined }));
  } catch (err) {
    ui.showToast('error', err.message || '加载知识库失败');
  } finally {
    loading.value = false;
  }
}
</script>
