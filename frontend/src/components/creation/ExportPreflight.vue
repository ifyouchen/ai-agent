<template>
  <div class="creation-export-preflight">
    <div class="creation-flow-steps">
      <span class="done">分场编辑</span>
      <span :class="{ active: issueCount > 0, done: issueCount === 0 }">导出前检查</span>
      <span :class="{ active: exported }">生成文件</span>
      <span>下载交付</span>
    </div>
    <ScriptExportView
      :form="form"
      :format-options="formatOptions"
      :scope-options="scopeOptions"
      :episode-options="episodeOptions"
      :scene-options="sceneOptions"
      :checks="checks"
      :issue-count="issueCount"
      :exported="exported"
      :preview="preview"
      :busy="busy"
      :downloading="downloading"
      :generating="generating"
      :auto-fixing="autoFixing"
      :feedback="feedback"
      :feedback-tone="feedbackTone"
      @generate="$emit('generate')"
      @download="$emit('download')"
      @back="$emit('back')"
      @auto-fix="$emit('auto-fix')"
    />
  </div>
</template>

<script setup>
import ScriptExportView from './ScriptExportView.vue';

defineProps({
  form: { type: Object, required: true },
  formatOptions: { type: Array, required: true },
  scopeOptions: { type: Array, required: true },
  episodeOptions: { type: Array, required: true },
  sceneOptions: { type: Array, required: true },
  checks: { type: Array, required: true },
  issueCount: { type: Number, required: true },
  exported: { type: Object, default: null },
  preview: { type: String, default: '' },
  busy: Boolean,
  downloading: Boolean,
  generating: Boolean,
  autoFixing: Boolean,
  feedback: { type: String, default: '' },
  feedbackTone: { type: String, default: 'info' },
});

defineEmits(['download', 'generate', 'back', 'auto-fix']);
</script>
