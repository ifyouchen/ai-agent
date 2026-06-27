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

    <div class="admin-grid-2">
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
import { nextTick, onMounted, ref } from 'vue';
import { Chart, LineController, DoughnutController, CategoryScale, LinearScale, PointElement, LineElement, ArcElement, Tooltip, Legend, Filler } from 'chart.js';
import * as api from '../../services/api.js';
import { displayUserName, fmtCost, fmtNum } from './adminFormat.js';

Chart.register(LineController, DoughnutController, CategoryScale, LinearScale, PointElement, LineElement, ArcElement, Tooltip, Legend, Filler);

const days = ref(7);
const loading = ref(false);
const dailyReport = ref([]);
const modelReport = ref([]);
const userReport = ref([]);
const costChartEl = ref(null);
const modelChartEl = ref(null);
let costChart = null;
let modelChart = null;

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
    dailyReport.value = dailyRes || [];
    modelReport.value = modelRes || [];
    userReport.value = userRes || [];
    await nextTick();
    renderCharts();
  } finally {
    loading.value = false;
  }
}

function renderCharts() {
  if (costChartEl.value && dailyReport.value.length) {
    costChart?.destroy();
    costChart = new Chart(costChartEl.value, {
      type: 'line',
      data: {
        labels: dailyReport.value.map(r => String(r.day).slice(5)),
        datasets: [{ data: dailyReport.value.map(r => Number(r.costUsd || 0)), borderColor: '#4d6bfe', backgroundColor: '#4d6bfe1f', fill: true }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
      },
    });
  }
  if (modelChartEl.value && modelReport.value.length) {
    modelChart?.destroy();
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
}
</script>
