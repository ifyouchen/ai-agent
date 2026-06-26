<template>
  <section class="creation-panel creation-task-history">
    <div class="creation-section-title">
      <div>
        <h2>历史任务 <span>{{ badgeText }}</span></h2>
        <p>默认展示最近 3 条，可展开查看完整任务记录。</p>
      </div>
      <div class="creation-row">
        <button v-if="canExpand" class="creation-secondary-btn" type="button" @click="$emit('toggle')">{{ toggleLabel }}</button>
        <button class="creation-secondary-btn" type="button" :disabled="loading" @click="$emit('refresh')">
          {{ loading ? '刷新中...' : '刷新' }}
        </button>
      </div>
    </div>
    <div class="creation-history-search">
      <input
        :value="searchInput"
        placeholder="搜索作品名、任务名或进度信息"
        @input="$emit('update:searchInput', $event.target.value)"
      />
      <button v-if="searchInput" class="creation-secondary-btn compact" type="button" @click="$emit('clear-search')">清空</button>
    </div>
    <div class="creation-history-list" :class="{ expanded }">
      <div v-if="!visibleTasks.length" class="creation-empty">暂无历史任务</div>
      <div v-for="item in visibleTasks" :key="`${item.kind}:${item.taskId}`" class="creation-history-item">
        <div>
          <strong>{{ item.title || item.name || (item.kind === 'rewrite' ? '改小说三栏对照' : '短剧分场稿生成') }}</strong>
          <span>{{ item.projectTitle || item.draftTitle || '未知作品' }} · {{ statusLabelFor(item.status) }} · {{ progressFor(item) }}%</span>
          <small>{{ item.currentStep || '暂无进度信息' }}</small>
        </div>
        <div class="creation-history-actions">
          <button class="creation-secondary-btn compact" type="button" :disabled="busyKey === taskKeyFor(item)" @click="$emit('restore', item)">
            {{ busyKey === taskKeyFor(item) && busyAction === 'restore' ? '置顶中...' : '置顶任务' }}
          </button>
          <button class="creation-primary-btn compact" type="button" :disabled="!canOpen(item) || busyKey === taskKeyFor(item)" @click="$emit('open', item)">打开</button>
          <button class="creation-secondary-btn compact" type="button" :disabled="!canCancel(item) || busyKey === taskKeyFor(item)" @click="$emit('cancel', item)">
            {{ busyKey === taskKeyFor(item) && busyAction === 'cancel' ? '终止中...' : '终止' }}
          </button>
          <button class="creation-secondary-btn compact" type="button" :disabled="!canRetry(item) || busyKey === taskKeyFor(item)" @click="$emit('retry', item)">
            {{ busyKey === taskKeyFor(item) && busyAction === 'retry' ? '重试中...' : '重试' }}
          </button>
        </div>
      </div>
    </div>
    <p v-if="hiddenCount && !expanded && !searchActive" class="creation-history-note">还有 {{ hiddenCount }} 条历史任务，可点击展开查看。</p>
  </section>
</template>

<script setup>
defineProps({
  badgeText: { type: String, required: true },
  canExpand: Boolean,
  toggleLabel: { type: String, required: true },
  loading: Boolean,
  searchInput: { type: String, default: '' },
  visibleTasks: { type: Array, default: () => [] },
  expanded: Boolean,
  hiddenCount: { type: Number, default: 0 },
  searchActive: Boolean,
  busyKey: { type: String, default: '' },
  busyAction: { type: String, default: '' },
  taskKeyFor: { type: Function, required: true },
  statusLabelFor: { type: Function, required: true },
  progressFor: { type: Function, required: true },
  canOpen: { type: Function, required: true },
  canCancel: { type: Function, required: true },
  canRetry: { type: Function, required: true },
});

defineEmits(['toggle', 'refresh', 'update:searchInput', 'clear-search', 'restore', 'open', 'cancel', 'retry']);
</script>
