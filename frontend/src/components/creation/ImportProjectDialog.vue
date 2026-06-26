<template>
  <div class="creation-modal" @click.self="$emit('close')">
    <form class="creation-dialog wide" @submit.prevent="$emit('preview')">
      <div class="creation-dialog-head">
        <div>
          <h2>导入小说文本</h2>
          <p>上传 DOCX/TXT，或直接粘贴正文，系统会先解析章节再导入。</p>
        </div>
        <button class="creation-icon-btn" type="button" :disabled="busy" @click="$emit('close')">×</button>
      </div>
      <input v-model="form.title" required placeholder="作品名" :disabled="busy" />
      <label class="creation-file-field">
        <span>上传文件</span>
        <span class="creation-file-picker">
          <input type="file" accept=".txt,.md,.doc,.docx" :disabled="busy" @change="$emit('file-change', $event)" />
          <span class="creation-file-button">选择文件</span>
          <span class="creation-file-name">{{ fileName || '支持 .txt / .md / .doc / .docx' }}</span>
        </span>
      </label>
      <textarea v-model="form.content" rows="12" placeholder="或直接粘贴正文" :disabled="busy"></textarea>
      <div v-if="previewLoading || confirming" class="creation-import-loading" aria-live="polite">
        <span class="creation-spinner dark"></span>
        <div>
          <strong>{{ confirming ? '正在导入作品' : '正在解析章节' }}</strong>
          <p>{{ confirming ? '作品库即将更新，并自动进入创作页面。' : '正在读取文件结构、识别章节和正文，请稍候。' }}</p>
        </div>
      </div>
      <section v-if="preview" class="creation-import-preview">
        <strong>{{ preview.detectedTypeLabel }} · {{ preview.wordCount }} 字 · {{ preview.chapterCount }} 章</strong>
        <p v-if="preview.truncated">仅展示前 20 章预览。</p>
        <div v-for="chapter in preview.chapters" :key="chapter.chapterNo">
          第{{ chapter.chapterNo }}章 · {{ chapter.title }} · {{ chapter.wordCount }}字
        </div>
      </section>
      <div class="creation-row">
        <button class="creation-secondary-btn" type="submit" :disabled="busy">
          <span v-if="previewLoading" class="creation-spinner dark"></span>
          {{ previewLoading ? '解析中...' : '解析预览' }}
        </button>
        <button class="creation-primary-btn" type="button" :disabled="!preview || busy" @click="$emit('confirm')">
          <span v-if="confirming" class="creation-spinner"></span>
          {{ confirming ? '导入中...' : '确认导入' }}
        </button>
      </div>
    </form>
  </div>
</template>

<script setup>
defineProps({
  form: { type: Object, required: true },
  preview: { type: Object, default: null },
  fileName: { type: String, default: '' },
  busy: Boolean,
  previewLoading: Boolean,
  confirming: Boolean,
});

defineEmits(['close', 'preview', 'file-change', 'confirm']);
</script>
