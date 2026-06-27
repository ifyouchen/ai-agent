<template>
  <div class="creation-view creation-project-library">
    <header class="creation-header">
      <div>
        <h1>作品库</h1>
        <p>按作品阶段管理写作、改写、短剧质检和导出，不再让入口散在各处。</p>
      </div>
      <div class="creation-row">
        <button class="creation-secondary-btn" type="button" @click="$emit('import')">
          <Upload :size="17" /> 导入文本/DOCX
        </button>
        <button class="creation-primary-btn" type="button" @click="$emit('create')">
          <Plus :size="17" /> 新建作品
        </button>
      </div>
    </header>

    <div class="creation-tabs">
      <button v-for="f in filters" :key="f.value" :class="{ active: filter === f.value }" type="button" @click="$emit('update:filter', f.value)">
        {{ f.label }}
      </button>
    </div>

    <div v-if="filter !== 'history'" class="creation-toolbar">
      <div class="creation-filters">
        <label>状态<CreationSelect :model-value="statusFilter" :options="statusOptions" @update:model-value="$emit('update:statusFilter', $event)" /></label>
        <label>排序<CreationSelect :model-value="sortOrder" :options="sortOptions" @update:model-value="$emit('update:sortOrder', $event)" /></label>
      </div>
      <form class="creation-search" @submit.prevent="$emit('search')">
        <input :value="searchInput" type="search" placeholder="搜索作品标题" @input="$emit('update:searchInput', $event.target.value)" />
        <button class="creation-primary-btn" type="submit"><Search :size="16" /> 搜索</button>
        <button v-if="searchActive" class="creation-secondary-btn" type="button" @click="$emit('clear-search')">清空</button>
      </form>
    </div>

    <slot name="tasks"></slot>
    <slot v-if="filter === 'history'" name="history"></slot>

    <section v-if="filter !== 'history'" class="creation-panel creation-library-panel">
      <div class="creation-section-title">
        <div>
          <h2>全部作品</h2>
          <p>{{ projects.length }} 个结果 · 主按钮会跟随作品阶段变化</p>
        </div>
      </div>
      <div v-if="loading" class="creation-empty">正在加载作品...</div>
      <div v-else-if="!projects.length" class="creation-empty">暂无作品</div>
      <article v-for="project in projects" v-else :key="project.id" class="creation-library-row">
        <div class="creation-library-main">
          <span class="creation-type-tag" :class="`type-${project.type || 'unknown'}`">{{ project.typeLabel || project.type }}</span>
          <div>
            <h3>{{ project.title }}</h3>
            <p>{{ project.description || '还没有简介' }}</p>
            <small>{{ project.chapterCount || 0 }} 章 · {{ project.scriptDraftCount || 0 }} 个脚本 · 更新 {{ compactDate(project.updatedAt) }}</small>
          </div>
        </div>
        <div class="creation-library-state">
          <span>{{ project.workflowStage || project.status || '写作中' }}</span>
          <strong v-if="project.latestScriptDraftId">质检 {{ project.qualityScore || 0 }}</strong>
          <strong v-else>正文 {{ project.latestChapter?.wordCount || 0 }} 字</strong>
        </div>
        <div class="creation-library-actions">
          <router-link class="creation-primary-btn compact" :to="project.nextActionUrl || `/creation/projects/${project.id}/editor`">
            <ArrowRight :size="16" /> {{ project.nextAction || '继续写作' }}
          </router-link>
          <details class="creation-more-menu">
            <summary><MoreHorizontal :size="18" /></summary>
            <button type="button" @click="$emit('script', project)">转短剧</button>
            <button type="button" @click="$emit('export', { project, format: 'docx' })">导出 Word</button>
            <button type="button" class="danger" @click="$emit('delete', project)">删除作品</button>
          </details>
        </div>
      </article>
    </section>
  </div>
</template>

<script setup>
import { ArrowRight, MoreHorizontal, Plus, Search, Upload } from 'lucide-vue-next';
import CreationSelect from './CreationSelect.vue';

defineProps({
  projects: { type: Array, default: () => [] },
  filters: { type: Array, required: true },
  filter: { type: String, required: true },
  statusOptions: { type: Array, required: true },
  statusFilter: { type: String, required: true },
  sortOptions: { type: Array, required: true },
  sortOrder: { type: String, required: true },
  searchInput: { type: String, default: '' },
  searchActive: Boolean,
  loading: Boolean,
});

defineEmits([
  'create',
  'import',
  'script',
  'export',
  'delete',
  'search',
  'clear-search',
  'update:filter',
  'update:statusFilter',
  'update:sortOrder',
  'update:searchInput',
]);

function compactDate(value) {
  if (!value) return '未知';
  return String(value).slice(0, 10);
}
</script>
