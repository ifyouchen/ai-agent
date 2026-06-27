<template>
  <div class="admin-page">
    <div class="admin-stat-grid">
      <div v-for="item in stats" :key="item.label" class="admin-stat-card">
        <div class="admin-stat-value">{{ item.value }}</div>
        <div class="admin-stat-label">{{ item.label }}</div>
      </div>
    </div>

    <div class="admin-grid-2">
      <section class="admin-panel admin-chart-box">
        <div class="admin-panel-header">
          <h2 class="admin-panel-title">近 7 天成本趋势</h2>
          <button class="admin-small-btn" type="button" :disabled="loading" @click="loadAll">刷新</button>
        </div>
        <div v-if="!dailyReport.length" class="admin-empty">暂无成本趋势数据</div>
        <div v-else class="admin-chart-frame">
          <canvas ref="costChartEl"></canvas>
        </div>
      </section>

      <section class="admin-panel admin-chart-box">
        <div class="admin-panel-header">
          <h2 class="admin-panel-title">模型消费占比</h2>
        </div>
        <div v-if="!modelReport.length" class="admin-empty">暂无模型消费数据</div>
        <div v-else class="admin-chart-frame">
          <canvas ref="modelChartEl"></canvas>
        </div>
      </section>
    </div>

    <div class="admin-grid-2">
      <section class="admin-panel">
        <div class="admin-panel-header">
          <h2 class="admin-panel-title">高消耗用户</h2>
          <router-link class="admin-inline-link" to="/admin/usage">查看用量页</router-link>
        </div>
        <div v-if="!pagedTopUsers.length" class="admin-empty">暂无高消耗用户</div>
        <div v-else class="admin-dashboard-list">
          <div v-for="row in pagedTopUsers" :key="row.userId" class="admin-dashboard-list-item">
            <div>
              <strong>{{ displayUserName(row) }}</strong>
              <span v-if="row.userId && displayUserName(row) !== row.userId">{{ row.userId }}</span>
              <span>{{ fmtNum(row.totalTokens) }} tokens</span>
            </div>
            <span>${{ fmtCost(row.costUsd) }}</span>
          </div>
        </div>
        <div v-if="topUserTotalPages > 1" class="admin-mini-pager">
          <button class="admin-small-btn" :disabled="topUserPage === 0" @click="topUserPage--">上一页</button>
          <span class="admin-muted">第 {{ topUserPage + 1 }} / {{ topUserTotalPages }} 页</span>
          <button class="admin-small-btn" :disabled="topUserPage >= topUserTotalPages - 1" @click="topUserPage++">下一页</button>
        </div>
      </section>

      <section class="admin-panel">
        <div class="admin-panel-header">
          <h2 class="admin-panel-title">失败摘要</h2>
          <router-link v-if="failedTab === 'documents'" class="admin-inline-link" to="/admin/documents">查看文档页</router-link>
        </div>
        <div class="admin-tab-row">
          <button class="admin-tab-btn" :class="{ active: failedTab === 'documents' }" @click="failedTab = 'documents'">
            失败文档 {{ failedDocuments.total || 0 }}
          </button>
          <button class="admin-tab-btn" :class="{ active: failedTab === 'tasks' }" @click="failedTab = 'tasks'">
            失败任务 {{ failedTasks.total || 0 }}
          </button>
        </div>
        <div v-if="!activeFailedItems.length" class="admin-empty">{{ failedTab === 'documents' ? '暂无失败文档' : '暂无失败任务' }}</div>
        <div v-else class="admin-dashboard-list">
          <div v-for="item in activeFailedItems" :key="item.key" class="admin-dashboard-list-item">
            <div>
              <strong>{{ item.title }}</strong>
              <span>{{ item.desc }}</span>
            </div>
            <span class="admin-badge error">失败</span>
          </div>
        </div>
        <div v-if="activeFailedTotalPages > 1" class="admin-mini-pager">
          <button class="admin-small-btn" :disabled="activeFailedPage === 0" @click="changeFailedPage(-1)">上一页</button>
          <span class="admin-muted">第 {{ activeFailedPage + 1 }} / {{ activeFailedTotalPages }} 页</span>
          <button class="admin-small-btn" :disabled="activeFailedPage >= activeFailedTotalPages - 1" @click="changeFailedPage(1)">下一页</button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue';
import { Chart, LineController, DoughnutController, CategoryScale, LinearScale, PointElement, LineElement, ArcElement, Tooltip, Legend, Filler } from 'chart.js';
import * as api from '../../services/api.js';
import { displayUserName, fmtCost, fmtNum, fmtPct } from './adminFormat.js';

Chart.register(LineController, DoughnutController, CategoryScale, LinearScale, PointElement, LineElement, ArcElement, Tooltip, Legend, Filler);

const loading = ref(false);
const summary = reactive({});
const dailyReport = ref([]);
const modelReport = ref([]);
const topUsers = ref([]);
const topUserPage = ref(0);
const failedTab = ref('documents');
const failedDocumentPage = ref(0);
const failedTaskPage = ref(0);
const failedDocuments = reactive({ items: [], total: 0, totalPages: 0 });
const failedTasks = reactive({ items: [], total: 0, totalPages: 0 });
const costChartEl = ref(null);
const modelChartEl = ref(null);
let costChart = null;
let modelChart = null;
const pageSize = 4;

const stats = computed(() => [
  { label: '今日成本', value: `$${fmtCost(summary.todayCostUsd)}` },
  { label: '近 5 分钟错误率', value: fmtPct(summary.errorRate) },
  { label: '用户数', value: fmtNum(summary.userCount) },
  { label: '知识库', value: fmtNum(summary.knowledgeBaseCount) },
  { label: '失败文档', value: fmtNum(summary.failedDocumentCount) },
  { label: '失败任务', value: fmtNum(summary.failedTaskCount) },
]);

const topUserTotalPages = computed(() => Math.ceil(topUsers.value.length / pageSize));
const pagedTopUsers = computed(() => {
  const start = topUserPage.value * pageSize;
  return topUsers.value.slice(start, start + pageSize);
});

const failedDocumentItems = computed(() => (failedDocuments.items || []).map(d => ({
    key: `doc-${d.id}`,
    title: d.name || `文档 ${d.id}`,
    desc: d.parseError || d.kbName || '解析失败',
  })));

const failedTaskItems = computed(() => (failedTasks.items || []).map(t => ({
    key: `task-${t.id}`,
    title: t.projectTitle || `任务 ${t.id}`,
    desc: t.errorMessage || t.currentStep || t.taskType || '生成失败',
  })));

const activeFailedItems = computed(() => failedTab.value === 'documents'
  ? failedDocumentItems.value
  : failedTaskItems.value);
const activeFailedPage = computed(() => failedTab.value === 'documents'
  ? failedDocumentPage.value
  : failedTaskPage.value);
const activeFailedTotalPages = computed(() => failedTab.value === 'documents'
  ? failedDocuments.totalPages
  : failedTasks.totalPages);

onMounted(loadAll);

watch(failedTab, () => {
  if (failedTab.value === 'documents' && !failedDocuments.items.length) loadFailedDocuments();
  if (failedTab.value === 'tasks' && !failedTasks.items.length) loadFailedTasks();
});

async function loadAll() {
  loading.value = true;
  try {
    const [summaryRes, dailyRes, modelRes, userRes, failedDocRes, failedTaskRes] = await Promise.all([
      api.adminGetDashboardSummary(),
      api.adminGetDailyReport(7),
      api.adminGetModelReport(7),
      api.adminGetUserReport(7),
      api.adminListDocuments({ parseStatus: 'FAILED', page: failedDocumentPage.value, size: pageSize }),
      api.adminListTasks({ status: 'FAILED', page: failedTaskPage.value, size: pageSize }),
    ]);
    Object.assign(summary, summaryRes || {});
    dailyReport.value = dailyRes || [];
    modelReport.value = modelRes || [];
    topUsers.value = userRes || [];
    Object.assign(failedDocuments, failedDocRes || {});
    Object.assign(failedTasks, failedTaskRes || {});
    await nextTick();
    renderCharts();
  } finally {
    loading.value = false;
  }
}

async function loadFailedDocuments() {
  Object.assign(failedDocuments, await api.adminListDocuments({
    parseStatus: 'FAILED',
    page: failedDocumentPage.value,
    size: pageSize,
  }));
}

async function loadFailedTasks() {
  Object.assign(failedTasks, await api.adminListTasks({
    status: 'FAILED',
    page: failedTaskPage.value,
    size: pageSize,
  }));
}

async function changeFailedPage(delta) {
  if (failedTab.value === 'documents') {
    failedDocumentPage.value += delta;
    await loadFailedDocuments();
    return;
  }
  failedTaskPage.value += delta;
  await loadFailedTasks();
}

function renderCharts() {
  renderCostChart();
  renderModelChart();
}

function renderCostChart() {
  if (!costChartEl.value || !dailyReport.value.length) return;
  costChart?.destroy();
  costChart = new Chart(costChartEl.value, {
    type: 'line',
    data: {
      labels: dailyReport.value.map(r => String(r.day).slice(5)),
      datasets: [{
        data: dailyReport.value.map(r => Number(r.costUsd || 0)),
        borderColor: '#4d6bfe',
        backgroundColor: '#4d6bfe1f',
        fill: true,
        tension: 0.32,
      }],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: false } },
    },
  });
}

function renderModelChart() {
  if (!modelChartEl.value || !modelReport.value.length) return;
  modelChart?.destroy();
  modelChart = new Chart(modelChartEl.value, {
    type: 'doughnut',
    data: {
      labels: modelReport.value.map(r => r.modelName || 'unknown'),
      datasets: [{
        data: modelReport.value.map(r => Number(r.costUsd || 0)),
        backgroundColor: ['#4d6bfe', '#00a96e', '#d69e2e', '#e53e3e', '#6b7280'],
      }],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      cutout: '58%',
      plugins: { legend: { position: 'bottom' } },
    },
  });
}
</script>
