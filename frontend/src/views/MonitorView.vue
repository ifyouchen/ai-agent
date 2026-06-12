<template>
  <div class="monitor-view">
    <div class="kb-panel monitor-panel">

      <!-- 个人今日用量 -->
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

      <!-- 管理员：全局总览 -->
      <div class="monitor-section">
        <div class="monitor-section-title">
          <span>全局总览（管理员）</span>
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

        <div class="monitor-charts-row">
          <div class="monitor-chart-card">
            <div class="monitor-chart-title">费用趋势（近 {{ days }} 天）</div>
            <canvas ref="costChartEl" height="180"></canvas>
          </div>
          <div class="monitor-chart-card">
            <div class="monitor-chart-title">模型消费占比</div>
            <canvas ref="modelPieEl" height="180"></canvas>
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

        <!-- 用户管理 -->
        <div class="monitor-section-title" style="margin-top:20px;">
          <span>用户管理</span>
          <button class="monitor-refresh-btn" type="button" @click="loadAdminUsers">刷新</button>
        </div>
        <div v-if="!adminUsers.items?.length" class="empty-hint">暂无数据</div>
        <table v-else class="monitor-table">
          <thead><tr><th>用户名</th><th>用户 ID</th><th>角色</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="u in adminUsers.items" :key="u.userId">
              <td>{{ u.username }}</td>
              <td class="user-id-cell">{{ u.userId }}</td>
              <td>{{ u.roles?.join(', ') }}</td>
              <td>
                <span :class="u.enabled ? 'status-enabled' : 'status-disabled'">
                  {{ u.enabled ? '正常' : '禁用' }}
                </span>
              </td>
              <td>
                <button v-if="u.enabled" class="admin-user-btn danger" type="button" @click="disableUser(u.userId)">禁用</button>
                <button v-else class="admin-user-btn" type="button" @click="enableUser(u.userId)">启用</button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="adminUsers.totalPages > 1" class="monitor-pagination">
          <button :disabled="adminUserPage === 0" class="monitor-page-btn" type="button" @click="adminUserPage--; loadAdminUsers()">上一页</button>
          <span>第 {{ adminUserPage + 1 }} / {{ adminUsers.totalPages }} 页</span>
          <button :disabled="adminUserPage >= adminUsers.totalPages - 1" class="monitor-page-btn" type="button" @click="adminUserPage++; loadAdminUsers()">下一页</button>
        </div>
      </div>

    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue';
import { Chart, LineController, BarController, DoughnutController, CategoryScale, LinearScale, PointElement, LineElement, BarElement, ArcElement, Tooltip, Legend, Filler } from 'chart.js';
import * as api from '../services/api.js';
import { useUiStore } from '../stores/ui.js';

Chart.register(LineController, BarController, DoughnutController, CategoryScale, LinearScale, PointElement, LineElement, BarElement, ArcElement, Tooltip, Legend, Filler);

const ui = useUiStore();
const days         = ref(7);
const loading      = reactive({ my: false, admin: false });
const data         = reactive({ myCost: null, todayCost: null, errorRate: 0, errorPct: '—', modelReport: [], userReport: [] });
const adminUsers   = reactive({ items: [], total: 0, totalPages: 0 });
const adminUserPage = ref(0);
const costChartEl  = ref(null);
const modelPieEl   = ref(null);
let costChart = null, modelPie = null;

onMounted(() => { loadMyUsage(); loadAdminStats(); loadAdminUsers(); });

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
    const [todayRes, errorRes, modelRes, userRes] = await Promise.all([
      api.adminGetTodayCost(),
      api.adminGetErrorRate(5),
      api.adminGetModelReport(days.value),
      api.adminGetUserReport(days.value),
    ]);
    data.todayCost   = fmtCost(todayRes.totalCostUsd ?? todayRes.costUsd ?? 0);
    data.errorRate   = errorRes.errorRate ?? 0;
    data.errorPct    = `${((data.errorRate) * 100).toFixed(1)}%`;
    data.modelReport = modelRes || [];
    data.userReport  = userRes  || [];
    renderCharts();
  } catch (err) {
    ui.showToast('error', '加载统计数据失败');
  } finally {
    loading.admin = false;
  }
}

async function loadAdminUsers() {
  try {
    const res = await api.adminListUsers(adminUserPage.value, 20);
    Object.assign(adminUsers, res);
  } catch {}
}

async function enableUser(userId) {
  try { await api.adminEnableUser(userId); await loadAdminUsers(); ui.showToast('success', '已启用用户'); }
  catch (err) { ui.showToast('error', err.message || '操作失败'); }
}

async function disableUser(userId) {
  try { await api.adminDisableUser(userId); await loadAdminUsers(); ui.showToast('success', '已禁用用户'); }
  catch (err) { ui.showToast('error', err.message || '操作失败'); }
}

function renderCharts() {
  // 费用趋势（bar 图）
  if (costChartEl.value) {
    costChart?.destroy();
    costChart = new Chart(costChartEl.value, {
      type: 'bar',
      data: {
        labels: data.modelReport.map(r => r.modelName || 'unknown'),
        datasets: [{ label: '费用（USD）', data: data.modelReport.map(r => r.costUsd ?? 0), backgroundColor: '#4D6BFE88', borderColor: '#4D6BFE', borderWidth: 1 }],
      },
      options: { responsive: true, plugins: { legend: { display: false } } },
    });
  }
  // 模型占比（doughnut 图）
  if (modelPieEl.value) {
    modelPie?.destroy();
    const total = data.modelReport.reduce((s, r) => s + (r.costUsd ?? 0), 0) || 1;
    modelPie = new Chart(modelPieEl.value, {
      type: 'doughnut',
      data: {
        labels: data.modelReport.map(r => r.modelName),
        datasets: [{ data: data.modelReport.map(r => +((r.costUsd / total * 100).toFixed(1))), backgroundColor: ['#4D6BFE','#00A96E','#E53E3E','#D69E2E','#9B59B6'] }],
      },
      options: { responsive: true, plugins: { legend: { position: 'right' } } },
    });
  }
}

const fmtNum  = n => n == null ? '—' : n.toLocaleString();
const fmtCost = n => n == null ? '—' : Number(n).toFixed(6);
</script>
