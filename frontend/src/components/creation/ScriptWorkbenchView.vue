<template>
  <div class="creation-workbench script-workbench">
    <aside class="creation-side">
      <router-link class="creation-back-link" to="/creation/projects">返回作品库</router-link>
      <h2>{{ draft?.title || '短剧改编' }}</h2>
      <button class="creation-primary-btn full creation-assist-btn" type="button" :disabled="!!busy" @click="$emit('quality-check')">
        <span v-if="busy === 'draft:quality'" class="creation-spinner"></span>
        {{ busy === 'draft:quality' ? '质检中...' : '质检全稿' }}
      </button>
      <div v-if="draft?.sourceChapters?.length" class="creation-side-section">
        <h3>原文章节</h3>
        <details v-for="source in draft.sourceChapters" :key="source.id" class="creation-source-item">
          <summary>{{ source.title }} · {{ source.wordCount }}字</summary>
          <p>{{ source.preview }}</p>
        </details>
      </div>
      <template v-for="ep in draft?.episodes || []" :key="ep.id">
        <h3>第{{ ep.episodeNo }}集</h3>
        <button class="creation-secondary-btn full compact creation-assist-btn" type="button" :disabled="!!busy" @click="$emit('add-scene', ep)">
          <span v-if="busy === `add:${ep.id}`" class="creation-spinner dark"></span>
          {{ busy === `add:${ep.id}` ? '新增中...' : '新增场次' }}
        </button>
        <button
          v-for="scene in ep.scenes"
          :key="scene.id"
          class="creation-list-btn"
          :class="{ active: scene.id === currentScene?.id }"
          type="button"
          :disabled="!!busy"
          @click="$emit('select-scene', ep, scene)"
        >
          第{{ scene.sceneNo }}场
        </button>
      </template>
    </aside>
    <main v-if="currentScene" class="creation-editor script-editor">
      <input v-model="sceneForm.sceneTitle" class="creation-title-input" />
      <label>场景<input v-model="sceneForm.location" /></label>
      <label>人物<input v-model="sceneForm.characters" /></label>
      <label>本场功能<input v-model="sceneForm.sceneFunction" /></label>
      <label>画面<textarea v-model="sceneForm.visualAction"></textarea></label>
      <label>旁白<textarea v-model="sceneForm.narration"></textarea></label>
      <label>对白<textarea v-model="sceneForm.dialogue"></textarea></label>
      <label>表演/镜头<textarea v-model="sceneForm.performanceCameraNote"></textarea></label>
      <label>钩子<textarea v-model="sceneForm.hook"></textarea></label>
      <footer>
        <span>{{ currentEpisode?.coreHook }}</span>
        <button type="button" :disabled="!!busy" @click="$emit('save-scene')">
          {{ busy === 'scene:save' ? '保存中...' : '保存场次' }}
        </button>
      </footer>
    </main>
    <aside class="creation-ai">
      <h3>短剧助手</h3>
      <div v-if="busy" class="creation-assistant-status" aria-live="polite">
        <span class="creation-spinner dark"></span>
        <div>
          <strong>{{ actionTitle }}</strong>
          <small>处理中，请稍候。完成后会自动刷新当前内容。</small>
        </div>
      </div>
      <button
        v-for="action in sceneActions"
        :key="action.key"
        class="creation-assist-btn"
        type="button"
        :disabled="!!busy"
        @click="$emit(action.event, action.payload)"
      >
        <span v-if="busy === action.busy" class="creation-spinner dark"></span>
        {{ busy === action.busy ? action.loadingLabel : action.label }}
      </button>
      <router-link class="creation-primary-btn full" :to="`/creation/scripts/${draftId}/export`">导出预览</router-link>
      <div v-if="qualityReport" class="creation-quality">
        <div class="creation-quality-head">
          <strong>评分 {{ qualityReport.totalScore }}</strong>
          <button class="creation-secondary-btn compact" type="button" :disabled="!!busy" @click="$emit('quality-check')">重新质检</button>
        </div>
        <div v-if="qualityIssueList.length" class="creation-quality-issues">
          <div v-for="issue in qualityIssueList" :key="issue" class="creation-quality-issue" :class="{ optimized: isIssueOptimized(issue) }">
            <p>{{ issue }}</p>
            <button
              class="creation-secondary-btn compact"
              type="button"
              :class="{ optimized: isIssueOptimized(issue) }"
              :disabled="!!busy || isIssueOptimized(issue)"
              @click="$emit('improve-issue', issue)"
            >
              <span v-if="busy === 'quality:fix' && fixingIssue === issue" class="creation-spinner dark"></span>
              {{ isIssueOptimized(issue) ? '已优化' : 'AI 优化' }}
            </button>
          </div>
        </div>
      </div>
      <div v-if="adaptationPlanEntries.length" class="creation-quality">
        <strong>改编方案</strong>
        <p v-for="item in adaptationPlanEntries" :key="item.label">{{ item.label }}：{{ item.value }}</p>
      </div>
    </aside>
  </div>
</template>

<script setup>
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

const sceneActions = [
  { key: 'episode', event: 'improve-episode', busy: 'episode:rewrite', label: '重写本集', loadingLabel: '重写本集中...' },
  { key: 'rewrite', event: 'improve-scene', payload: 'rewrite', busy: 'scene:rewrite', label: '重写本场', loadingLabel: '重写本场中...' },
  { key: 'hook', event: 'improve-scene', payload: 'hook', busy: 'scene:hook', label: '补钩子', loadingLabel: '补钩子中...' },
  { key: 'dialogue', event: 'improve-scene', payload: 'dialogue', busy: 'scene:dialogue', label: '对白口语化', loadingLabel: '对白优化中...' },
  { key: 'externalize', event: 'improve-scene', payload: 'externalize', busy: 'scene:externalize', label: '心理外化', loadingLabel: '心理外化中...' },
  { key: 'move-up', event: 'move-scene', payload: 'up', busy: 'scene:move-up', label: '上移场次', loadingLabel: '上移中...' },
  { key: 'move-down', event: 'move-scene', payload: 'down', busy: 'scene:move-down', label: '下移场次', loadingLabel: '下移中...' },
  { key: 'delete', event: 'delete-scene', busy: 'scene:delete', label: '删除场次', loadingLabel: '删除中...' },
];
</script>
