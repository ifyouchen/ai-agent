<template>
  <div class="token-usage-view">
    <div class="token-usage-shell">
      <header class="usage-header">
        <div>
          <h1>Token 用量</h1>
          <p>统计当前账号近 {{ days }} 天的模型调用、文档解析和会话消耗。</p>
        </div>
        <div class="usage-actions">
          <button
            v-for="item in dayOptions"
            :key="item"
            class="usage-range-btn"
            :class="{ active: days === item }"
            type="button"
            @click="setDays(item)"
          >近 {{ item }} 天</button>
          <button class="usage-refresh-btn" type="button" :disabled="loading" @click="loadAll">
            {{ loading ? '刷新中' : '刷新' }}
          </button>
        </div>
      </header>

      <section class="usage-kpis">
        <article class="usage-kpi">
          <span>总 Token</span>
          <strong>{{ fmtNum(summary.totalTokens) }}</strong>
        </article>
        <article class="usage-kpi">
          <span>输入 Token</span>
          <strong>{{ fmtNum(summary.inputTokens) }}</strong>
        </article>
        <article class="usage-kpi">
          <span>输出 Token</span>
          <strong>{{ fmtNum(summary.outputTokens) }}</strong>
        </article>
        <article class="usage-kpi">
          <span>调用次数</span>
          <strong>{{ fmtNum(summary.callCount) }}</strong>
        </article>
        <article class="usage-kpi">
          <span>估算费用</span>
          <strong>${{ fmtCost(summary.costUsd) }}</strong>
        </article>
      </section>

      <section class="usage-panel">
        <div class="usage-panel-header">
          <h2>每日消耗趋势</h2>
          <span>{{ summary.startAt || '-' }} 至 {{ summary.endAt || '-' }}</span>
        </div>
        <div v-if="!dailySeries.length" class="usage-empty">暂无趋势数据</div>
        <div v-else class="usage-chart-frame">
          <canvas ref="chartEl"></canvas>
        </div>
      </section>

      <section class="usage-panel">
        <div class="usage-panel-header">
          <h2>Token 明细</h2>
          <span>共 {{ fmtNum(detailTotal) }} 条</span>
        </div>
        <div class="usage-detail-list">
          <article v-for="item in detailItems" :key="item.id" class="usage-detail-item">
            <div class="usage-detail-main">
              <div class="usage-detail-title">
                <span class="usage-scenario">{{ item.scenarioLabel || item.scenario || '未知' }}</span>
                <strong>{{ item.inputSnippet || item.outputSnippet || '无内容摘要' }}</strong>
              </div>
              <div class="usage-detail-meta">
                <span>{{ item.calledAt || '-' }}</span>
                <span>{{ item.modelName || 'unknown' }}</span>
                <span>会话：{{ item.sessionId || '-' }}</span>
              </div>
              <p v-if="item.errorMessage" class="usage-error">{{ item.errorMessage }}</p>
            </div>
            <div class="usage-detail-cost">
              <strong>{{ fmtNum(item.totalTokens) }}</strong>
              <span>{{ fmtNum(item.inputTokens) }} / {{ fmtNum(item.outputTokens) }}</span>
              <small>${{ fmtCost(item.costUsd) }}</small>
            </div>
          </article>
          <div v-if="!detailItems.length && !loadingDetails" class="usage-empty">暂无 Token 明细</div>
        </div>
        <div v-if="detailTotalPages > 1" class="usage-pager">
          <button type="button" :disabled="detailPage <= 1 || loadingDetails" @click="setDetailPage(detailPage - 1)">
            上一页
          </button>
          <span>第 {{ detailPage }} / {{ detailTotalPages }} 页</span>
          <button type="button" :disabled="detailPage >= detailTotalPages || loadingDetails" @click="setDetailPage(detailPage + 1)">
            下一页
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import {
  CategoryScale,
  Chart,
  Filler,
  Legend,
  LinearScale,
  LineController,
  LineElement,
  PointElement,
  Tooltip,
} from 'chart.js';
import { useUiStore } from '../stores/ui.js';
import * as api from '../services/api.js';

Chart.register(LineController, CategoryScale, LinearScale, PointElement, LineElement, Tooltip, Legend, Filler);

const ui = useUiStore();
const dayOptions = [7, 14, 30];
const days = ref(7);
const loading = ref(false);
const loadingDetails = ref(false);
const summary = ref({});
const dailyRows = ref([]);
const detailPage = ref(1);
const detailSize = 10;
const detailTotal = ref(0);
const detailTotalPages = ref(0);
const detailItems = ref([]);
const chartEl = ref(null);
let chart = null;

const dailySeries = computed(() => buildDailySeries(days.value, dailyRows.value));

onMounted(loadAll);
onBeforeUnmount(() => chart?.destroy());

async function setDays(value) {
  days.value = value;
  detailPage.value = 1;
  await loadAll();
}

async function setDetailPage(value) {
  detailPage.value = value;
  await loadDetails();
}

async function loadAll() {
  loading.value = true;
  try {
    const [summaryRes, dailyRes] = await Promise.all([
      api.getMyUsageSummary(days.value),
      api.getMyDailyUsage(days.value),
    ]);
    summary.value = summaryRes || {};
    dailyRows.value = dailyRes || [];
    await loadDetails();
    await nextTick();
    renderChart();
  } catch (err) {
    ui.showToast('error', err.message || '加载 Token 用量失败');
  } finally {
    loading.value = false;
  }
}

async function loadDetails() {
  loadingDetails.value = true;
  try {
    const data = await api.getMyUsageDetails({
      days: days.value,
      page: detailPage.value,
      size: detailSize,
    });
    detailItems.value = data?.items || [];
    detailTotal.value = Number(data?.total || 0);
    detailTotalPages.value = Number(data?.totalPages || 0);
  } catch (err) {
    ui.showToast('error', err.message || '加载 Token 明细失败');
  } finally {
    loadingDetails.value = false;
  }
}

function renderChart() {
  if (!chartEl.value || !dailySeries.value.length) return;
  chart?.destroy();
  chart = new Chart(chartEl.value, {
    type: 'line',
    data: {
      labels: dailySeries.value.map(item => item.label),
      datasets: [{
        label: 'Token',
        data: dailySeries.value.map(item => item.totalTokens),
        borderColor: '#4d6bfe',
        backgroundColor: 'rgba(77, 107, 254, 0.12)',
        borderWidth: 2,
        pointRadius: 3,
        tension: 0.35,
        fill: true,
      }],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            label(ctx) {
              const row = dailySeries.value[ctx.dataIndex];
              return `${fmtNum(row.totalTokens)} tokens / $${fmtCost(row.costUsd)}`;
            },
          },
        },
      },
      scales: {
        y: { beginAtZero: true, ticks: { precision: 0 } },
      },
    },
  });
}

function buildDailySeries(dayCount, rows) {
  const map = new Map((rows || []).map(row => [String(row.day), row]));
  const result = [];
  const today = new Date();
  for (let index = dayCount - 1; index >= 0; index -= 1) {
    const date = new Date(today.getFullYear(), today.getMonth(), today.getDate() - index);
    const key = formatDateKey(date);
    const row = map.get(key) || {};
    result.push({
      day: key,
      label: key.slice(5),
      totalTokens: Number(row.totalTokens || 0),
      costUsd: Number(row.costUsd || 0),
    });
  }
  return result;
}

function formatDateKey(date) {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

function fmtNum(value) {
  return Number(value || 0).toLocaleString();
}

function fmtCost(value) {
  return Number(value || 0).toFixed(6);
}
</script>

<style scoped>
@import '../css/views/token-usage-view.css';
</style>
