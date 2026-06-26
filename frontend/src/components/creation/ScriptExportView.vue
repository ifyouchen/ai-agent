<template>
  <div class="creation-view">
    <header class="creation-header">
      <div>
        <h1>导出预览</h1>
        <p>生成 Markdown、Word、HTML、PDF 或 TXT 导出内容。</p>
      </div>
      <div class="creation-row">
        <button class="creation-secondary-btn" type="button" :disabled="busy" @click="$emit('download')">
          <span v-if="downloading" class="creation-spinner dark"></span>
          {{ downloading ? '下载中...' : '下载文件' }}
        </button>
        <button class="creation-primary-btn" type="button" :disabled="busy" @click="$emit('generate')">
          <span v-if="generating" class="creation-spinner"></span>
          {{ generating ? '生成中...' : '生成导出内容' }}
        </button>
      </div>
    </header>
    <div v-if="feedback" class="creation-export-feedback" :class="`tone-${feedbackTone}`" aria-live="polite">
      {{ feedback }}
    </div>
    <section class="creation-export">
      <aside class="creation-panel">
        <label>格式<CreationSelect v-model="form.format" :options="formatOptions" /></label>
        <label>范围<CreationSelect v-model="form.scope" :options="scopeOptions" /></label>
        <label v-if="form.scope === 'episode'">集数<CreationSelect v-model="form.episodeNo" :options="episodeOptions" /></label>
        <label v-if="form.scope === 'scene'">场次<CreationSelect v-model="form.sceneId" :options="sceneOptions" /></label>
        <label><input v-model="form.includeQualityReport" type="checkbox" /> 包含质量报告</label>
        <label><input v-model="form.includeAdaptationPlan" type="checkbox" /> 包含改编方案</label>
        <label><input v-model="form.includeCharacterTable" type="checkbox" /> 包含人物表</label>
        <label><input v-model="form.includeSceneDirectory" type="checkbox" /> 包含场次目录</label>
        <div class="creation-check-list">
          <strong>导出前检查 · {{ issueCount }} 项待处理</strong>
          <p v-for="item in checks" :key="item.label" :class="{ pass: item.pass }">{{ item.pass ? '通过' : '待修复' }}：{{ item.label }}</p>
        </div>
        <button class="creation-secondary-btn full" type="button" @click="$emit('back')">返回修复</button>
        <button class="creation-secondary-btn full" type="button" :disabled="autoFixing || busy" @click="$emit('auto-fix')">
          <span v-if="autoFixing" class="creation-spinner dark"></span>
          {{ autoFixing ? '修复中...' : 'AI 自动修复可修复项' }}
        </button>
      </aside>
      <main class="creation-panel"><pre>{{ exported?.content || preview }}</pre></main>
    </section>
  </div>
</template>

<script setup>
import CreationSelect from './CreationSelect.vue';

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
