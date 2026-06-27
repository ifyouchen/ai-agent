<template>
  <div class="admin-page">
    <section class="admin-panel">
      <div class="admin-panel-header">
        <h2 class="admin-panel-title">文档解析</h2>
        <span class="admin-muted">共 {{ data.total || 0 }} 个文档</span>
      </div>
      <div class="admin-filter-row">
        <input v-model.trim="filters.keyword" class="admin-input" placeholder="搜索文件名" @input="onSearch" />
        <select v-model="filters.parseStatus" class="admin-select" @change="reload">
          <option value="">全部状态</option>
          <option v-for="s in statuses" :key="s" :value="s">{{ s }}</option>
        </select>
        <button class="admin-small-btn" type="button" :disabled="loading" @click="loadDocs">刷新</button>
      </div>

      <div v-if="!data.items?.length" class="admin-empty">暂无文档数据</div>
      <div v-else class="admin-table-wrap">
        <table class="admin-table">
          <thead><tr><th>文件名</th><th>知识库</th><th>组织</th><th>状态</th><th>切片</th><th>失败原因</th><th>更新时间</th></tr></thead>
          <tbody>
            <tr v-for="doc in data.items" :key="doc.id">
              <td>{{ doc.name }}</td>
              <td>{{ doc.kbName || doc.kbId }}</td>
              <td>{{ doc.orgName || doc.tenantId }}</td>
              <td><span class="admin-badge" :class="parseStatusClass(doc.parseStatus)">{{ doc.parseStatus }}</span></td>
              <td>{{ fmtNum(doc.chunkCount) }}</td>
              <td><span class="admin-cell-error" :title="doc.parseError || ''">{{ doc.parseError || '-' }}</span></td>
              <td>{{ shortTime(doc.updatedAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <Pager :page="page" :total-pages="data.totalPages" @prev="page--; loadDocs()" @next="page++; loadDocs()" />
    </section>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue';
import * as api from '../../services/api.js';
import { useUiStore } from '../../stores/ui.js';
import { fmtNum, parseStatusClass, shortTime } from './adminFormat.js';
import Pager from './Pager.vue';

const ui = useUiStore();
const loading = ref(false);
const page = ref(0);
const data = reactive({ items: [], total: 0, totalPages: 0 });
const filters = reactive({ keyword: '', parseStatus: '' });
const statuses = ['PENDING', 'PARSING', 'CHUNKING', 'EMBEDDING', 'DONE', 'FAILED'];
let timer = null;

onMounted(loadDocs);

function reload() {
  page.value = 0;
  loadDocs();
}

function onSearch() {
  clearTimeout(timer);
  page.value = 0;
  timer = setTimeout(loadDocs, 300);
}

async function loadDocs() {
  loading.value = true;
  try {
    Object.assign(data, await api.adminListDocuments({ page: page.value, size: 10, keyword: filters.keyword, parseStatus: filters.parseStatus }));
  } catch (err) {
    ui.showToast('error', err.message || '加载文档失败');
  } finally {
    loading.value = false;
  }
}
</script>
