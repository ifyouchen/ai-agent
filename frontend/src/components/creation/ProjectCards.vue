<template>
  <section class="creation-panel">
    <div class="creation-section-title">
      <h2>{{ title }}</h2>
    </div>
    <div v-if="!projects?.length" class="creation-empty">暂无作品</div>
    <div v-else class="creation-projects">
      <article v-for="project in projects" :key="project.id" class="creation-project-card">
        <span class="creation-type-tag" :class="`type-${project.type || 'unknown'}`">{{ project.typeLabel || project.type }}</span>
        <h3>{{ project.title }}</h3>
        <p>{{ project.description || '还没有简介' }}</p>
        <small>{{ project.status || 'writing' }} · {{ project.chapterCount || 0 }}章 · {{ project.scriptDraftCount || 0 }}个脚本</small>
        <div class="creation-row">
          <a :href="`#/creation/projects/${project.id}/editor`">继续写作</a>
          <a v-if="project.latestScriptDraftId" :href="`#/creation/scripts/${project.latestScriptDraftId}`">查看脚本</a>
          <button type="button" :disabled="convertingProjectId === project.id" @click="$emit('script', project)">
            {{ convertingProjectId === project.id ? '转短剧中...' : '转短剧' }}
          </button>
        </div>
        <div class="creation-export-controls">
          <CreationSelect
            :model-value="selectedFormat(project)"
            :options="exportOptions"
            :disabled="exportingProjectId === project.id"
            @update:model-value="value => setFormat(project, value)"
          />
          <button
            class="creation-secondary-btn"
            type="button"
            :disabled="exportingProjectId === project.id"
            @click="$emit('export', { project, format: selectedFormat(project) })"
          >
            {{ exportingProjectId === project.id ? '导出中...' : '导出' }}
          </button>
          <button
            class="creation-danger-btn"
            type="button"
            :disabled="deletingProjectId === project.id || convertingProjectId === project.id || exportingProjectId === project.id"
            @click="$emit('delete', project)"
          >
            {{ deletingProjectId === project.id ? '删除中...' : '删除' }}
          </button>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup>
import { reactive } from 'vue';
import CreationSelect from './CreationSelect.vue';

defineProps({
  title: String,
  projects: { type: Array, default: () => [] },
  convertingProjectId: [String, Number],
  exportingProjectId: [String, Number],
  deletingProjectId: [String, Number],
});

defineEmits(['script', 'export', 'delete']);

const projectExportFormats = reactive({});
const exportOptions = [
  { value: 'md', label: 'MD' },
  { value: 'docx', label: 'Word' },
  { value: 'html', label: 'HTML' },
  { value: 'pdf', label: 'PDF' },
  { value: 'txt', label: 'TXT' },
];

function selectedFormat(project) {
  return projectExportFormats[project.id] || 'md';
}

function setFormat(project, value) {
  projectExportFormats[project.id] = value;
}
</script>
