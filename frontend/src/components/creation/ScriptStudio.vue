<template>
  <div class="creation-script-studio">
    <div class="creation-flow-steps">
      <span class="done">生成分集</span>
      <span class="active">编辑分场</span>
      <span :class="{ active: qualityReport }">质检修复</span>
      <span>导出</span>
    </div>
    <ScriptWorkbenchView
      :draft="draft"
      :draft-id="draftId"
      :current-episode="currentEpisode"
      :current-scene="currentScene"
      :scene-form="sceneForm"
      :busy="busy"
      :action-title="actionTitle"
      :quality-report="qualityReport"
      :quality-issue-list="qualityIssueList"
      :fixing-issue="fixingIssue"
      :adaptation-plan-entries="adaptationPlanEntries"
      :is-issue-optimized="isIssueOptimized"
      @quality-check="$emit('quality-check')"
      @add-scene="(...args) => $emit('add-scene', ...args)"
      @select-scene="(...args) => $emit('select-scene', ...args)"
      @save-scene="$emit('save-scene')"
      @improve-episode="$emit('improve-episode')"
      @improve-scene="(...args) => $emit('improve-scene', ...args)"
      @move-scene="(...args) => $emit('move-scene', ...args)"
      @delete-scene="$emit('delete-scene')"
      @improve-issue="(...args) => $emit('improve-issue', ...args)"
    />
  </div>
</template>

<script setup>
import ScriptWorkbenchView from './ScriptWorkbenchView.vue';

defineProps({
  draft: { type: Object, default: null },
  draftId: { type: [String, Number], required: true },
  currentEpisode: { type: Object, default: null },
  currentScene: { type: Object, default: null },
  sceneForm: { type: Object, required: true },
  busy: { type: String, default: '' },
  actionTitle: { type: String, default: '' },
  qualityReport: { type: Object, default: null },
  qualityIssueList: { type: Array, default: () => [] },
  fixingIssue: { type: String, default: '' },
  adaptationPlanEntries: { type: Array, default: () => [] },
  isIssueOptimized: { type: Function, required: true },
});

defineEmits([
  'quality-check',
  'add-scene',
  'select-scene',
  'save-scene',
  'improve-episode',
  'improve-scene',
  'move-scene',
  'delete-scene',
  'improve-issue',
]);
</script>
