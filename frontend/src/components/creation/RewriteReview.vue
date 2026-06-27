<template>
  <div class="creation-review-shell">
    <div v-if="ready" class="creation-flow-steps">
      <span class="done">选择片段</span>
      <span class="active">逐段确认</span>
      <span :class="{ active: acceptedCount + rejectedCount === segments.length }">保存新版本</span>
    </div>
    <RewriteCompareView
      :task="task"
      :review-summary="reviewSummary"
      :ready="ready"
      :accepting="accepting"
      :retrying="retrying"
      :page-busy="pageBusy"
      :can-cancel="canCancel"
      :can-retry="canRetry"
      :progress="progress"
      :segments="segments"
      :accepted-count="acceptedCount"
      :rejected-count="rejectedCount"
      :segment-notes="segmentNotes"
      :form="form"
      :status-label-for="statusLabelFor"
      @back="$emit('back')"
      @refresh="$emit('refresh')"
      @accept="$emit('accept')"
      @cancel="$emit('cancel')"
      @retry="$emit('retry')"
      @set-segment-status="(...args) => $emit('set-segment-status', ...args)"
    />
    <div v-if="ready" class="creation-sticky-next">
      <strong>{{ acceptedCount }} 段采用 AI，{{ rejectedCount }} 段保留原文</strong>
      <button class="creation-primary-btn" type="button" :disabled="accepting" @click="$emit('accept')">
        保存为新版本
      </button>
    </div>
  </div>
</template>

<script setup>
import RewriteCompareView from './RewriteCompareView.vue';

defineProps({
  task: { type: Object, default: null },
  reviewSummary: { type: String, required: true },
  ready: Boolean,
  accepting: Boolean,
  retrying: Boolean,
  pageBusy: { type: String, default: '' },
  canCancel: Boolean,
  canRetry: Boolean,
  progress: { type: Number, default: 0 },
  segments: { type: Array, default: () => [] },
  acceptedCount: { type: Number, default: 0 },
  rejectedCount: { type: Number, default: 0 },
  segmentNotes: { type: Array, default: () => [] },
  form: { type: Object, required: true },
  statusLabelFor: { type: Function, required: true },
});

defineEmits(['back', 'refresh', 'accept', 'cancel', 'retry', 'set-segment-status']);
</script>
