<template>
  <div class="creation-view creation-workbench-home">
    <header class="creation-header creation-workbench-hero">
      <div>
        <h1>今日创作台</h1>
        <p>从作品、任务和短剧草稿里自动推导下一步，把写作、改写、质检和导出串起来。</p>
      </div>
      <div class="creation-row">
        <button class="creation-secondary-btn" type="button" @click="$emit('import')">
          <Upload :size="17" /> 导入文本/DOCX
        </button>
        <button class="creation-primary-btn" type="button" @click="$emit('create', 'long_novel')">
          <Plus :size="17" /> 新建作品
        </button>
      </div>
    </header>

    <section class="creation-command-center creation-flow-board">
      <div class="creation-flow-main">
        <span class="creation-kicker">当前焦点</span>
        <h2>{{ focusProject?.title || focusTitle }}</h2>
        <p>{{ focusProject ? `${focusProject.workflowStage || '写作中'} · ${focusProject.description || '继续推进这个作品。'}` : focusHint }}</p>
        <div class="creation-row">
          <router-link v-if="focusProject?.nextActionUrl" class="creation-primary-btn" :to="focusProject.nextActionUrl">
            <ArrowRight :size="17" /> {{ focusProject.nextAction || '继续' }}
          </router-link>
          <button v-else class="creation-primary-btn" type="button" @click="$emit('create', 'long_novel')">
            <Plus :size="17" /> 创建第一个作品
          </button>
          <router-link class="creation-secondary-btn" to="/creation/projects">
            <Library :size="17" /> 作品库
          </router-link>
        </div>
      </div>
      <div class="creation-command-stats" aria-label="创作概览">
        <div v-for="item in overviewItems" :key="item.label" class="creation-command-stat">
          <component :is="item.icon" :size="18" />
          <strong>{{ item.value }}</strong>
          <span>{{ item.label }}</span>
        </div>
      </div>
    </section>

    <section class="creation-home-grid">
      <div class="creation-panel creation-next-panel">
        <div class="creation-section-title">
          <div>
            <h2>最近作品</h2>
            <p>主按钮永远指向最合理的下一步。</p>
          </div>
        </div>
        <div v-if="loading" class="creation-empty">正在读取创作状态...</div>
        <div v-else-if="!recentProjects.length" class="creation-empty">还没有作品，先新建或导入一篇。</div>
        <article v-for="project in recentProjects" v-else :key="project.id" class="creation-next-row">
          <div>
            <span class="creation-type-tag" :class="`type-${project.type || 'unknown'}`">{{ project.typeLabel || project.type }}</span>
            <h3>{{ project.title }}</h3>
            <p>{{ project.workflowStage || project.status }} · {{ project.chapterCount || 0 }} 章 · {{ project.scriptDraftCount || 0 }} 个脚本</p>
          </div>
          <router-link class="creation-primary-btn compact" :to="project.nextActionUrl || `/creation/projects/${project.id}/editor`">
            {{ project.nextAction || '继续写作' }}
          </router-link>
        </article>
      </div>

      <aside class="creation-panel creation-task-dock">
        <div class="creation-section-title">
          <div>
            <h2>运行中任务</h2>
            <p>生成和改写状态集中在这里。</p>
          </div>
        </div>
        <div v-if="!activeTasks.length" class="creation-empty">暂无运行中任务</div>
        <article v-for="task in activeTasks" :key="`${task.kind}-${task.taskId}`" class="creation-task-dock-item">
          <div>
            <strong>{{ task.title }}</strong>
            <span>{{ task.currentStep || statusLabel(task.status) }}</span>
          </div>
          <div class="creation-task-progress" :aria-label="`任务进度 ${task.progress || 0}%`">
            <i :style="{ width: `${task.progress || 0}%` }"></i>
          </div>
          <router-link class="creation-secondary-btn compact" :to="task.openUrl || '/creation/projects'">查看</router-link>
        </article>
      </aside>
    </section>

    <section class="creation-grid creation-action-strip">
      <button v-for="item in actionItems" :key="item.key" class="creation-action-card" type="button" @click="item.action">
        <span class="creation-action-icon"><component :is="item.icon" :size="20" /></span>
        <strong>{{ item.title }}</strong>
        <small>{{ item.desc }}</small>
      </button>
    </section>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { ArrowRight, BookOpen, ClipboardCheck, FileInput, FileText, Library, Plus, Sparkles, Upload } from 'lucide-vue-next';

const props = defineProps({
  summary: { type: Object, default: () => ({}) },
  loading: Boolean,
});

const emit = defineEmits(['create', 'import']);

const overview = computed(() => props.summary?.overview || {});
const recentProjects = computed(() => props.summary?.recentProjects || []);
const activeTasks = computed(() => props.summary?.activeTasks || []);
const focusProject = computed(() => recentProjects.value[0] || null);
const focusTitle = computed(() => activeTasks.value.length ? '任务正在推进' : '先创建一个可持续迭代的作品');
const focusHint = computed(() => activeTasks.value.length ? '任务完成后会回到作品、改写或短剧结果。' : '从长篇、短篇或导入开始，后续都会进入同一个闭环。');

const overviewItems = computed(() => [
  { label: '作品', value: overview.value.projectCount || 0, icon: BookOpen },
  { label: '进行中', value: overview.value.runningTaskCount || 0, icon: Sparkles },
  { label: '脚本草稿', value: overview.value.scriptDraftCount || 0, icon: FileText },
  { label: '可导出', value: overview.value.exportReadyCount || 0, icon: ClipboardCheck },
]);

const actionItems = [
  { key: 'long', icon: BookOpen, title: '新建长篇', desc: '设定、人物、大纲、章节续写', action: () => emit('create', 'long_novel') },
  { key: 'short', icon: FileText, title: '新建短篇', desc: '情绪目标、反转、爆点和结尾', action: () => emit('create', 'short_story') },
  { key: 'import', icon: FileInput, title: '导入小说', desc: '支持粘贴文本和 DOCX 文件', action: () => emit('import') },
  { key: 'library', icon: Library, title: '进入作品库', desc: '批量管理写作、改写和短剧草稿', action: () => window.location.hash = '#/creation/projects' },
];

function statusLabel(status) {
  return {
    pending: '排队中',
    running: '处理中',
    completed: '已完成',
    failed: '失败',
    canceled: '已取消',
  }[status] || status || '未知状态';
}
</script>
