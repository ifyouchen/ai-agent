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
        <article class="usage-kpi usage-kpi-balance">
          <span>可用余额</span>
          <strong>¥{{ fmtCny(wallet.availableBalanceCny) }}</strong>
          <small>冻结 ¥{{ fmtCny(wallet.frozenBalanceCny) }}</small>
        </article>
        <article class="usage-kpi usage-kpi-today">
          <span>今日用量</span>
          <strong>{{ fmtNum(todayUsage.totalTokens) }}</strong>
          <small>${{ fmtCost(todayUsage.costUsd) }}</small>
        </article>
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

      <section class="usage-panel billing-panel">
        <div class="usage-panel-header">
          <h2>余额充值</h2>
          <span>支付宝 / 微信</span>
        </div>
        <div class="recharge-grid">
          <article v-for="item in packages" :key="item.code" class="recharge-card">
            <div>
              <strong>¥{{ fmtCny(item.amountCny) }}</strong>
              <span>{{ item.name }}</span>
            </div>
            <div class="recharge-actions">
              <button type="button" :disabled="creatingOrder" @click="createOrder(item.code, 'ALIPAY')">
                支付宝
              </button>
              <button type="button" :disabled="creatingOrder" @click="createOrder(item.code, 'WECHAT')">
                微信
              </button>
            </div>
          </article>
        </div>
        <div v-if="latestOrder" class="recharge-order">
          <div>
            <span>最近订单</span>
            <strong>{{ latestOrder.orderNo }}</strong>
          </div>
          <div>
            <span>状态</span>
            <strong>{{ orderStatusLabel(latestOrder.status) }}</strong>
          </div>
          <div>
            <span>支付内容</span>
            <strong>{{ latestOrder.payQrContent || '-' }}</strong>
          </div>
        </div>
      </section>

      <section class="usage-panel">
        <div class="usage-panel-header">
          <h2>每日消耗趋势</h2>
          <span>{{ summary.startAt || '-' }} 至 {{ summary.endAt || '-' }}</span>
        </div>
        <div v-if="!dailySeries.length && !loading" class="usage-empty">暂无趋势数据</div>
        <div v-else class="usage-chart-frame">
          <canvas ref="chartEl"></canvas>
        </div>
      </section>

      <section class="usage-panel">
        <div class="usage-panel-header">
          <h2>钱包流水</h2>
          <span>最近 {{ ledgerItems.length }} 条</span>
        </div>
        <div class="usage-detail-list">
          <article v-for="item in ledgerItems" :key="item.id || item.ledgerNo" class="usage-detail-item">
            <div class="usage-detail-main">
              <div class="usage-detail-title">
                <span class="usage-scenario">{{ ledgerTypeLabel(item.type) }}</span>
                <strong>{{ item.remark || item.refId || item.ledgerNo }}</strong>
              </div>
              <div class="usage-detail-meta">
                <span>{{ formatDateTime(item.createdAt) }}</span>
                <span>{{ item.refType || '-' }}</span>
                <span>{{ item.refId || '-' }}</span>
              </div>
            </div>
            <div class="usage-detail-cost">
              <strong>{{ signedCny(item.amountCny) }}</strong>
              <small>余额 ¥{{ fmtCny(item.balanceAfterCny) }}</small>
            </div>
          </article>
          <div v-if="!ledgerItems.length && !loading" class="usage-empty">暂无钱包流水</div>
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
import { useUiStore } from '../stores/ui.js';
import * as api from '../services/api.js';

const ui = useUiStore();
const dayOptions = [7, 14, 30];
const days = ref(7);
const loading = ref(false);
const loadingDetails = ref(false);
const summary = ref({});
const dailyRows = ref([]);
const wallet = ref({});
const packages = ref([]);
const ledgerItems = ref([]);
const latestOrder = ref(null);
const creatingOrder = ref(false);
const detailPage = ref(1);
const detailSize = 10;
const detailTotal = ref(0);
const detailTotalPages = ref(0);
const detailItems = ref([]);
const chartEl = ref(null);
let chart = null;

const dailySeries = computed(() => buildDailySeries(days.value, dailyRows.value));
const todayUsage = computed(() =>
  dailySeries.value[dailySeries.value.length - 1] || { totalTokens: 0, costUsd: 0 }
);

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
  loadingDetails.value = true;
  try {
    const [summaryRes, dailyRes, detailsRes] = await Promise.all([
      api.getMyUsageSummary(days.value),
      api.getMyDailyUsage(days.value),
      api.getMyUsageDetails({ days: days.value, page: detailPage.value, size: detailSize }),
      loadBilling(),
    ]);
    summary.value = summaryRes || {};
    dailyRows.value = dailyRes || [];
    detailItems.value = detailsRes?.items || [];
    detailTotal.value = Number(detailsRes?.total || 0);
    detailTotalPages.value = Number(detailsRes?.totalPages || 0);
    await nextTick();
    renderChart();
  } catch (err) {
    ui.showToast('error', err.message || '加载 Token 用量失败');
  } finally {
    loading.value = false;
    loadingDetails.value = false;
  }
}

async function loadBilling() {
  const [walletRes, packagesRes, ledgerRes, ordersRes] = await Promise.all([
    api.getBillingWallet(),
    api.getBillingPackages(),
    api.getBillingLedger({ page: 1, size: 10 }),
    api.getRechargeOrders({ page: 1, size: 1 }),
  ]);
  wallet.value = walletRes || {};
  packages.value = packagesRes || [];
  ledgerItems.value = ledgerRes || [];
  latestOrder.value = Array.isArray(ordersRes) && ordersRes.length ? ordersRes[0] : null;
}

async function createOrder(packageCode, payChannel) {
  creatingOrder.value = true;
  try {
    latestOrder.value = await api.createRechargeOrder({ packageCode, payChannel });
    ui.showToast('success', '充值订单已创建');
    await loadBilling();
  } catch (err) {
    ui.showToast('error', err.message || '创建充值订单失败');
  } finally {
    creatingOrder.value = false;
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

let chartRegistered = false;

async function renderChart() {
  if (!chartEl.value || !dailySeries.value.length) return;
  chart?.destroy();
  const { Chart, LineController, CategoryScale, LinearScale, PointElement, LineElement, Tooltip, Legend, Filler } = await import('chart.js');
  if (!chartRegistered) {
    Chart.register(LineController, CategoryScale, LinearScale, PointElement, LineElement, Tooltip, Legend, Filler);
    chartRegistered = true;
  }
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

function fmtCny(value) {
  return Number(value || 0).toFixed(2);
}

function signedCny(value) {
  const number = Number(value || 0);
  const sign = number > 0 ? '+' : '';
  return `${sign}¥${number.toFixed(6)}`;
}

function ledgerTypeLabel(type) {
  const labels = {
    RECHARGE: '充值',
    RESERVE: '冻结',
    SETTLE: '扣费',
    RELEASE: '释放',
    REFUND: '退款',
    ADJUSTMENT: '调整',
  };
  return labels[type] || type || '流水';
}

function orderStatusLabel(status) {
  const labels = {
    CREATED: '待支付',
    PAYING: '支付中',
    PAID: '已支付',
    CLOSED: '已关闭',
    REFUNDED: '已退款',
  };
  return labels[status] || status || '-';
}

function formatDateTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString();
}
</script>

<style scoped>
@import '../css/views/token-usage-view.css';
</style>
