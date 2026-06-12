<template>
  <div class="monitor-view">
    <div class="kb-panel monitor-panel">

      <!-- ① 个人今日用量（所有登录用户可见） -->
      <div class="monitor-section">
        <div class="monitor-section-title">
          <span>我的今日用量</span>
          <button class="monitor-refresh-btn" type="button" @click="loadMyUsage" :disabled="loading.my">
            <svg viewBox="0 0 24 24" fill="none" width="13" height="13" :class="{ spinning: loading.my }">
              <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              <path d="M21 3v5h-5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              <path d="M8 16H3v5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            刷新
          </button>
        </div>
        <div class="monitor-cards">
          <div class="monitor-card">
            <div class="monitor-card-value">${{ data.myCost ?? '—' }}</div>
            <div class="monitor-card-label">今日消费（USD）</div>
          </div>
        </div>
      </div>

      <!-- ② 管理员：全局总览（仅 admin 可见） -->
      <template v-if="auth.isAdmin">
        <div class="monitor-section">
          <div class="monitor-section-title">
            <span>全局总览</span>
            <div class="monitor-period-btns">
              <button
                v-for="d in [7, 14, 30]" :key="d"
                class="monitor-period-btn"
                :class="{ active: days === d }"
                type="button"
                @click="days = d; loadAdminStats()"
              >近{{ d }}天</button>
            </div>
            <button class="monitor-refresh-btn" type="button" @click="loadAdminStats" :disabled="loading.admin">
              <svg viewBox="0 0 24 24" fill="none" width="13" height="13" :class="{ spinning: loading.admin }">
                <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                <path d="M21 3v5h-5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                <path d="M8 16H3v5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              刷新
            </button>
          </div>

          <div class="monitor-cards">
            <div class="monitor-card">
              <div class="monitor-card-value">${{ data.todayCost ?? '—' }}</div>
              <div class="monitor-card-label">今日总消费（USD）</div>
            </div>
            <div class="monitor-card" :class="{ 'monitor-card-warn': data.errorRate > 0.05 }">
              <div class="monitor-card-value">{{ data.errorPct ?? '—' }}</div>
              <div class="monitor-card-label">近5分钟错误率</div>
            </div>
          </div>

          <!-- P0-2 修正：两张图表各自数据源正确 -->
          <div class="monitor-charts-row">
            <!-- 图1：按天费用趋势折线图（新增按天接口） -->
            <div class="monitor-chart-card">
              <div class="monitor-chart-title">费用趋势（近 {{ days }} 天）</div>
              <div v-if="!data.dailyReport?.length" class="chart-empty">暂无数据</div>
              <canvas v-else ref="costChartEl" height="180"></canvas>
            </div>
            <!-- 图2：模型消费占比（甜甜圈） -->
            <div class="monitor-chart-card">
              <div class="monitor-chart-title">模型消费占比</div>
              <div v-if="!data.modelReport?.length" class="chart-empty">暂无数据</div>
              <canvas v-else ref="modelPieEl" height="180"></canvas>
            </div>
          </div>

          <!-- 按模型统计 -->
          <div class="monitor-table-title">按模型统计（近 {{ days }} 天）</div>
          <div v-if="!data.modelReport?.length" class="empty-hint">暂无数据</div>
          <table v-else class="monitor-table">
            <thead><tr><th>模型</th><th>输入 Token</th><th>输出 Token</th><th>费用（USD）</th></tr></thead>
            <tbody>
              <tr v-for="row in data.modelReport" :key="row.modelName">
                <td>{{ row.modelName }}</td>
                <td>{{ fmtNum(row.inputTokens) }}</td>
                <td>{{ fmtNum(row.outputTokens) }}</td>
                <td>${{ fmtCost(row.costUsd) }}</td>
              </tr>
            </tbody>
          </table>

          <!-- 用户消费排行 -->
          <div class="monitor-table-title">用户消费排行（近 {{ days }} 天）</div>
          <div v-if="!data.userReport?.length" class="empty-hint">暂无数据</div>
          <table v-else class="monitor-table">
            <thead><tr><th>用户 ID</th><th>总 Token</th><th>费用（USD）</th></tr></thead>
            <tbody>
              <tr v-for="row in data.userReport" :key="row.userId">
                <td class="user-id-cell">{{ row.userId }}</td>
                <td>{{ fmtNum(row.totalTokens) }}</td>
                <td>${{ fmtCost(row.costUsd) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, watch, nextTick } from 'vue';
import { Chart, LineController, BarController, DoughnutController, CategoryScale, LinearScale, PointElement, LineElement, BarElement, ArcElement, Tooltip, Legend, Filler } from 'chart.js';
import * as api from '../services/api.js';
import { useUiStore } from '../stores/ui.js';
import { useAuthStore } from '../stores/auth.js';

Chart.register(LineController, BarController, DoughnutController, CategoryScale, LinearScale, PointElement, LineElement, BarElement, ArcElement, Tooltip, Legend, Filler);

const ui   = useUiStore();
const auth = useAuthStore();

const days         = ref(7);
const loading      = reactive({ my: false, admin: false });
const data         = reactive({
  myCost: null,
  todayCost: null, errorRate: 0, errorPct: '—',
  modelReport: [], userReport: [], dailyReport: [],
});
const costChartEl  = ref(null);
const modelPieEl   = ref(null);
let costChart = null, modelPie = null;

onMounted(() => {
  loadMyUsage();
  if (auth.isAdmin) loadAdminStats();
});

async function loadMyUsage() {
  loading.my = true;
  try {
    const res = await api.getMyTodayCost();
    data.myCost = fmtCost(res.totalCostUsd ?? res.costUsd ?? 0);
  } catch { data.myCost = '—'; }
  finally { loading.my = false; }
}

async function loadAdminStats() {
  loading.admin = true;
  try {
    const [todayRes, errorRes, modelRes, userRes, dailyRes] = await Promise.all([
      api.adminGetTodayCost(),
      api.adminGetErrorRate(5),
      api.adminGetModelReport(days.value),
      api.adminGetUserReport(days.value),
      api.adminGetDailyReport(days.value),
    ]);
    data.todayCost   = fmtCost(todayRes.totalCostUsd ?? todayRes.costUsd ?? 0);
    data.errorRate   = errorRes.errorRate ?? 0;
    data.errorPct    = `${((data.errorRate) * 100).toFixed(1)}%`;
    data.modelReport = modelRes || [];
    data.userReport  = userRes  || [];
    data.dailyReport = dailyRes || [];
    await nextTick();
    renderCharts();
  } catch (err) {
    ui.showToast('error', '加载统计数据失败');
  } finally {
    loading.admin = false;
  }
}

function renderCharts() {
  // 图1：按天费用趋势折线图（P0-2 修正）
  if (costChartEl.value && data.dailyReport?.length) {
    costChart?.destroy();
    costChart = new Chart(costChartEl.value, {
      type: 'line',
      data: {
        labels: data.dailyReport.map(r => r.day?.slice(5) || r.day), // 显示"MM-DD"
        datasets: [{
          label: '费用（USD）',
          data: data.dailyReport.map(r => Number(r.costUsd ?? 0).toFixed(6)),
          backgroundColor: '#4D6BFE22',
          borderColor: '#4D6BFE',
          borderWidth: 2,
          pointRadius: 3,
          fill: true,
          tension: 0.3,
        }],
      },
      options: {
        responsive: true,
        plugins: { legend: { display: false } },
        scales: {
          y: { beginAtZero: true, ticks: { callback: v => '$' + Number(v).toFixed(4) } },
        },
      },
    });
  }
  // 图2：模型消费占比甜甜圈（数据不变，标题已匹配）
  if (modelPieEl.value && data.modelReport?.length) {
    modelPie?.destroy();
    const total = data.modelReport.reduce((s, r) => s + (Number(r.costUsd) ?? 0), 0) || 1;
    modelPie = new Chart(modelPieEl.value, {
      type: 'doughnut',
      data: {
        labels: data.modelReport.map(r => r.modelName),
        datasets: [{
          data: data.modelReport.map(r => +((Number(r.costUsd) / total * 100).toFixed(1))),
          backgroundColor: ['#4D6BFE', '#00A96E', '#E53E3E', '#D69E2E', '#9B59B6'],
        }],
      },
      options: { responsive: true, plugins: { legend: { position: 'right' } } },
    });
  }
}

// P2-10：精度友好显示（≥0.01 两位小数，≥0.0001 四位，更小用科学计数）
function fmtCost(n) {
  if (n == null) return '—';
  const v = Number(n);
  if (v === 0) return '0.00';
  if (v >= 0.01)   return v.toFixed(2);
  if (v >= 0.0001) return v.toFixed(4);
  return v.toExponential(2);
}

const fmtNum = n => n == null ? '—' : Number(n).toLocaleString();
</script>

<style scoped>
.chart-empty {
  height: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #aaa;
  font-size: 13px;
}
</style>
