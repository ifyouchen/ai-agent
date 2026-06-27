<template>
  <div class="admin-page">
    <section class="admin-panel">
      <div class="admin-panel-header">
        <h2 class="admin-panel-title">用量成本</h2>
        <div class="admin-filter-row">
          <button v-for="d in [7, 14, 30]" :key="d" class="admin-small-btn"
                  :class="{ 'admin-primary-btn': days === d }" type="button" @click="setDays(d)">近 {{ d }} 天</button>
          <button class="admin-small-btn" type="button" :disabled="loading" @click="loadReports">刷新</button>
        </div>
      </div>
    </section>

    <div class="admin-usage-chart-grid">
      <section class="admin-panel admin-chart-box">
        <h2 class="admin-panel-title">每日成本趋势</h2>
        <div v-if="!dailyReport.length" class="admin-empty">暂无趋势数据</div>
        <div v-else class="admin-chart-frame">
          <canvas ref="costChartEl"></canvas>
        </div>
      </section>
      <section class="admin-panel admin-chart-box">
        <h2 class="admin-panel-title">模型消费占比</h2>
        <div v-if="!modelReport.length" class="admin-empty">暂无模型数据</div>
        <div v-else class="admin-chart-frame">
          <canvas ref="modelChartEl"></canvas>
        </div>
      </section>
      <section class="admin-panel admin-chart-box">
        <h2 class="admin-panel-title">模型 Token 占比</h2>
        <div v-if="!hasModelTokenShare" class="admin-empty">暂无 Token 数据</div>
        <div v-else class="admin-chart-frame">
          <canvas ref="modelTokenChartEl"></canvas>
        </div>
      </section>
    </div>

    <div class="admin-grid-2">
      <section class="admin-panel">
        <h2 class="admin-panel-title">按模型统计</h2>
        <div class="admin-table-wrap">
          <table class="admin-table">
            <thead><tr><th>模型</th><th>输入 Token</th><th>输出 Token</th><th>费用</th></tr></thead>
            <tbody>
              <tr v-for="row in modelReport" :key="row.modelName">
                <td>{{ row.modelName }}</td>
                <td>{{ fmtNum(row.inputTokens) }}</td>
                <td>{{ fmtNum(row.outputTokens) }}</td>
                <td>${{ fmtCost(row.costUsd) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="admin-panel">
        <h2 class="admin-panel-title">用户消费排行</h2>
        <div class="admin-table-wrap">
          <table class="admin-table">
            <thead><tr><th>用户名</th><th>用户 ID</th><th>总 Token</th><th>费用</th></tr></thead>
            <tbody>
              <tr v-for="row in userReport" :key="row.userId">
                <td>{{ displayUserName(row) }}</td>
                <td>{{ row.userId }}</td>
                <td>{{ fmtNum(row.totalTokens) }}</td>
                <td>${{ fmtCost(row.costUsd) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue';
import { Chart, LineController, DoughnutController, CategoryScale, LinearScale, PointElement, LineElement, ArcElement, Tooltip, Legend, Filler } from 'chart.js';
import * as api from '../../services/api.js';
import { displayUserName, fmtCost, fmtNum } from './adminFormat.js';
import { dailyCostChartOptions, fillDailyCostReport } from './adminUsageCharts.js';

Chart.register(LineController, DoughnutController, CategoryScale, LinearScale, PointElement, LineElement, ArcElement, Tooltip, Legend, Filler);

const days = ref(7);
const loading = ref(false);
const dailyReport = ref([]);
const modelReport = ref([]);
const userReport = ref([]);
const costChartEl = ref(null);
const modelChartEl = ref(null);
const modelTokenChartEl = ref(null);
let costChart = null;
let modelChart = null;
let modelTokenChart = null;

const modelTokenReport = computed(() => modelReport.value.map(row => ({
  modelName: row.modelName || 'unknown',
  totalTokens: Number(row.inputTokens || 0) + Number(row.outputTokens || 0),
})));
const hasModelTokenShare = computed(() =>
  modelTokenReport.value.some(row => row.totalTokens > 0)
);

onMounted(loadReports);

async function setDays(value) {
  days.value = value;
  await loadReports();
}

async function loadReports() {
  loading.value = true;
  try {
    const [dailyRes, modelRes, userRes] = await Promise.all([
      api.adminGetDailyReport(days.value),
      api.adminGetModelReport(days.value),
      api.adminGetUserReport(days.value),
    ]);
    dailyReport.value = fillDailyCostReport(dailyRes, days.value);
    modelReport.value = modelRes || [];
    userReport.value = userRes || [];
    await nextTick();
    renderCharts();
  } finally {
    loading.value = false;
  }
}

function renderCharts() {
  costChart?.destroy();
  costChart = null;
  modelChart?.destroy();
  modelChart = null;
  modelTokenChart?.destroy();
  modelTokenChart = null;

  if (costChartEl.value && dailyReport.value.length) {
    costChart = new Chart(costChartEl.value, {
      type: 'line',
      data: {
        labels: dailyReport.value.map(r => String(r.day).slice(5)),
        datasets: [{ data: dailyReport.value.map(r => Number(r.costUsd || 0)), borderColor: '#4d6bfe', backgroundColor: '#4d6bfe1f', fill: true }],
      },
      options: dailyCostChartOptions(days.value),
    });
  }
  if (modelChartEl.value && modelReport.value.length) {
    modelChart = new Chart(modelChartEl.value, {
      type: 'doughnut',
      data: {
        labels: modelReport.value.map(r => r.modelName || 'unknown'),
        datasets: [{ data: modelReport.value.map(r => Number(r.costUsd || 0)), backgroundColor: ['#4d6bfe', '#00a96e', '#d69e2e', '#e53e3e'] }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: '58%',
        plugins: { legend: { position: 'bottom' } },
      },
    });
  }
  if (modelTokenChartEl.value && hasModelTokenShare.value) {
    modelTokenChart = new Chart(modelTokenChartEl.value, {
      type: 'doughnut',
      data: {
        labels: modelTokenReport.value.map(r => r.modelName),
        datasets: [{ data: modelTokenReport.value.map(r => r.totalTokens), backgroundColor: ['#4d6bfe', '#00a96e', '#d69e2e', '#e53e3e'] }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: '58%',
        plugins: { legend: { position: 'bottom' } },
      },
    });
  }
}
</script>
