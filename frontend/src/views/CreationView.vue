<template>
  <WorkbenchHome
    v-if="mode === 'home'"
    :summary="workbenchSummary"
    :loading="projectsLoading"
    @create="openCreateDialog"
    @import="openImportDialog"
  />

  <ProjectLibrary
    v-else-if="mode === 'projects'"
    :projects="filteredProjects"
    :filters="filters"
    :filter="filter"
    :status-options="statusOptions"
    :status-filter="statusFilter"
    :sort-options="sortOptions"
    :sort-order="sortOrder"
    :search-input="searchInput"
    :search-active="hasSearchQuery"
    :loading="projectsLoading"
    @create="showCreate = true"
    @import="openImportDialog"
    @script="startScript"
    @export="downloadProjectExport"
    @delete="deleteProject"
    @search="applyProjectSearch"
    @clear-search="clearProjectSearch"
    @update:filter="filter = $event"
    @update:status-filter="statusFilter = $event"
    @update:sort-order="sortOrder = $event"
    @update:search-input="searchInput = $event"
  >
    <template #tasks>
      <CreationTaskCard
        v-if="filter !== 'history' && activeTask"
        :task="activeTask"
        :title="taskPanelTitle"
        :subtitle="activeTaskName"
        :status-label="activeTaskStatusLabel"
        :progress="activeTaskProgress"
        :open-label="activeTaskOpenLabel"
        :can-open="taskCanOpen"
        :can-cancel="taskCanCancel"
        :can-retry="taskCanRetry"
        :busy="taskActionBusy"
        :token-usage="activeTask.tokenUsage"
        show-dismiss
        @open="openActiveTask"
        @refresh="refreshTask"
        @cancel="cancelTask"
        @retry="retryTask"
        @dismiss="dismissTask"
      />
      <CreationTaskCard
        v-if="filter !== 'history' && activeRewriteTask"
        :task="activeRewriteTask"
        :title="rewriteTaskPanelTitle"
        subtitle="改小说三栏对照"
        :status-label="rewriteTaskStatusLabel"
        :progress="rewriteTaskProgress"
        :open-label="rewriteTaskOpenLabel"
        :can-open="rewriteTaskCanOpen"
        :can-cancel="rewriteTaskCanCancel"
        :can-retry="rewriteTaskCanRetry"
        :busy="rewriteTaskActionBusy"
        show-dismiss
        @open="openActiveRewriteTask"
        @refresh="refreshRewriteTask"
        @cancel="cancelActiveRewriteTask"
        @retry="retryActiveRewriteTask"
        @dismiss="dismissRewriteTask"
      />
    </template>
    <template #history>
      <TaskHistoryPanel
        :badge-text="taskHistoryBadgeText"
        :can-expand="taskHistoryCanExpand"
        :toggle-label="taskHistoryToggleLabel"
        :loading="taskHistoryLoading"
        :search-input="taskHistorySearchInput"
        :visible-tasks="visibleTaskHistory"
        :expanded="taskHistoryExpanded"
        :hidden-count="taskHistoryHiddenCount"
        :search-active="taskHistorySearchActive"
        :busy-key="historyTaskBusyKey"
        :busy-action="historyTaskBusyAction"
        :task-key-for="historyTaskKey"
        :status-label-for="taskStatusLabelFor"
        :progress-for="taskProgressFor"
        :can-open="historyTaskCanOpen"
        :can-cancel="historyTaskCanCancel"
        :can-retry="historyTaskCanRetry"
        @toggle="toggleTaskHistory"
        @refresh="loadTaskHistory"
        @update:search-input="updateTaskHistorySearch"
        @clear-search="clearTaskHistorySearch"
        @restore="restoreHistoryTask"
        @open="openHistoryTask"
        @cancel="cancelHistoryTask"
        @retry="retryHistoryTask"
      />
    </template>
  </ProjectLibrary>

  <WritingEditor v-else-if="mode === 'editor'">
  <div class="creation-workbench creation-writing-workbench has-ai-config" :class="{ 'ai-config-collapsed': aiConfigCollapsed }">
    <aside class="creation-side">
      <div class="creation-side-top">
        <router-link class="creation-back-link" to="/creation/projects">返回作品库</router-link>
        <h2>{{ project?.title || '作品编辑器' }}</h2>
        <div class="creation-project-meta">
          <span>{{ project?.typeLabel || project?.type || '作品' }}</span>
          <span>{{ chapters.length }} 章</span>
          <span>{{ wordCount }} 字</span>
        </div>
      </div>
      <CreationTaskCard
        v-if="activeTask"
        compact
        :task="activeTask"
        :title="taskPanelTitle"
        :status-label="activeTaskStatusLabel"
        :progress="activeTaskProgress"
        :open-label="activeTaskOpenLabel"
        :can-open="taskCanOpen"
        :can-cancel="taskCanCancel"
        :can-retry="taskCanRetry"
        :busy="taskActionBusy"
        @open="openActiveTask"
        @refresh="refreshTask"
        @cancel="cancelTask"
        @retry="retryTask"
      />
      <CreationTaskCard
        v-if="activeRewriteTask"
        compact
        :task="activeRewriteTask"
        :title="rewriteTaskPanelTitle"
        :status-label="rewriteTaskStatusLabel"
        :progress="rewriteTaskProgress"
        :open-label="rewriteTaskOpenLabel"
        :can-open="rewriteTaskCanOpen"
        :can-cancel="rewriteTaskCanCancel"
        :can-retry="rewriteTaskCanRetry"
        :busy="rewriteTaskActionBusy"
        @open="openActiveRewriteTask"
        @refresh="refreshRewriteTask"
        @cancel="cancelActiveRewriteTask"
        @retry="retryActiveRewriteTask"
      />
      <div class="creation-side-section">
        <div class="creation-side-section-head">
          <h3>创作资产</h3>
          <span>{{ assetTypes.length }} 项</span>
        </div>
        <div class="creation-asset-grid">
          <button
            v-for="asset in assetTypes"
            :key="asset.type"
            class="creation-list-btn creation-asset-chip"
            :class="{ active: editorPanel === 'asset' && activeAssetType === asset.type }"
            type="button"
            @click="selectAsset(asset.type)"
          >
            {{ asset.label }}
          </button>
        </div>
      </div>
      <div class="creation-side-section">
        <div class="creation-side-section-head">
          <h3>章节</h3>
          <span>{{ chapters.length }} 章</span>
        </div>
        <button class="creation-primary-btn full creation-add-chapter-btn" type="button" :disabled="chapterAdding" @click="addChapter">
          {{ chapterAdding ? '新增中...' : '新增章节' }}
        </button>
        <div
          v-for="chapter in chapters"
          :key="chapter.id"
          class="creation-list-btn creation-chapter-btn"
          :class="{ active: editorPanel === 'chapter' && chapter.id === currentChapter?.id, draft: hasChapterDraft(chapter) }"
          :title="chapterDisplayTitle(chapter)"
          role="button"
          tabindex="0"
          @click="selectChapter(chapter)"
          @keydown.enter.prevent="selectChapter(chapter)"
          @keydown.space.prevent="selectChapter(chapter)"
        >
          <span class="creation-chapter-main">
            <span class="creation-chapter-title">{{ chapterDisplayTitle(chapter) }}</span>
          </span>
          <span v-if="hasChapterDraft(chapter)" class="creation-draft-dot" title="有未保存草稿"></span>
          <button
            class="creation-chapter-delete"
            type="button"
            title="删除章节"
            :disabled="chapters.length <= 1 || chapterDeletingId === chapter.id"
            @click.stop="deleteChapter(chapter)"
          >
            {{ chapterDeletingId === chapter.id ? '...' : '×' }}
          </button>
        </div>
      </div>
      <div v-if="project?.scriptDrafts?.length" class="creation-side-section">
        <h3>短剧草稿</h3>
        <router-link v-for="draftItem in project.scriptDrafts" :key="draftItem.id" class="creation-list-btn" :to="`/creation/scripts/${draftItem.id}`">
          {{ draftItem.title }}
        </router-link>
      </div>
    </aside>
    <main v-if="editorPanel === 'asset'" class="creation-editor creation-writing-editor">
      <div class="creation-asset-head">
        <div>
          <span class="creation-editor-kicker">创作资产</span>
          <h1>{{ activeAssetLabel }}</h1>
          <p>{{ activeAssetHint }}</p>
        </div>
        <button class="creation-secondary-btn" type="button" @click="openActionPanel(activeAssetType)">AI 配置/生成</button>
      </div>
      <label class="creation-asset-instruction">
        <span>你的要求</span>
        <textarea v-model="assetForm.instruction" rows="4" :placeholder="activeAssetInstructionPlaceholder"></textarea>
      </label>
      <textarea v-model="assetForm.content" class="creation-manuscript" :placeholder="activeAssetPlaceholder"></textarea>
      <footer>
        <span>{{ activeAssetLabel }} · {{ assetWordCount }} 字</span>
        <button type="button" :disabled="assetSaving" @click="saveAsset">{{ assetSaving ? '保存中...' : `保存${activeAssetLabel}` }}</button>
      </footer>
    </main>
    <main v-else class="creation-editor creation-writing-editor">
      <div class="creation-editor-headbar">
        <div>
          <span class="creation-editor-kicker">章节写作</span>
          <strong>{{ currentChapterLabel }}</strong>
          <p>{{ currentChapterStatusLabel }}</p>
        </div>
        <div class="creation-editor-stats">
          <span>{{ wordCount }} 字</span>
          <span>{{ chapterVersions.length }} 个快照</span>
          <span v-if="currentChapter && hasChapterDraft(currentChapter)">未保存草稿</span>
        </div>
      </div>
      <input v-model="chapterForm.title" class="creation-title-input" placeholder="章节标题" />
      <textarea v-model="chapterForm.content" class="creation-manuscript" placeholder="在这里写小说正文。"></textarea>
      <footer class="creation-editor-footer">
        <span>{{ activeAction?.label || 'AI 助手' }} · {{ promptConfig.global.style }} · {{ promptConfig.global.pace }}</span>
        <button type="button" :disabled="chapterSaving" @click="saveChapter">{{ chapterSaving ? '保存中...' : '保存章节' }}</button>
      </footer>
    </main>
    <aside v-if="activeAction" class="creation-ai-config" :class="{ collapsed: aiConfigCollapsed }">
      <button
        class="creation-ai-config-toggle"
        type="button"
        :title="aiConfigCollapsed ? '展开 AI 配置' : '折叠 AI 配置'"
        @click="aiConfigCollapsed = !aiConfigCollapsed"
      >
        {{ aiConfigCollapsed ? '‹' : '›' }}
      </button>
      <template v-if="aiConfigCollapsed">
        <div class="creation-ai-config-rail">
          <span><strong>{{ activeAction.label }}</strong> · AI 配置</span>
        </div>
      </template>
      <div class="creation-ai-config-body" v-show="!aiConfigCollapsed">
        <div class="creation-ai-config-head">
          <span class="creation-editor-kicker">当前动作</span>
          <strong>{{ activeAction.label }}</strong>
          <small>{{ activeAction.hint }}</small>
        </div>
        <label>
          <span>本次要求</span>
          <textarea v-model="actionForm.instruction" rows="4" :placeholder="activeAction.placeholder"></textarea>
        </label>
        <div class="creation-mini-grid">
          <label>
            <span>文风</span>
            <CreationSelect v-model="promptConfig.global.style" :options="styleOptions" />
          </label>
          <label>
            <span>节奏</span>
            <CreationSelect v-model="promptConfig.global.pace" :options="paceOptions" />
          </label>
        </div>
        <label>
          <span>改写力度</span>
          <CreationSelect v-model="promptConfig.global.rewriteStrength" :options="rewriteStrengthOptions" />
        </label>
        <label>
          <span>保留项</span>
          <input v-model="globalPreserveText" placeholder="剧情、人设、伏笔" @change="syncGlobalLists" />
        </label>
        <label>
          <span>禁止项</span>
          <input v-model="globalAvoidText" placeholder="AI腔、总结腔、过度排比" @change="syncGlobalLists" />
        </label>
        <div class="creation-mini-grid">
          <label>
            <span>动作强度</span>
            <CreationSelect v-model="activeActionConfig.params.strength" :options="actionStrengthOptions" />
          </label>
          <label class="creation-inline-check">
            <input v-model="activeActionConfig.params.keepPlot" type="checkbox" />
            <span>保留剧情</span>
          </label>
        </div>
        <details class="creation-prompt-details">
          <summary>高级提示词</summary>
          <label class="creation-inline-check">
            <input v-model="activeActionConfig.useCustomPrompt" type="checkbox" />
            <span>使用自定义动作模板</span>
          </label>
          <textarea v-model="activeActionConfig.userTemplate" rows="6" placeholder="输入这个动作的高级提示词模板。系统仍会追加安全边界和当前作品上下文。"></textarea>
          <button class="creation-secondary-btn full compact" type="button" @click="restoreActionPrompt">恢复默认提示词</button>
        </details>
        <button class="creation-primary-btn full creation-generate-btn" type="button" :disabled="actionForm.loading" @click="executeAction">
          <span v-if="actionForm.loading" class="creation-spinner"></span>
          {{ actionForm.loading ? '生成中...' : '执行' }}
        </button>
        <button v-if="actionForm.loading" class="creation-secondary-btn full compact creation-stop-btn" type="button" @click="stopActionGeneration">停止生成</button>
        <button class="creation-secondary-btn full compact" type="button" :disabled="actionForm.loading || promptConfigSaving" @click="savePromptConfig">
          {{ promptConfigSaving ? '保存中...' : '保存 AI 配置' }}
        </button>
        <label v-if="actionForm.loading || actionForm.result" class="creation-ai-result creation-draft-result">
          <span>{{ actionResultTitle }}</span>
          <div v-if="actionForm.loading" class="creation-ai-pending">
            <span class="creation-spinner"></span>
            <strong>{{ activeAction.label }}生成中</strong>
            <small>AI 正在处理当前正文和配置，请稍候。生成完成后会进入这里，你再决定是否应用。</small>
          </div>
          <textarea v-else v-model="actionForm.result" rows="9"></textarea>
        </label>
        <div v-if="actionForm.result && actionForm.resultTarget && isAssetAction(actionForm.resultTarget)" class="creation-row">
          <button class="creation-primary-btn" type="button" :disabled="assetSaving" @click="applyAssetResult">
            {{ assetSaving ? '保存中...' : `应用到${assetLabel(actionForm.resultTarget)}` }}
          </button>
          <button class="creation-secondary-btn" type="button" @click="discardActionResult">丢弃草稿</button>
        </div>
        <div v-if="actionForm.result && !isAssetAction(actionForm.resultTarget || activeAction.type) && (actionForm.resultTarget || activeAction.type) !== 'review'" class="creation-row">
          <button class="creation-secondary-btn" type="button" @click="appendActionResult">追加到正文</button>
          <button class="creation-secondary-btn" type="button" @click="replaceChapterWithResult">替换正文</button>
          <button class="creation-secondary-btn" type="button" @click="discardActionResult">丢弃草稿</button>
        </div>
        <div v-if="actionForm.result && (actionForm.resultTarget || activeAction.type) === 'review'" class="creation-row">
          <button class="creation-secondary-btn" type="button" @click="discardActionResult">关闭审查建议</button>
        </div>
      </div>
    </aside>
    <aside class="creation-ai">
      <div class="creation-ai-head">
        <h3>AI 助手</h3>
        <span>{{ aiActions.length + 2 }} 个动作</span>
      </div>
      <button
        v-for="action in aiActions"
        :key="action.type"
        :class="{ active: activeActionType === action.type }"
        type="button"
        :disabled="actionForm.loading"
        @click="openActionPanel(action.type)"
      >
        {{ action.label }}
      </button>
      <button type="button" :disabled="rewriteStarting || rewriteTaskRunning" @click="startRewrite">
        <span v-if="rewriteStarting || rewriteTaskRunning" class="creation-spinner dark"></span>
        {{ rewriteStarting || rewriteTaskRunning ? '改写中...' : '改小说三栏对照' }}
      </button>
      <button class="creation-primary-btn full" type="button" :disabled="scriptConvertingProjectId === project?.id || activeScriptProjectId === project?.id" @click="startScript(project)">
        <span v-if="scriptConvertingProjectId === project?.id || activeScriptProjectId === project?.id" class="creation-spinner"></span>
        {{ scriptConvertingProjectId === project?.id || activeScriptProjectId === project?.id ? '转短剧中...' : '转短剧' }}
      </button>
      <div class="creation-versions">
        <h3>版本快照</h3>
        <button v-for="version in chapterVersions" :key="version.id" type="button" :disabled="versionRestoringId === version.id" @click="restoreVersion(version)">
          {{ versionRestoringId === version.id ? '恢复中...' : `V${version.versionNo} · ${version.note || version.source}` }}
        </button>
        <p v-if="currentChapter && !chapterVersions.length">暂无快照</p>
      </div>
    </aside>
  </div>
  </WritingEditor>

  <RewriteReview
    v-else-if="mode === 'rewrite'"
    :task="rewriteTask"
    :review-summary="rewriteReviewSummary"
    :ready="rewriteReady"
    :accepting="rewriteAccepting"
    :retrying="rewriteRetrying"
    :page-busy="rewritePageActionBusy"
    :can-cancel="rewritePageCanCancel"
    :can-retry="rewritePageCanRetry"
    :progress="rewritePageProgress"
    :segments="rewriteSegments"
    :accepted-count="rewriteAcceptedCount"
    :rejected-count="rewriteRejectedCount"
    :segment-notes="rewriteSegmentNotes"
    :form="rewriteForm"
    :status-label-for="rewriteTaskStatusLabelFor"
    @back="router.push(rewriteBackPath)"
    @refresh="refreshRewritePage"
    @accept="acceptRewrite"
    @cancel="cancelRewritePage"
    @retry="retryRewrite"
    @set-segment-status="setRewriteSegmentStatus"
  />

  <ScriptStudio
    v-else-if="mode === 'script'"
    :draft="draft"
    :draft-id="route.params.draftId"
    :current-episode="currentEpisode"
    :current-scene="currentScene"
    :scene-form="sceneForm"
    :busy="scriptActionBusy"
    :action-title="scriptActionTitle"
    :quality-report="qualityReport"
    :quality-issue-list="qualityIssueList"
    :fixing-issue="qualityFixingIssue"
    :adaptation-plan-entries="adaptationPlanEntries"
    :is-issue-optimized="isQualityIssueOptimized"
    @quality-check="qualityCheck"
    @add-scene="addScene"
    @select-scene="selectScene"
    @save-scene="saveScene"
    @improve-episode="improveEpisode"
    @improve-scene="improveScene"
    @move-scene="moveScene"
    @delete-scene="deleteScene"
    @improve-issue="improveFromQualityIssue"
  />

  <ExportPreflight
    v-else-if="mode === 'export'"
    :form="exportForm"
    :format-options="exportFormatOptions"
    :scope-options="exportScopeOptions"
    :episode-options="exportEpisodeOptions"
    :scene-options="exportSceneOptions"
    :checks="exportChecks"
    :issue-count="exportIssueCount"
    :exported="exported"
    :preview="scriptPreview"
    :busy="exportBusy"
    :downloading="exportDownloading"
    :generating="exportGenerating"
    :auto-fixing="exportAutoFixing"
    :feedback="exportFeedback"
    :feedback-tone="exportFeedbackTone"
    @download="downloadExport"
    @generate="exportDraft"
    @back="router.push(`/creation/scripts/${route.params.draftId}`)"
    @auto-fix="autoFixExportIssues"
  />

  <CreateProjectDialog
    v-if="showCreate"
    :form="createForm"
    :type-options="createTypeOptions"
    :creating="projectCreating"
    @close="showCreate = false"
    @create="createProject"
  />
  <ImportProjectDialog
    v-if="showImport"
    :form="importForm"
    :preview="importPreview"
    :file-name="importFileRef?.name"
    :busy="importBusy"
    :preview-loading="importPreviewLoading"
    :confirming="importConfirming"
    @close="closeImportDialog"
    @preview="previewImport"
    @file-change="onImportFileChange"
    @confirm="confirmImport"
  />
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import CreateProjectDialog from '../components/creation/CreateProjectDialog.vue';
import CreationSelect from '../components/creation/CreationSelect.vue';
import CreationTaskCard from '../components/creation/CreationTaskCard.vue';
import ExportPreflight from '../components/creation/ExportPreflight.vue';
import ImportProjectDialog from '../components/creation/ImportProjectDialog.vue';
import ProjectLibrary from '../components/creation/ProjectLibrary.vue';
import RewriteReview from '../components/creation/RewriteReview.vue';
import ScriptStudio from '../components/creation/ScriptStudio.vue';
import TaskHistoryPanel from '../components/creation/TaskHistoryPanel.vue';
import WorkbenchHome from '../components/creation/WorkbenchHome.vue';
import WritingEditor from '../components/creation/WritingEditor.vue';
import { storyApi } from '../services/storyApi.js';
import { useUiStore } from '../stores/ui.js';

const route = useRoute();
const router = useRouter();
const ui = useUiStore();
const projects = ref([]);
const workbenchSummary = ref({ overview: {}, activeTasks: [], recentProjects: [] });
const projectsLoading = ref(false);
const project = ref(null);
const chapters = ref([]);
const currentChapter = ref(null);
const rewriteTask = ref(null);
const draft = ref(null);
const currentEpisode = ref(null);
const currentScene = ref(null);
const qualityReport = ref(null);
const exported = ref(null);
const exportGenerating = ref(false);
const exportDownloading = ref(false);
const exportAutoFixing = ref(false);
const exportFeedback = ref('');
const exportFeedbackTone = ref('info');
const chapterVersions = ref([]);
const activeTask = ref(null);
const activeRewriteTask = ref(null);
const taskHistory = ref([]);
const taskHistoryExpanded = ref(false);
const taskHistorySearchInput = ref('');
const taskActionBusy = ref('');
const rewriteTaskActionBusy = ref('');
const rewritePageActionBusy = ref('');
const historyTaskBusyKey = ref('');
const historyTaskBusyAction = ref('');
const chapterSaving = ref(false);
const chapterAdding = ref(false);
const assetSaving = ref(false);
const projectCreating = ref(false);
const promptConfigSaving = ref(false);
const versionRestoringId = ref(null);
const rewriteStarting = ref(false);
const rewriteAccepting = ref(false);
const rewriteRetrying = ref(false);
const chapterDeletingId = ref(null);
const scriptConvertingProjectId = ref(null);
const projectExportingId = ref(null);
const projectDeletingId = ref(null);
const scriptActionBusy = ref('');
const qualityFixingIssue = ref('');
const filter = ref('all');
const statusFilter = ref('all');
const sortOrder = ref('updated_desc');
const searchInput = ref('');
const searchQuery = ref('');
const showCreate = ref(false);
const showImport = ref(route.query.import === '1');
const importFileRef = ref(null);
const importPreview = ref(null);
const importPreviewLoading = ref(false);
const importConfirming = ref(false);
const taskHistoryLoading = ref(false);
const editorPanel = ref('chapter');
const activeAssetType = ref('setting');
const assetForm = reactive({ content: '', instruction: '' });
const assetInstructions = reactive({ setting: '', characters: '', outline: '' });
const activeActionType = ref('continue');
const aiConfigCollapsed = ref(false);
const actionForm = reactive({ instruction: '', result: '', resultTarget: '', loading: false, controller: null });
const chapterDrafts = reactive({});
const qualityOptimizedIssues = reactive({});
const promptConfig = reactive(defaultPromptConfig());
const globalPreserveText = ref(promptConfig.global.preserve.join('、'));
const globalAvoidText = ref(promptConfig.global.avoid.join('、'));
const chapterForm = reactive({ title: '', content: '' });
const createForm = reactive({ title: '', type: 'long_novel', description: '' });
const importForm = reactive({ title: '', content: '' });
const rewriteForm = reactive({ instruction: '' });
const sceneForm = reactive({});
let rewritePollTimer = null;
let activeTaskPollTimer = null;
const exportForm = reactive({
  format: 'md',
  scope: 'all',
  episodeNo: null,
  sceneId: null,
  includeQualityReport: true,
  includeAdaptationPlan: true,
  includeCharacterTable: true,
  includeSceneDirectory: true,
});
const statusOptions = [
  { value: 'all', label: '全部状态' },
  { value: 'writing', label: '写作中' },
  { value: 'rewriting', label: '改写中' },
  { value: 'adapting', label: '改编中' },
  { value: 'completed', label: '已完成' },
];
const sortOptions = [
  { value: 'updated_desc', label: '最近更新' },
  { value: 'updated_asc', label: '最早更新' },
  { value: 'title_asc', label: '标题 A-Z' },
];
const styleOptions = ['爽文', '悬疑', '现实向', '轻喜剧', '虐恋'].map(value => ({ value, label: value }));
const paceOptions = ['快节奏', '中等节奏', '慢热'].map(value => ({ value, label: value }));
const rewriteStrengthOptions = ['轻微', '中等', '大幅'].map(value => ({ value, label: value }));
const actionStrengthOptions = ['轻微', '中等', '强'].map(value => ({ value, label: value }));
const exportFormatOptions = [
  { value: 'md', label: 'Markdown' },
  { value: 'docx', label: 'Word' },
  { value: 'html', label: 'HTML' },
  { value: 'pdf', label: 'PDF' },
  { value: 'txt', label: 'TXT' },
];
const exportScopeOptions = [
  { value: 'all', label: '全部' },
  { value: 'episode', label: '选中集' },
  { value: 'scene', label: '选中场' },
];
const createTypeOptions = [
  { value: 'long_novel', label: '长篇小说' },
  { value: 'short_story', label: '短篇故事' },
  { value: 'adaptation', label: '改编项目' },
];
const filters = [
  { value: 'all', label: '全部' },
  { value: 'long_novel', label: '长篇' },
  { value: 'short_story', label: '短篇' },
  { value: 'adaptation', label: '改编' },
  { value: 'short_drama', label: '短剧' },
  { value: 'history', label: '历史任务' },
];
const aiActions = [
  { type: 'setting', label: '编辑设定', hint: '生成或补全世界观、题材基调、爽点机制。', placeholder: '例如：古代科举权谋，男主重生，不要系统。' },
  { type: 'characters', label: '编辑人物', hint: '生成或补全人物欲望、弱点、关系网。', placeholder: '例如：主角克制但有狠劲，反派掌握官府资源。' },
  { type: 'outline', label: '编辑大纲', hint: '生成章节节点、冲突升级和结尾钩子。', placeholder: '例如：先生成前20章，每章一个冲突点。' },
  { type: 'continue', label: '续写', hint: '基于当前章节继续写，不自动保存。', placeholder: '例如：承接电话后的不安，写800字，结尾留钩子。' },
  { type: 'expand', label: '扩写', hint: '扩充动作、环境、冲突和细节。', placeholder: '例如：把这段扩成更有压迫感的对峙。' },
  { type: 'shorten', label: '缩写', hint: '压缩解释和旁白，保留剧情信息。', placeholder: '例如：压到300字，保留反转和关键对白。' },
  { type: 'style', label: '改风格', hint: '调整文风和叙事口吻。', placeholder: '例如：改成短句快节奏，少抒情。' },
  { type: 'polish', label: '润色', hint: '提升文字流畅度和可读性。', placeholder: '例如：保持原剧情，增强动作和情绪。' },
  { type: 'deslop', label: '去 AI 味', hint: '减少模板化、总结腔和空泛形容。', placeholder: '例如：更像真人作者，不要排比和总结。' },
  { type: 'dialogue', label: '对白优化', hint: '让对白更口语、更有冲突。', placeholder: '例如：保留含义，但让两个人互相试探。' },
  { type: 'conflict', label: '强化冲突', hint: '提高压迫、误会、反转或危机密度。', placeholder: '例如：加入当众打脸和证据反转。' },
  { type: 'review', label: '章节审查', hint: '输出问题诊断和修改建议，不改正文。', placeholder: '例如：重点检查前三秒钩子、人设一致性和爽点。' },
];
const scriptActionLabels = {
  'draft:quality': '正在质检全稿',
  'quality:fix': '正在按质检问题优化',
  'episode:rewrite': '正在重写本集',
  'scene:rewrite': '正在重写本场',
  'scene:hook': '正在补充钩子',
  'scene:dialogue': '正在优化对白',
  'scene:externalize': '正在心理外化',
  'scene:move-up': '正在上移场次',
  'scene:move-down': '正在下移场次',
  'scene:delete': '正在删除场次',
  'scene:save': '正在保存场次',
};
const assetTypes = [
  {
    type: 'setting',
    label: '设定',
    hint: '记录世界观、题材基调、主线矛盾、爽点机制和连续性规则。',
    placeholder: '例如：时代背景、核心冲突、金手指/爽点机制、禁忌规则、主要势力...',
    instructionPlaceholder: '例如：古代科举权谋，男主重生，爽点是复试翻盘；不要仙侠，不要系统。',
  },
  {
    type: 'characters',
    label: '人物',
    hint: '记录主角、对手、盟友和关键配角的欲望、弱点、关系与出场功能。',
    placeholder: '例如：主角目标、人物弱点、关系网、对手压迫点、角色弧光...',
    instructionPlaceholder: '例如：主角理性克制但有狠劲；女配嫌贫爱富；反派有官府资源压迫。',
  },
  {
    type: 'outline',
    label: '大纲',
    hint: '记录主线阶段、章节节点、冲突升级、反转和结尾钩子。',
    placeholder: '例如：第一卷目标、关键节点、每章功能、伏笔回收、阶段性爆点...',
    instructionPlaceholder: '例如：先写前 20 章大纲，每章一个冲突点，前三章必须强钩子。',
  },
];

const mode = computed(() => route.meta.creationMode || 'home');
const TASK_HISTORY_PREVIEW_LIMIT = 3;
const runningTaskCount = computed(() =>
  [activeTask.value, activeRewriteTask.value].filter(task => task && ['pending', 'running'].includes(task.status)).length
);
const scriptDraftCount = computed(() =>
  projects.value.reduce((sum, item) => sum + Number(item.scriptDraftCount || 0), 0)
);
const creationOverview = computed(() => [
  { label: '作品', value: projects.value.length },
  { label: '进行中', value: runningTaskCount.value },
  { label: '脚本草稿', value: scriptDraftCount.value },
]);
const creationFocusTitle = computed(() => {
  if (activeTask.value && ['pending', 'running'].includes(activeTask.value.status)) return activeTask.value.currentStep || '短剧任务进行中';
  if (activeRewriteTask.value && ['pending', 'running'].includes(activeRewriteTask.value.status)) return activeRewriteTask.value.currentStep || '改写任务进行中';
  if (projects.value.length) return '从最近作品继续推进';
  return '先创建一个可持续迭代的作品';
});
const creationFocusHint = computed(() => {
  if (runningTaskCount.value) return '生成、改写和转短剧任务会在这里汇总，方便随时回到结果。';
  if (projects.value.length) return '作品资产、导出动作和任务入口放在同一个工作台里，下一步更好找。';
  return '可以从长篇、短篇、导入或短剧改编开始，后续资产都会进入作品库。';
});
const taskHistorySearchActive = computed(() => Boolean(taskHistorySearchInput.value.trim()));
const filteredTaskHistory = computed(() => {
  const keyword = taskHistorySearchInput.value.trim().toLowerCase();
  if (!keyword) return taskHistory.value;
  return taskHistory.value.filter(item => {
    const fields = [
      item.title,
      item.projectTitle,
      item.currentStep,
      item.kind,
      taskStatusLabelFor(item.status),
      `${taskProgressFor(item)}%`,
    ];
    return fields.some(field => String(field || '').toLowerCase().includes(keyword));
  });
});
const taskHistoryCanExpand = computed(() => filteredTaskHistory.value.length > TASK_HISTORY_PREVIEW_LIMIT);
const visibleTaskHistory = computed(() =>
  taskHistoryExpanded.value
    ? filteredTaskHistory.value
    : filteredTaskHistory.value.slice(0, TASK_HISTORY_PREVIEW_LIMIT)
);
const taskHistoryHiddenCount = computed(() => Math.max(0, filteredTaskHistory.value.length - visibleTaskHistory.value.length));
const taskHistoryToggleLabel = computed(() =>
  taskHistoryExpanded.value ? '收起' : `展开全部 ${filteredTaskHistory.value.length} 条`
);
const taskHistoryBadgeText = computed(() =>
  taskHistorySearchActive.value
    ? `${filteredTaskHistory.value.length}/${taskHistory.value.length} 条`
    : `${taskHistory.value.length} 条`
);
const hasSearchQuery = computed(() => Boolean(searchQuery.value));
const filteredProjects = computed(() => {
  const keyword = searchQuery.value.trim().toLowerCase();
  const list = projects.value
    .filter(p => filter.value === 'all' || p.type === filter.value)
    .filter(p => statusFilter.value === 'all' || p.status === statusFilter.value)
    .filter(p => !keyword || String(p.title || '').toLowerCase().includes(keyword));
  return [...list].sort((a, b) => {
    if (sortOrder.value === 'updated_asc') return String(a.updatedAt || '').localeCompare(String(b.updatedAt || ''));
    if (sortOrder.value === 'title_asc') return String(a.title || '').localeCompare(String(b.title || ''));
    return String(b.updatedAt || '').localeCompare(String(a.updatedAt || ''));
  });
});
const wordCount = computed(() => (chapterForm.content || '').replace(/\s/g, '').length);
const assetWordCount = computed(() => (assetForm.content || '').replace(/\s/g, '').length);
const exportBusy = computed(() => exportGenerating.value || exportDownloading.value || exportAutoFixing.value);
const currentChapterLabel = computed(() =>
  currentChapter.value ? chapterDisplayTitle(currentChapter.value) : '未选择章节'
);
const currentChapterStatusLabel = computed(() => {
  if (!currentChapter.value) return '从左侧选择一个章节开始写作。';
  if (hasChapterDraft(currentChapter.value)) return '当前章节有未保存草稿，保存后会生成版本快照。';
  return '正文会在本地编辑，点击保存章节后同步到作品。';
});
const activeAsset = computed(() => assetTypes.find(asset => asset.type === activeAssetType.value) || assetTypes[0]);
const activeAssetLabel = computed(() => activeAsset.value.label);
const activeAssetHint = computed(() => activeAsset.value.hint);
const activeAssetPlaceholder = computed(() => activeAsset.value.placeholder);
const activeAssetInstructionPlaceholder = computed(() => activeAsset.value.instructionPlaceholder);
const activeAction = computed(() => aiActions.find(action => action.type === activeActionType.value));
const activeActionConfig = computed(() => ensureActionConfig(activeActionType.value));
const scriptActionTitle = computed(() => {
  if (!scriptActionBusy.value) return '';
  if (scriptActionBusy.value.startsWith('add:')) return '正在新增场次';
  return scriptActionLabels[scriptActionBusy.value] || '正在处理短剧';
});
const scriptPreview = computed(() => (draft.value?.episodes || []).map(ep => `第${ep.episodeNo}集\n核心爽点：${ep.coreHook}\n结尾钩子：${ep.endingHook}`).join('\n\n'));
const exportScenes = computed(() => (draft.value?.episodes || []).flatMap(ep => (ep.scenes || []).map(scene => ({
  ...scene,
  label: `第${ep.episodeNo}集 第${scene.sceneNo}场 ${scene.sceneTitle || ''}`,
}))));
const exportEpisodeOptions = computed(() => (draft.value?.episodes || []).map(ep => ({ value: ep.episodeNo, label: `第${ep.episodeNo}集` })));
const exportSceneOptions = computed(() => exportScenes.value.map(scene => ({ value: scene.id, label: scene.label })));
const activeTaskProgress = computed(() => Math.max(0, Math.min(100, Number(activeTask.value?.progress || 0))));
const activeTaskStatusLabel = computed(() => ({
  completed: '已完成',
  running: '生成中',
  pending: '排队中',
  failed: '失败',
  canceled: '已取消',
}[activeTask.value?.status] || activeTask.value?.status || '未知状态'));
const activeTaskName = computed(() => ({
  script_convert: '短剧分场稿生成',
}[activeTask.value?.taskType] || 'AI 生成任务'));
const taskPanelTitle = computed(() => activeTask.value?.status === 'completed' ? '最近生成任务' : '生成任务');
const activeScriptProjectId = computed(() => {
  if (scriptConvertingProjectId.value) return scriptConvertingProjectId.value;
  if (activeTask.value?.taskType === 'script_convert' && ['pending', 'running'].includes(activeTask.value.status)) {
    return activeTask.value.projectId;
  }
  return null;
});
const activeTaskDestination = computed(() => {
  if (!activeTask.value) return '';
  if (activeTask.value.draftId) return `/creation/scripts/${activeTask.value.draftId}`;
  if (activeTask.value.projectId && activeTask.value.status === 'completed') return `/creation/projects/${activeTask.value.projectId}/editor`;
  return '';
});
const taskCanOpen = computed(() => Boolean(activeTaskDestination.value));
const activeTaskOpenLabel = computed(() => taskCanOpen.value ? (activeTask.value?.draftId ? '查看结果' : '打开作品') : '等待完成');
const taskCanCancel = computed(() => activeTask.value && !['completed', 'failed', 'canceled'].includes(activeTask.value.status));
const taskCanRetry = computed(() => activeTask.value && ['failed', 'canceled', 'completed'].includes(activeTask.value.status));
const rewriteTaskProgress = computed(() => rewriteProgress(activeRewriteTask.value));
const rewriteTaskStatusLabel = computed(() => rewriteTaskStatusLabelFor(activeRewriteTask.value));
const rewriteTaskRunning = computed(() => activeRewriteTask.value && ['pending', 'running'].includes(activeRewriteTask.value.status));
const rewriteTaskPanelTitle = computed(() => activeRewriteTask.value?.status === 'completed' ? '最近改写任务' : '改写任务');
const rewriteTaskCanOpen = computed(() => activeRewriteTask.value && ['completed', 'accepted'].includes(activeRewriteTask.value.status));
const rewriteTaskCanCancel = computed(() => activeRewriteTask.value && ['pending', 'running'].includes(activeRewriteTask.value.status));
const rewriteTaskCanRetry = computed(() => activeRewriteTask.value && ['failed', 'canceled', 'completed', 'accepted'].includes(activeRewriteTask.value.status));
const rewriteTaskOpenLabel = computed(() => rewriteTaskCanOpen.value ? '查看三栏对照' : '等待完成');
const rewriteReady = computed(() => rewriteTask.value && ['completed', 'accepted'].includes(rewriteTask.value.status));
const rewriteSegments = computed(() => Array.isArray(rewriteTask.value?.segments) ? rewriteTask.value.segments : []);
const rewriteAcceptedCount = computed(() => rewriteSegments.value.filter(segment => segment.status !== 'rejected').length);
const rewriteRejectedCount = computed(() => rewriteSegments.value.filter(segment => segment.status === 'rejected').length);
const rewriteReviewSummary = computed(() => {
  if (!rewriteTask.value) return '正在加载改写任务。';
  if (!rewriteReady.value) return rewriteTask.value.currentStep || '改写任务处理中，完成后可逐段确认。';
  return `共 ${rewriteSegments.value.length} 段，采用 AI ${rewriteAcceptedCount.value} 段，保留原文 ${rewriteRejectedCount.value} 段。`;
});
const rewriteSegmentNotes = computed(() => rewriteSegments.value
  .map((segment, index) => ({ index: index + 1, note: String(segment.note || '').trim() }))
  .filter(item => item.note && item.note !== rewriteTask.value?.summaryNote));
const rewritePageProgress = computed(() => rewriteProgress(rewriteTask.value));
const rewritePageCanCancel = computed(() => rewriteTask.value && ['pending', 'running'].includes(rewriteTask.value.status));
const rewritePageCanRetry = computed(() => rewriteTask.value && ['failed', 'canceled', 'completed', 'accepted'].includes(rewriteTask.value.status));
const rewriteBackPath = computed(() => rewriteTask.value?.projectId ? `/creation/projects/${rewriteTask.value.projectId}/editor` : '/creation/projects');
const importBusy = computed(() => importPreviewLoading.value || importConfirming.value);
const adaptationPlanEntries = computed(() => {
  const plan = draft.value?.adaptationPlan || {};
  return [
    ['故事核', plan.storyCore],
    ['人物关系', plan.characterRelations],
    ['情节取舍', plan.plotSelection],
    ['改编策略', plan.strategy],
  ].filter(([, value]) => value).map(([label, value]) => ({ label, value }));
});
const qualityIssueList = computed(() => Array.isArray(qualityReport.value?.mainIssues) ? qualityReport.value.mainIssues : []);
const actionResultTitle = computed(() => {
  const target = actionForm.resultTarget || activeActionType.value;
  if (target === 'review') return '审查建议';
  if (isAssetAction(target)) return `${assetLabel(target)}生成草稿`;
  return 'AI 生成草稿';
});
const exportChecks = computed(() => {
  const episodes = draft.value?.episodes || [];
  const scenes = episodes.flatMap(ep => ep.scenes || []);
  const missingSceneFields = scenes.filter(scene => !scene.location || !scene.characters || !scene.sceneFunction || !scene.dialogue || !scene.hook).length;
  const missingEpisodeHooks = episodes.filter(ep => !ep.coreHook || !ep.mainConflict || !ep.endingHook).length;
  const report = qualityReport.value || draft.value?.qualityReport || {};
  return [
    { label: '分集包含预计时长、核心爽点、本集冲突和结尾钩子', pass: episodes.length > 0 && missingEpisodeHooks === 0 },
    { label: '每场包含场景、人物、本场功能、对白和钩子', pass: scenes.length > 0 && missingSceneFields === 0 },
    { label: '已执行短剧质量检查', pass: Boolean(report.totalScore) },
    { label: '当前导出范围能匹配到内容', pass: selectedExportSceneCount(episodes) > 0 },
  ];
});
const exportIssueCount = computed(() => exportChecks.value.filter(item => !item.pass).length);
function selectedExportSceneCount(episodes) {
  if (exportForm.scope === 'episode') {
    return episodes.filter(ep => ep.episodeNo === exportForm.episodeNo).flatMap(ep => ep.scenes || []).length;
  }
  if (exportForm.scope === 'scene') {
    return exportScenes.value.some(scene => scene.id === exportForm.sceneId) ? 1 : 0;
  }
  return episodes.flatMap(ep => ep.scenes || []).length;
}

let loadAbortController = null;

onMounted(loadByMode);
onUnmounted(() => {
  loadAbortController?.abort();
  stopActiveTaskPolling();
  stopRewritePolling();
});
watch(() => route.fullPath, loadByMode);

async function loadByMode() {
  loadAbortController?.abort();
  if (loadAbortController?.signal?.aborted) return;
  loadAbortController = new AbortController();
  try {
    if (mode.value === 'projects' && route.query.import === '1') showImport.value = true;
    if (mode.value === 'home' || mode.value === 'projects') {
      projectsLoading.value = true;
      const result = await Promise.all([
        storyApi.listProjects(),
        storyApi.getWorkbenchSummary(),
        mode.value === 'projects' ? loadTaskHistory() : Promise.resolve(),
        (['home', 'projects', 'editor'].includes(mode.value)) ? loadActiveTask() : Promise.resolve(),
        (['home', 'projects', 'editor'].includes(mode.value)) ? loadActiveRewriteTask() : Promise.resolve(),
      ]);
      projects.value = result[0];
      workbenchSummary.value = result[1] || { overview: {}, activeTasks: [], recentProjects: [] };
      projectsLoading.value = false;
    }
    if (mode.value === 'editor') await loadProject();
    if (mode.value === 'rewrite') {
      rewriteTask.value = await storyApi.getRewrite(route.params.taskId);
      activeRewriteTask.value = rewriteTask.value;
      localStorage.setItem('story:lastRewriteTaskId', String(rewriteTask.value.id));
      if (['pending', 'running'].includes(rewriteTask.value.status)) startRewritePolling();
    }
    if (mode.value === 'script' || mode.value === 'export') await loadDraft();
  } catch (err) {
    projectsLoading.value = false;
    ui.showToast('error', err.message || '加载失败');
  }
}

function openCreateDialog(type = 'long_novel') {
  createForm.title = '';
  createForm.type = type;
  createForm.description = '从这里开始创作。';
  showCreate.value = true;
}

function openImportDialog() {
  showImport.value = true;
}

async function createProject() {
  if (projectCreating.value) return;
  projectCreating.value = true;
  try {
    const p = await storyApi.createProject(createForm);
    showCreate.value = false;
    ui.showToast('success', '作品已创建');
    router.push(`/creation/projects/${p.id}/editor`);
  } catch (err) {
    ui.showToast('error', err.message || '创建作品失败');
  } finally {
    projectCreating.value = false;
  }
}

function applyProjectSearch() {
  searchQuery.value = searchInput.value.trim();
}

function clearProjectSearch() {
  searchInput.value = '';
  searchQuery.value = '';
}

async function previewImport() {
  if (!importFileRef.value && !importForm.content.trim()) return ui.showToast('warning', '请上传文件或粘贴正文');
  if (importBusy.value) return;
  importPreviewLoading.value = true;
  importPreview.value = null;
  try {
    importPreview.value = importFileRef.value
      ? await storyApi.previewImportFile(importFileRef.value, importForm.title)
      : await storyApi.previewImportText(importForm);
    importForm.title = importPreview.value.title || importForm.title;
    ui.showToast('success', '解析预览已生成');
  } catch (err) {
    ui.showToast('error', err.message || '解析失败，请检查文件或正文');
  } finally {
    importPreviewLoading.value = false;
  }
}

async function confirmImport() {
  if (!importPreview.value) return ui.showToast('warning', '请先解析预览');
  if (importBusy.value) return;
  importConfirming.value = true;
  try {
    const p = importFileRef.value
      ? await storyApi.importFile(importFileRef.value, importPreview.value.title || importForm.title)
      : await storyApi.importText({
        title: importPreview.value.title || importForm.title,
        content: importPreview.value.content || importForm.content,
      });
    showImport.value = false;
    importFileRef.value = null;
    importPreview.value = null;
    router.push(`/creation/projects/${p.id}/editor`);
  } catch (err) {
    ui.showToast('error', err.message || '导入失败，请稍后重试');
  } finally {
    importConfirming.value = false;
  }
}

function onImportFileChange(event) {
  if (importBusy.value) return;
  importFileRef.value = event.target.files?.[0] || null;
  importPreview.value = null;
  if (importFileRef.value && !importForm.title.trim()) {
    importForm.title = importFileRef.value.name.replace(/\.[^.]+$/, '');
  }
}

function closeImportDialog() {
  if (importBusy.value) return;
  showImport.value = false;
}

async function loadProject() {
  project.value = await storyApi.getProject(route.params.id);
  applyPromptConfig(project.value.promptConfig || project.value.metadata?.promptConfig);
  chapters.value = project.value.chapters || [];
  if (editorPanel.value === 'asset') {
    syncAssetForm();
  } else {
    const selected = chapters.value.find(chapter => chapter.id === currentChapter.value?.id) || chapters.value[0] || null;
    selectChapter(selected, { skipCache: true });
  }
}

function selectChapter(chapter, options = {}) {
  if (!options.skipCache) cacheCurrentChapterDraft();
  cacheCurrentAssetInstruction();
  editorPanel.value = 'chapter';
  currentChapter.value = chapter;
  const draft = chapterDraft(chapter);
  chapterForm.title = draft?.title ?? chapterDisplayTitle(chapter);
  chapterForm.content = draft?.content ?? chapter?.content ?? '';
  loadChapterVersions();
}

function chapterFallbackTitle(chapter) {
  const no = chapter?.chapterNo || (chapters.value.findIndex(item => item.id === chapter?.id) + 1) || chapters.value.length + 1;
  return `第${no}章`;
}

function chapterBaseTitle(chapter) {
  return String(chapter?.title || '').trim() || chapterFallbackTitle(chapter);
}

function chapterDisplayTitle(chapter) {
  const draft = chapterDraft(chapter);
  return String(draft?.title || '').trim() || chapterBaseTitle(chapter);
}

function chapterDraft(chapter) {
  if (!chapter?.id) return null;
  return chapterDrafts[chapter.id] || null;
}

function chapterValuesChanged(chapter, values) {
  if (!chapter) return false;
  const title = String(values?.title ?? '').trim() || chapterFallbackTitle(chapter);
  const baseTitle = chapterBaseTitle(chapter);
  const content = values?.content ?? '';
  return title !== baseTitle || content !== (chapter.content || '');
}

function hasChapterDraft(chapter) {
  if (!chapter?.id) return false;
  if (editorPanel.value === 'chapter' && currentChapter.value?.id === chapter.id) {
    return chapterValuesChanged(chapter, chapterForm);
  }
  const draft = chapterDraft(chapter);
  return draft ? chapterValuesChanged(chapter, draft) : false;
}

function cacheCurrentChapterDraft() {
  const chapter = currentChapter.value;
  if (!chapter?.id || editorPanel.value !== 'chapter') return;
  const draft = {
    title: chapterForm.title,
    content: chapterForm.content,
  };
  if (chapterValuesChanged(chapter, draft)) {
    chapterDrafts[chapter.id] = draft;
  } else {
    delete chapterDrafts[chapter.id];
  }
}

function projectAssets() {
  return project.value?.assets || project.value?.metadata?.assets || {};
}

function selectAsset(type) {
  cacheCurrentChapterDraft();
  cacheCurrentAssetInstruction();
  editorPanel.value = 'asset';
  activeAssetType.value = type;
  syncAssetForm();
}

function isAssetAction(action) {
  return ['setting', 'characters', 'outline'].includes(action);
}

function assetLabel(type) {
  return assetTypes.find(asset => asset.type === type)?.label || '资产';
}

function defaultPromptConfig() {
  return {
    global: {
      style: '爽文',
      pace: '快节奏',
      rewriteStrength: '中等',
      preserve: ['剧情', '人设', '伏笔'],
      avoid: ['AI腔', '总结腔', '过度排比'],
    },
    actions: {},
  };
}

function defaultActionConfig(type) {
  return {
    enabled: true,
    userTemplate: '',
    useCustomPrompt: false,
    params: {
      strength: type === 'conflict' ? '强' : '中等',
      keepPlot: true,
    },
  };
}

function ensureActionConfig(type) {
  if (!type) return defaultActionConfig('continue');
  if (!promptConfig.actions[type]) {
    promptConfig.actions[type] = defaultActionConfig(type);
  }
  if (!promptConfig.actions[type].params) {
    promptConfig.actions[type].params = defaultActionConfig(type).params;
  }
  if (promptConfig.actions[type].useCustomPrompt === undefined) {
    promptConfig.actions[type].useCustomPrompt = false;
  }
  return promptConfig.actions[type];
}

function applyPromptConfig(saved) {
  const defaults = defaultPromptConfig();
  const incoming = saved && typeof saved === 'object' ? saved : {};
  Object.assign(promptConfig.global, defaults.global, incoming.global || {});
  promptConfig.global.preserve = Array.isArray(promptConfig.global.preserve) ? promptConfig.global.preserve : defaults.global.preserve;
  promptConfig.global.avoid = Array.isArray(promptConfig.global.avoid) ? promptConfig.global.avoid : defaults.global.avoid;
  Object.keys(promptConfig.actions).forEach(key => delete promptConfig.actions[key]);
  Object.entries(incoming.actions || {}).forEach(([type, value]) => {
    promptConfig.actions[type] = {
      ...defaultActionConfig(type),
      ...(value || {}),
      params: {
        ...defaultActionConfig(type).params,
        ...((value || {}).params || {}),
      },
    };
  });
  aiActions.forEach(action => ensureActionConfig(action.type));
  syncGlobalListTexts();
}

function syncGlobalListTexts() {
  globalPreserveText.value = (promptConfig.global.preserve || []).join('、');
  globalAvoidText.value = (promptConfig.global.avoid || []).join('、');
}

function splitListText(text) {
  return String(text || '').split(/[、,，\n]/).map(item => item.trim()).filter(Boolean);
}

function syncGlobalLists() {
  promptConfig.global.preserve = splitListText(globalPreserveText.value);
  promptConfig.global.avoid = splitListText(globalAvoidText.value);
}

function promptConfigPayload() {
  syncGlobalLists();
  aiActions.forEach(action => ensureActionConfig(action.type));
  return JSON.parse(JSON.stringify(promptConfig));
}

async function savePromptConfig() {
  if (promptConfigSaving.value) return;
  promptConfigSaving.value = true;
  try {
    project.value = await storyApi.updateProject(project.value.id, { promptConfig: promptConfigPayload() });
    applyPromptConfig(project.value.promptConfig || project.value.metadata?.promptConfig);
    ui.showToast('success', 'AI 配置已保存');
  } catch (err) {
    ui.showToast('error', err.message || 'AI 配置保存失败');
  } finally {
    promptConfigSaving.value = false;
  }
}

function restoreActionPrompt() {
  const config = ensureActionConfig(activeActionType.value);
  config.userTemplate = '';
  config.useCustomPrompt = false;
  ui.showToast('success', '已恢复默认提示词');
}

function openActionPanel(type) {
  if (actionForm.loading) return;
  cacheCurrentAssetInstruction();
  activeActionType.value = type;
  aiConfigCollapsed.value = false;
  actionForm.result = '';
  actionForm.resultTarget = '';
  ensureActionConfig(type);
  if (isAssetAction(type)) {
    selectAsset(type);
    actionForm.instruction = assetForm.instruction;
  }
}

function actionSource() {
  if (isAssetAction(activeActionType.value)) {
    return [
      assetForm.content ? `当前${activeAssetLabel.value}：\n${assetForm.content}` : '',
      chapterForm.content ? `当前章节：\n${chapterForm.content}` : '',
    ].filter(Boolean).join('\n\n');
  }
  return chapterForm.content || '';
}

async function executeAction() {
  if (actionForm.loading) return;
  if (!project.value?.id) return;
  const actionType = activeActionType.value;
  const targetAssetType = isAssetAction(actionType) ? actionType : '';
  if (!isAssetAction(actionType) && !chapterForm.content.trim() && actionType !== 'continue') {
    ui.showToast('warning', '请先输入正文');
    return;
  }
  if (isAssetAction(actionType) && !actionForm.instruction.trim() && !assetForm.content.trim()) {
    ui.showToast('warning', `请先填写${activeAssetLabel.value}要求，或手动输入${activeAssetLabel.value}内容`);
    return;
  }
  const config = ensureActionConfig(actionType);
  if (targetAssetType) {
    assetForm.instruction = actionForm.instruction;
    assetInstructions[targetAssetType] = actionForm.instruction;
  }
  actionForm.result = '';
  actionForm.resultTarget = actionType;
  actionForm.controller = new AbortController();
  actionForm.loading = true;
  try {
    const result = await storyApi.generate(project.value.id, {
      action: actionType,
      chapterId: currentChapter.value?.id,
      content: actionSource(),
      instruction: actionForm.instruction,
      params: config.params || {},
      useCustomPrompt: Boolean(config.useCustomPrompt),
    }, {
      signal: actionForm.controller.signal,
    });
    actionForm.result = result.content || '';
    actionForm.resultTarget = actionType;
    if (targetAssetType) {
      ui.showToast('success', `${assetLabel(targetAssetType)}草稿已生成，请确认后应用`);
      return;
    }
    ui.showToast('success', actionType === 'review' ? '审查建议已生成' : 'AI 草稿已生成，请确认后应用');
  } catch (err) {
    if (err.canceled) {
      ui.showToast('info', '已停止生成');
      return;
    }
    ui.showToast('error', err.message || '生成失败，请稍后重试');
  } finally {
    actionForm.loading = false;
    actionForm.controller = null;
  }
}

function stopActionGeneration() {
  if (!actionForm.loading || !actionForm.controller) return;
  actionForm.controller.abort();
}

function appendActionResult() {
  if (!actionForm.result.trim()) return;
  chapterForm.content = [chapterForm.content, actionForm.result].filter(Boolean).join(chapterForm.content ? '\n\n' : '');
  actionForm.result = '';
  actionForm.resultTarget = '';
  ui.showToast('success', '已追加到正文，记得保存章节');
}

function replaceChapterWithResult() {
  if (!actionForm.result.trim()) return;
  chapterForm.content = actionForm.result;
  actionForm.result = '';
  actionForm.resultTarget = '';
  ui.showToast('success', '已替换正文，记得保存章节');
}

async function applyAssetResult() {
  const target = actionForm.resultTarget;
  if (!isAssetAction(target) || !actionForm.result.trim()) return;
  await saveAssetContent(target, actionForm.result);
  actionForm.result = '';
  actionForm.resultTarget = '';
}

function discardActionResult() {
  actionForm.result = '';
  actionForm.resultTarget = '';
}

function syncAssetForm() {
  assetForm.content = projectAssets()[activeAssetType.value] || '';
  assetForm.instruction = assetInstructions[activeAssetType.value] || '';
}

function cacheCurrentAssetInstruction() {
  if (editorPanel.value !== 'asset') return;
  assetInstructions[activeAssetType.value] = assetForm.instruction || '';
}

async function addChapter() {
  if (chapterAdding.value) return;
  chapterAdding.value = true;
  cacheCurrentChapterDraft();
  try {
    const chapter = await storyApi.createChapter(project.value.id, { title: `第${chapters.value.length + 1}章`, content: '' });
    chapters.value.push(chapter);
    selectChapter(chapter, { skipCache: true });
    ui.showToast('success', '章节已新增');
    return chapter;
  } catch (err) {
    ui.showToast('error', err.message || '新增章节失败');
    return null;
  } finally {
    chapterAdding.value = false;
  }
}

async function saveChapter() {
  if (chapterSaving.value) return;
  if (!currentChapter.value) {
    const chapter = await addChapter();
    if (!chapter) return;
  }
  chapterSaving.value = true;
  try {
    const saved = await storyApi.updateChapter(currentChapter.value.id, chapterForm);
    Object.assign(currentChapter.value, saved);
    const index = chapters.value.findIndex(chapter => chapter.id === saved.id);
    if (index >= 0) chapters.value[index] = { ...chapters.value[index], ...saved };
    delete chapterDrafts[saved.id];
    await loadChapterVersions();
    ui.showToast('success', '章节已保存');
  } catch (err) {
    ui.showToast('error', err.message || '保存章节失败');
  } finally {
    chapterSaving.value = false;
  }
}

async function saveAsset() {
  await saveAssetContent(activeAssetType.value, assetForm.content);
}

async function saveAssetContent(type, content) {
  if (assetSaving.value) return;
  assetSaving.value = true;
  try {
    const assets = { ...projectAssets(), [type]: content };
    project.value = await storyApi.updateProject(project.value.id, { assets });
    if (activeAssetType.value === type) assetForm.content = content;
    ui.showToast('success', `${assetLabel(type)}已保存`);
  } catch (err) {
    ui.showToast('error', err.message || `${assetLabel(type)}保存失败`);
  } finally {
    assetSaving.value = false;
  }
}

async function deleteChapter(chapter) {
  if (!chapter?.id || chapterDeletingId.value) return;
  if (chapters.value.length <= 1) {
    ui.showToast('warning', '至少需要保留一个章节');
    return;
  }
  const hasDraft = hasChapterDraft(chapter);
  const message = hasDraft
    ? `确定删除「${chapterDisplayTitle(chapter)}」吗？该章节的未保存草稿和版本快照都会删除。`
    : `确定删除「${chapterDisplayTitle(chapter)}」吗？该章节正文和版本快照都会删除。`;
  const confirmed = await ui.showConfirm({
    title: '删除章节',
    message,
    confirmText: '删除章节',
    cancelText: '取消',
    variant: 'danger',
  });
  if (!confirmed) return;
  const deletedIndex = chapters.value.findIndex(item => item.id === chapter.id);
  chapterDeletingId.value = chapter.id;
  try {
    const result = await storyApi.deleteChapter(chapter.id);
    delete chapterDrafts[chapter.id];
    chapters.value = result.chapters || chapters.value.filter(item => item.id !== chapter.id);
    if (currentChapter.value?.id === chapter.id) {
      const next = chapters.value[Math.min(deletedIndex, chapters.value.length - 1)] || chapters.value[0] || null;
      selectChapter(next, { skipCache: true });
    }
    ui.showToast('success', '章节已删除');
  } catch (err) {
    ui.showToast('error', err.message || '删除章节失败');
  } finally {
    chapterDeletingId.value = null;
  }
}

async function loadChapterVersions() {
  if (!currentChapter.value?.id) {
    chapterVersions.value = [];
    return;
  }
  chapterVersions.value = await storyApi.listChapterVersions(currentChapter.value.id);
}

async function restoreVersion(version) {
  if (!currentChapter.value?.id || versionRestoringId.value) return;
  versionRestoringId.value = version.id;
  try {
    const saved = await storyApi.restoreChapter(currentChapter.value.id, { versionId: version.id });
    Object.assign(currentChapter.value, saved);
    const index = chapters.value.findIndex(chapter => chapter.id === saved.id);
    if (index >= 0) chapters.value[index] = { ...chapters.value[index], ...saved };
    delete chapterDrafts[saved.id];
    chapterForm.title = saved.title;
    chapterForm.content = saved.content;
    await loadChapterVersions();
    ui.showToast('success', `已恢复到 V${version.versionNo}`);
  } catch (err) {
    ui.showToast('error', err.message || '恢复版本失败');
  } finally {
    versionRestoringId.value = null;
  }
}

async function generateAsset(type) {
  openActionPanel(type);
  actionForm.instruction = assetForm.instruction;
  await executeAction();
}

async function startRewrite() {
  if (!chapterForm.content.trim()) return ui.showToast('warning', '请先输入正文');
  if (rewriteStarting.value || rewriteTaskRunning.value) return;
  rewriteStarting.value = true;
  try {
    const task = await storyApi.createRewrite({ projectId: project.value.id, chapterId: currentChapter.value?.id, sourceText: chapterForm.content, rewriteMode: 'deslop' });
    activeRewriteTask.value = task;
    localStorage.setItem('story:lastRewriteTaskId', String(task.id));
    startRewritePolling();
    await loadTaskHistory();
    ui.showToast('info', '改写任务已开始，可切换页面，完成后从任务卡查看');
  } catch (err) {
    ui.showToast('error', err.message || '改写任务提交失败');
  } finally {
    rewriteStarting.value = false;
  }
}

async function acceptRewrite() {
  if (!rewriteReady.value || rewriteAccepting.value) return;
  rewriteAccepting.value = true;
  try {
    const result = await storyApi.acceptRewrite(route.params.taskId, { segments: rewriteTask.value.segments });
    if (activeRewriteTask.value?.id === Number(route.params.taskId)) {
      activeRewriteTask.value = { ...activeRewriteTask.value, status: 'accepted', progress: 100, currentStep: '已保存为新版本' };
    }
    ui.showToast('success', '已保存为新版本');
    router.push(`/creation/projects/${result.projectId}/editor`);
  } catch (err) {
    ui.showToast('error', err.message || '保存改写结果失败');
  } finally {
    rewriteAccepting.value = false;
  }
}

async function retryRewrite() {
  if (rewriteRetrying.value || rewritePageActionBusy.value) return;
  rewriteRetrying.value = true;
  rewritePageActionBusy.value = 'retry';
  try {
    const task = await storyApi.retryRewrite(route.params.taskId, {
      rewriteMode: rewriteTask.value?.rewriteMode || 'deslop',
      instruction: rewriteForm.instruction,
    });
    rewriteTask.value = task;
    activeRewriteTask.value = task;
    localStorage.setItem('story:lastRewriteTaskId', String(task.id));
    startRewritePolling();
    await loadTaskHistory();
    ui.showToast('success', '已重新提交改写任务');
    router.replace(`/creation/rewrite/${task.id}`);
  } catch (err) {
    ui.showToast('error', err.message || '重新改写失败');
  } finally {
    rewriteRetrying.value = false;
    rewritePageActionBusy.value = '';
  }
}

async function startScript(p) {
  if (!p?.id || scriptConvertingProjectId.value || activeScriptProjectId.value === p.id) return;
  scriptConvertingProjectId.value = p.id;
  ui.showToast('info', '已提交转短剧任务，可切换页面，完成后从任务卡查看');
  try {
    const task = await storyApi.convertToScript({ projectId: p.id, targetEpisodes: 20 });
    activeTask.value = task;
    localStorage.setItem('story:lastTaskId', String(task.id));
    if (['pending', 'running'].includes(task.status)) {
      startActiveTaskPolling();
    } else if (task.draftId) {
      router.push(`/creation/scripts/${task.draftId}`);
    }
    await loadTaskHistory();
  } catch (err) {
    ui.showToast('error', err.message || '转短剧失败');
  } finally {
    scriptConvertingProjectId.value = null;
  }
}

async function downloadProjectExport({ project: targetProject, format }) {
  if (!targetProject?.id || projectExportingId.value) return;
  projectExportingId.value = targetProject.id;
  try {
    const response = await storyApi.exportProjectFile(targetProject.id, { format });
    saveBlobResponse(response, `${targetProject.title || '作品'}.${exportExtension(format)}`);
    ui.showToast('success', '作品已导出');
  } catch (err) {
    ui.showToast('error', err.message || '导出作品失败');
  } finally {
    projectExportingId.value = null;
  }
}

async function deleteProject(targetProject) {
  if (!targetProject?.id || projectDeletingId.value) return;
  const confirmed = await ui.showConfirm({
    title: '删除作品',
    message: `确定删除「${targetProject.title || '未命名作品'}」吗？关联章节、短剧脚本和生成任务也会一起删除。`,
    confirmText: '删除作品',
    cancelText: '取消',
    variant: 'danger',
  });
  if (!confirmed) return;
  projectDeletingId.value = targetProject.id;
  try {
    await storyApi.deleteProject(targetProject.id);
    projects.value = projects.value.filter(item => item.id !== targetProject.id);
    if (activeTask.value?.projectId === targetProject.id) dismissTask();
    ui.showToast('success', '作品已删除');
  } catch (err) {
    ui.showToast('error', err.message || '删除作品失败');
  } finally {
    projectDeletingId.value = null;
  }
}

async function loadActiveTask() {
  const taskId = localStorage.getItem('story:lastTaskId');
  if (!taskId) return;
  try {
    activeTask.value = await storyApi.getTask(taskId);
    if (!activeTask.value?.projectId && !activeTask.value?.draftId) {
      dismissTask();
      return;
    }
    if (['pending', 'running'].includes(activeTask.value.status)) startActiveTaskPolling();
  } catch {
    activeTask.value = null;
  }
}

async function refreshTask(options = {}) {
  if (!activeTask.value?.id) return;
  if (!options.silent && taskActionBusy.value) return;
  if (!options.silent) taskActionBusy.value = 'refresh';
  try {
    const previousStatus = activeTask.value.status;
    activeTask.value = await storyApi.getTask(activeTask.value.id);
    if (['pending', 'running'].includes(activeTask.value.status)) {
      startActiveTaskPolling();
    } else {
      stopActiveTaskPolling();
    }
    if (!options.silent && ['pending', 'running'].includes(previousStatus) && activeTask.value.status === 'completed') {
      ui.showToast('success', '短剧分场稿已生成，可从任务卡查看');
    }
  } catch (err) {
    if (!options.silent) ui.showToast('error', err.message || '刷新任务失败');
  } finally {
    if (!options.silent) taskActionBusy.value = '';
  }
}

function startActiveTaskPolling() {
  if (activeTaskPollTimer) return;
  activeTaskPollTimer = window.setInterval(() => {
    if (!activeTask.value?.id || !['pending', 'running'].includes(activeTask.value.status)) {
      stopActiveTaskPolling();
      return;
    }
    refreshTask({ silent: true }).catch(() => {});
  }, 3000);
}

function stopActiveTaskPolling() {
  if (!activeTaskPollTimer) return;
  window.clearInterval(activeTaskPollTimer);
  activeTaskPollTimer = null;
}

function openActiveTask() {
  if (!activeTaskDestination.value) return;
  router.push(activeTaskDestination.value);
}

function dismissTask() {
  stopActiveTaskPolling();
  activeTask.value = null;
  localStorage.removeItem('story:lastTaskId');
}

async function cancelTask() {
  if (!activeTask.value?.id || taskActionBusy.value) return;
  taskActionBusy.value = 'cancel';
  try {
    activeTask.value = await storyApi.cancelTask(activeTask.value.id);
    stopActiveTaskPolling();
    await loadTaskHistory();
    ui.showToast('success', '已终止转短剧任务');
  } catch (err) {
    ui.showToast('error', err.message || '终止转短剧任务失败');
  } finally {
    taskActionBusy.value = '';
  }
}

async function retryTask() {
  if (!activeTask.value?.id || taskActionBusy.value) return;
  taskActionBusy.value = 'retry';
  try {
    const task = await storyApi.retryTask(activeTask.value.id);
    activeTask.value = task;
    localStorage.setItem('story:lastTaskId', String(task.id));
    if (['pending', 'running'].includes(task.status)) startActiveTaskPolling();
    await loadTaskHistory();
    ui.showToast('success', '已重新提交转短剧任务');
  } catch (err) {
    ui.showToast('error', err.message || '重试转短剧任务失败');
  } finally {
    taskActionBusy.value = '';
  }
}

async function loadTaskHistory() {
  taskHistoryLoading.value = true;
  try {
    const result = await storyApi.listTaskHistory(30);
    taskHistory.value = Array.isArray(result.tasks) ? result.tasks : [];
    if (taskHistory.value.length <= TASK_HISTORY_PREVIEW_LIMIT) taskHistoryExpanded.value = false;
  } catch (err) {
    ui.showToast('error', err.message || '历史任务加载失败');
  } finally {
    taskHistoryLoading.value = false;
  }
}

function toggleTaskHistory() {
  taskHistoryExpanded.value = !taskHistoryExpanded.value;
}

function clearTaskHistorySearch() {
  taskHistorySearchInput.value = '';
  taskHistoryExpanded.value = false;
}

function updateTaskHistorySearch(value) {
  taskHistorySearchInput.value = value;
  taskHistoryExpanded.value = false;
}

function taskStatusLabelFor(status) {
  return ({
    completed: '已完成',
    accepted: '已保存',
    running: '进行中',
    pending: '排队中',
    failed: '失败',
    canceled: '已取消',
  }[status] || status || '未知状态');
}

function taskProgressFor(item) {
  return Math.max(0, Math.min(100, Number(item?.progress || 0)));
}

function historyTaskCanOpen(item) {
  if (!item) return false;
  if (item.kind === 'generation') return Boolean(item.draftId || item.projectId);
  if (item.kind === 'rewrite') return ['completed', 'accepted'].includes(item.status);
  return false;
}

function historyTaskCanCancel(item) {
  return item && ['pending', 'running'].includes(item.status);
}

function historyTaskCanRetry(item) {
  return item && ['failed', 'canceled', 'completed', 'accepted'].includes(item.status);
}

function historyTaskKey(item) {
  return `${item?.kind || 'task'}:${item?.taskId || item?.id || ''}`;
}

async function restoreHistoryTask(item) {
  if (!item?.taskId || historyTaskBusyKey.value) return;
  historyTaskBusyKey.value = historyTaskKey(item);
  historyTaskBusyAction.value = 'restore';
  try {
    if (item.kind === 'generation') {
      activeTask.value = await storyApi.getTask(item.taskId);
      localStorage.setItem('story:lastTaskId', String(activeTask.value.id));
      if (['pending', 'running'].includes(activeTask.value.status)) startActiveTaskPolling();
    } else if (item.kind === 'rewrite') {
      activeRewriteTask.value = await storyApi.getRewrite(item.taskId);
      localStorage.setItem('story:lastRewriteTaskId', String(activeRewriteTask.value.id));
      if (['pending', 'running'].includes(activeRewriteTask.value.status)) startRewritePolling();
    }
    ui.showToast('success', '已置顶到任务卡');
  } catch (err) {
    ui.showToast('error', err.message || '恢复任务失败');
  } finally {
    historyTaskBusyKey.value = '';
    historyTaskBusyAction.value = '';
  }
}

async function openHistoryTask(item) {
  if (!historyTaskCanOpen(item)) return;
  if (item.kind === 'generation') {
    if (item.draftId) {
      router.push(`/creation/scripts/${item.draftId}`);
    } else if (item.projectId) {
      router.push(`/creation/projects/${item.projectId}/editor`);
    }
    return;
  }
  if (item.kind === 'rewrite') {
    router.push(`/creation/rewrite/${item.taskId}`);
  }
}

async function cancelHistoryTask(item) {
  if (!historyTaskCanCancel(item) || historyTaskBusyKey.value) return;
  historyTaskBusyKey.value = historyTaskKey(item);
  historyTaskBusyAction.value = 'cancel';
  try {
    if (item.kind === 'generation') {
      const task = await storyApi.cancelTask(item.taskId);
      if (activeTask.value?.id === task.id) activeTask.value = task;
    } else if (item.kind === 'rewrite') {
      const task = await storyApi.cancelRewrite(item.taskId);
      if (activeRewriteTask.value?.id === task.id) activeRewriteTask.value = task;
    }
    await loadTaskHistory();
    ui.showToast('success', '任务已终止');
  } catch (err) {
    ui.showToast('error', err.message || '终止任务失败');
  } finally {
    historyTaskBusyKey.value = '';
    historyTaskBusyAction.value = '';
  }
}

async function retryHistoryTask(item) {
  if (!historyTaskCanRetry(item) || historyTaskBusyKey.value) return;
  historyTaskBusyKey.value = historyTaskKey(item);
  historyTaskBusyAction.value = 'retry';
  try {
    if (item.kind === 'generation') {
      const task = await storyApi.retryTask(item.taskId);
      activeTask.value = task;
      localStorage.setItem('story:lastTaskId', String(task.id));
      if (['pending', 'running'].includes(task.status)) startActiveTaskPolling();
    } else if (item.kind === 'rewrite') {
      const task = await storyApi.retryRewrite(item.taskId, {
        rewriteMode: item.rewriteMode || 'deslop',
      });
      activeRewriteTask.value = task;
      localStorage.setItem('story:lastRewriteTaskId', String(task.id));
      if (['pending', 'running'].includes(task.status)) startRewritePolling();
    }
    await loadTaskHistory();
    ui.showToast('success', '已重新提交任务');
  } catch (err) {
    ui.showToast('error', err.message || '重试任务失败');
  } finally {
    historyTaskBusyKey.value = '';
    historyTaskBusyAction.value = '';
  }
}

function rewriteProgress(task) {
  return Math.max(0, Math.min(100, Number(task?.progress || 0)));
}

function rewriteTaskStatusLabelFor(task) {
  return ({
    completed: '已完成',
    accepted: '已保存',
    running: '改写中',
    pending: '排队中',
    failed: '失败',
    canceled: '已取消',
  }[task?.status] || task?.status || '未知状态');
}

async function loadActiveRewriteTask() {
  const taskId = localStorage.getItem('story:lastRewriteTaskId');
  if (!taskId) return;
  try {
    activeRewriteTask.value = await storyApi.getRewrite(taskId);
    if (!activeRewriteTask.value?.projectId) {
      dismissRewriteTask();
      return;
    }
    if (['pending', 'running'].includes(activeRewriteTask.value.status)) startRewritePolling();
  } catch {
    activeRewriteTask.value = null;
  }
}

async function refreshRewriteTask(options = {}) {
  if (!activeRewriteTask.value?.id) return;
  if (!options.silent && rewriteTaskActionBusy.value) return;
  if (!options.silent) rewriteTaskActionBusy.value = 'refresh';
  try {
    const previousStatus = activeRewriteTask.value.status;
    const task = await storyApi.getRewrite(activeRewriteTask.value.id);
    activeRewriteTask.value = task;
    if (rewriteTask.value?.id === task.id) rewriteTask.value = task;
    if (['pending', 'running'].includes(task.status)) {
      startRewritePolling();
    } else {
      stopRewritePolling();
    }
    if (!options.silent && ['pending', 'running'].includes(previousStatus) && task.status === 'completed') {
      ui.showToast('success', '改写任务已完成，可从任务卡查看三栏对照');
    }
  } catch (err) {
    if (!options.silent) ui.showToast('error', err.message || '刷新改写任务失败');
  } finally {
    if (!options.silent) rewriteTaskActionBusy.value = '';
  }
}

function startRewritePolling() {
  if (rewritePollTimer) return;
  rewritePollTimer = window.setInterval(() => {
    if (!activeRewriteTask.value?.id || !['pending', 'running'].includes(activeRewriteTask.value.status)) {
      stopRewritePolling();
      return;
    }
    refreshRewriteTask({ silent: true }).catch(() => {});
  }, 3000);
}

function stopRewritePolling() {
  if (!rewritePollTimer) return;
  window.clearInterval(rewritePollTimer);
  rewritePollTimer = null;
}

function openActiveRewriteTask() {
  if (!rewriteTaskCanOpen.value || !activeRewriteTask.value?.id) return;
  router.push(`/creation/rewrite/${activeRewriteTask.value.id}`);
}

function dismissRewriteTask() {
  stopRewritePolling();
  activeRewriteTask.value = null;
  localStorage.removeItem('story:lastRewriteTaskId');
}

async function cancelActiveRewriteTask() {
  if (!activeRewriteTask.value?.id || !rewriteTaskCanCancel.value || rewriteTaskActionBusy.value) return;
  rewriteTaskActionBusy.value = 'cancel';
  try {
    const task = await storyApi.cancelRewrite(activeRewriteTask.value.id);
    activeRewriteTask.value = task;
    if (rewriteTask.value?.id === task.id) rewriteTask.value = task;
    stopRewritePolling();
    await loadTaskHistory();
    ui.showToast('success', '已终止改写任务');
  } catch (err) {
    ui.showToast('error', err.message || '终止改写失败');
  } finally {
    rewriteTaskActionBusy.value = '';
  }
}

async function cancelRewritePage() {
  if (!rewriteTask.value?.id || !rewritePageCanCancel.value || rewritePageActionBusy.value) return;
  rewritePageActionBusy.value = 'cancel';
  try {
    const task = await storyApi.cancelRewrite(rewriteTask.value.id);
    rewriteTask.value = task;
    activeRewriteTask.value = task;
    stopRewritePolling();
    await loadTaskHistory();
    ui.showToast('success', '已终止改写任务');
  } catch (err) {
    ui.showToast('error', err.message || '终止改写失败');
  } finally {
    rewritePageActionBusy.value = '';
  }
}

async function retryActiveRewriteTask() {
  if (!activeRewriteTask.value?.id || !rewriteTaskCanRetry.value || rewriteRetrying.value || rewriteTaskActionBusy.value) return;
  rewriteRetrying.value = true;
  rewriteTaskActionBusy.value = 'retry';
  try {
    const task = await storyApi.retryRewrite(activeRewriteTask.value.id, {
      rewriteMode: activeRewriteTask.value.rewriteMode || 'deslop',
      instruction: activeRewriteTask.value.instruction || '',
    });
    activeRewriteTask.value = task;
    localStorage.setItem('story:lastRewriteTaskId', String(task.id));
    startRewritePolling();
    await loadTaskHistory();
    ui.showToast('success', '已重新提交改写任务');
  } catch (err) {
    ui.showToast('error', err.message || '重新改写失败');
  } finally {
    rewriteRetrying.value = false;
    rewriteTaskActionBusy.value = '';
  }
}

async function refreshRewritePage() {
  if (!route.params.taskId || rewritePageActionBusy.value) return;
  rewritePageActionBusy.value = 'refresh';
  try {
    rewriteTask.value = await storyApi.getRewrite(route.params.taskId);
    activeRewriteTask.value = rewriteTask.value;
    if (['pending', 'running'].includes(rewriteTask.value.status)) {
      startRewritePolling();
    } else {
      stopRewritePolling();
    }
  } catch (err) {
    ui.showToast('error', err.message || '刷新改写任务失败');
  } finally {
    rewritePageActionBusy.value = '';
  }
}

function setRewriteSegmentStatus(segment, status) {
  segment.status = status;
}

async function loadDraft() {
  draft.value = await storyApi.getScriptDraft(route.params.draftId);
  const ep = draft.value.episodes?.[0];
  selectScene(ep, ep?.scenes?.[0]);
  if (!exportForm.episodeNo) exportForm.episodeNo = ep?.episodeNo || null;
  if (!exportForm.sceneId) exportForm.sceneId = ep?.scenes?.[0]?.id || null;
}

function selectScene(ep, scene) {
  currentEpisode.value = ep;
  currentScene.value = scene;
  Object.assign(sceneForm, scene || {});
}

async function saveScene() {
  if (!currentScene.value || scriptActionBusy.value) return;
  scriptActionBusy.value = 'scene:save';
  try {
    const saved = await storyApi.updateScene(currentScene.value.id, sceneForm);
    Object.assign(currentScene.value, saved);
    ui.showToast('success', '场次已保存');
  } catch (err) {
    ui.showToast('error', err.message || '保存场次失败');
  } finally {
    scriptActionBusy.value = '';
  }
}

async function addScene(ep) {
  if (!ep?.id || scriptActionBusy.value) return;
  scriptActionBusy.value = `add:${ep.id}`;
  try {
    const scene = await storyApi.createScene(ep.id, {
      sceneTitle: '新增场次',
      location: '内景｜待定｜日',
      characters: '',
      sceneFunction: '补充冲突推进',
    });
    ep.scenes = [...(ep.scenes || []), scene];
    selectScene(ep, scene);
    ui.showToast('success', '场次已新增');
  } catch (err) {
    ui.showToast('error', err.message || '新增场次失败');
  } finally {
    scriptActionBusy.value = '';
  }
}

async function deleteScene() {
  if (!currentScene.value || !currentEpisode.value || scriptActionBusy.value) return;
  scriptActionBusy.value = 'scene:delete';
  try {
    await storyApi.deleteScene(currentScene.value.id);
    currentEpisode.value.scenes = (currentEpisode.value.scenes || []).filter(scene => scene.id !== currentScene.value.id);
    currentEpisode.value.scenes.forEach((scene, index) => { scene.sceneNo = index + 1; });
    selectScene(currentEpisode.value, currentEpisode.value.scenes[0] || null);
    ui.showToast('success', '场次已删除');
  } catch (err) {
    ui.showToast('error', err.message || '删除场次失败');
  } finally {
    scriptActionBusy.value = '';
  }
}

async function moveScene(direction) {
  if (!currentScene.value || !currentEpisode.value || scriptActionBusy.value) return;
  scriptActionBusy.value = `scene:move-${direction}`;
  try {
    const scenes = await storyApi.moveScene(currentScene.value.id, { direction });
    currentEpisode.value.scenes = scenes;
    const moved = scenes.find(scene => scene.id === currentScene.value.id);
    selectScene(currentEpisode.value, moved || scenes[0] || null);
    ui.showToast('success', direction === 'up' ? '场次已上移' : '场次已下移');
  } catch (err) {
    ui.showToast('error', err.message || '移动场次失败');
  } finally {
    scriptActionBusy.value = '';
  }
}

async function improveEpisode() {
  if (!currentEpisode.value || scriptActionBusy.value) return;
  scriptActionBusy.value = 'episode:rewrite';
  try {
    const saved = await storyApi.improveEpisode(currentEpisode.value.id, { action: 'rewrite', episode: currentEpisode.value });
    Object.assign(currentEpisode.value, saved);
    ui.showToast('success', '本集已重写');
  } catch (err) {
    ui.showToast('error', err.message || '重写本集失败');
  } finally {
    scriptActionBusy.value = '';
  }
}
async function improveScene(action) {
  if (!currentScene.value || scriptActionBusy.value) return;
  scriptActionBusy.value = `scene:${action}`;
  try {
    const saved = await storyApi.improveScene(currentScene.value.id, { action, scene: sceneForm });
    Object.assign(currentScene.value, saved);
    Object.assign(sceneForm, saved);
    ui.showToast('success', '场次已更新');
  } catch (err) {
    ui.showToast('error', err.message || '场次更新失败');
  } finally {
    scriptActionBusy.value = '';
  }
}

async function qualityCheck() {
  if (scriptActionBusy.value) return;
  scriptActionBusy.value = 'draft:quality';
  try {
    qualityReport.value = await storyApi.checkQuality(route.params.draftId);
    resetQualityOptimizedIssues();
    ui.showToast('success', '质检完成');
  } catch (err) {
    ui.showToast('error', err.message || '质检失败');
  } finally {
    scriptActionBusy.value = '';
  }
}

async function improveFromQualityIssue(issue) {
  if (scriptActionBusy.value) return;
  const target = findSceneForQualityIssue(issue);
  if (!target?.scene || !target?.episode) return ui.showToast('warning', '请先选择一个要优化的场次');
  scriptActionBusy.value = 'quality:fix';
  qualityFixingIssue.value = issue;
  try {
    selectScene(target.episode, target.scene);
    const action = qualityActionForIssue(issue);
    const saved = await storyApi.improveScene(target.scene.id, {
      action,
      instruction: issue,
      scene: target.scene,
    });
    Object.assign(target.scene, saved);
    Object.assign(sceneForm, saved);
    qualityOptimizedIssues[issue] = true;
    ui.showToast('success', '已按质检问题优化当前场');
  } catch (err) {
    ui.showToast('error', err.message || '按质检问题优化失败');
  } finally {
    scriptActionBusy.value = '';
    qualityFixingIssue.value = '';
  }
}

function isQualityIssueOptimized(issue) {
  return Boolean(qualityOptimizedIssues[issue]);
}

function resetQualityOptimizedIssues() {
  Object.keys(qualityOptimizedIssues).forEach(key => {
    delete qualityOptimizedIssues[key];
  });
}

function findSceneForQualityIssue(issue) {
  const episodes = draft.value?.episodes || [];
  const episodeMatch = String(issue || '').match(/第\s*(\d+)\s*集/);
  const sceneMatch = String(issue || '').match(/第\s*(\d+)\s*场/);
  const episodeNo = episodeMatch ? Number(episodeMatch[1]) : currentEpisode.value?.episodeNo;
  const sceneNo = sceneMatch ? Number(sceneMatch[1]) : currentScene.value?.sceneNo;
  const episode = episodes.find(ep => Number(ep.episodeNo) === episodeNo) || currentEpisode.value;
  const scene = (episode?.scenes || []).find(item => Number(item.sceneNo) === sceneNo) || currentScene.value;
  return { episode, scene };
}

function qualityActionForIssue(issue) {
  const text = String(issue || '');
  if (/钩子|开场|结尾|追看/.test(text)) return 'hook';
  if (/旁白|心理|内心|小说化|小说残留|不可拍|抽象/.test(text)) return 'externalize';
  if (/对白|口语|解释/.test(text)) return 'dialogue';
  return 'quality_fix';
}

async function autoFixExportIssues() {
  if (exportAutoFixing.value || exportBusy.value) return;
  exportAutoFixing.value = true;
  exportFeedbackTone.value = 'info';
  exportFeedback.value = '正在自动修复导出前检查项，请稍候。';
  try {
    const scenes = (draft.value?.episodes || []).flatMap(ep => ep.scenes || []);
    let fixed = 0;
    for (const scene of scenes) {
      if (!scene.hook) {
        await storyApi.improveScene(scene.id, { action: 'hook', scene });
        fixed++;
      } else if (!scene.dialogue) {
        await storyApi.improveScene(scene.id, { action: 'dialogue', scene });
        fixed++;
      }
    }
    qualityReport.value = await storyApi.checkQuality(route.params.draftId, { useFallback: true });
    await loadDraft();
    exportFeedbackTone.value = fixed ? 'success' : 'info';
    exportFeedback.value = fixed ? `已修复 ${fixed} 个可自动处理的场次。` : '没有发现可自动修复的空缺项。';
    ui.showToast(fixed ? 'success' : 'info', fixed ? `已修复 ${fixed} 个可自动处理的场次` : '没有发现可自动修复的空缺项');
  } catch (err) {
    exportFeedbackTone.value = 'error';
    exportFeedback.value = err.message || '自动修复失败，请稍后重试。';
    ui.showToast('error', exportFeedback.value);
  } finally {
    exportAutoFixing.value = false;
  }
}

async function exportDraft() {
  if (exportBusy.value) return;
  exportGenerating.value = true;
  exportFeedbackTone.value = 'info';
  exportFeedback.value = '正在生成导出内容，请稍候。';
  try {
    exported.value = await storyApi.exportDraft(route.params.draftId, exportForm);
    exportFeedbackTone.value = 'success';
    exportFeedback.value = `导出内容已生成，可预览或下载 ${exportFormatLabel(exportForm.format)} 文件。`;
    ui.showToast('success', '导出内容已生成');
  } catch (err) {
    exportFeedbackTone.value = 'error';
    exportFeedback.value = err.message || '导出内容生成失败，请稍后重试。';
    ui.showToast('error', exportFeedback.value);
  } finally {
    exportGenerating.value = false;
  }
}

async function downloadExport() {
  if (exportBusy.value) return;
  exportDownloading.value = true;
  exportFeedbackTone.value = 'info';
  exportFeedback.value = '正在打包并下载文件，请不要重复点击。';
  try {
    const response = await storyApi.exportDraftFile(route.params.draftId, exportForm);
    saveBlobResponse(response, exported.value?.filename || `短剧分场稿.${exportExtension(exportForm.format)}`);
    exportFeedbackTone.value = 'success';
    exportFeedback.value = '文件已开始下载，请查看浏览器下载记录。';
    ui.showToast('success', '文件已开始下载');
  } catch (err) {
    exportFeedbackTone.value = 'error';
    exportFeedback.value = err.message || '下载文件失败，请稍后重试。';
    ui.showToast('error', exportFeedback.value);
  } finally {
    exportDownloading.value = false;
  }
}

function exportFormatLabel(format) {
  return exportFormatOptions.find(option => option.value === format)?.label || String(format || '').toUpperCase();
}

function saveBlobResponse(response, fallbackFilename) {
  const disposition = response.headers?.['content-disposition'] || '';
  const filename = exportFilenameFromDisposition(disposition) || fallbackFilename;
  const url = URL.createObjectURL(response.data);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

function exportFilenameFromDisposition(disposition) {
  if (!disposition) return '';
  const starMatch = disposition.match(/filename\*=UTF-8''([^;]+)/i);
  if (starMatch?.[1]) return decodeURIComponent(starMatch[1].trim().replace(/^"|"$/g, ''));
  const plainMatch = disposition.match(/filename="?([^";]+)"?/i);
  if (!plainMatch?.[1]) return '';
  return decodeMimeEncodedFilename(plainMatch[1].trim());
}

function decodeMimeEncodedFilename(filename) {
  const normalized = filename.replace(/^=_/, '=?').replace(/_=$/, '?=').replace(/_Q_/i, '?Q?').replace(/_B_/i, '?B?');
  const match = normalized.match(/^=\?UTF-8\?([QB])\?(.+)\?=$/i);
  if (!match) return filename;
  if (match[1].toUpperCase() === 'B') {
    try {
      const binary = atob(match[2]);
      const bytes = Uint8Array.from(binary, char => char.charCodeAt(0));
      return new TextDecoder('utf-8').decode(bytes);
    } catch {
      return filename;
    }
  }
  const text = match[2].replace(/_/g, ' ').replace(/=([0-9A-F]{2})/gi, '%$1');
  try {
    return decodeURIComponent(text);
  } catch {
    return filename;
  }
}

function exportExtension(format) {
  return { markdown: 'md', md: 'md', html: 'html', pdf: 'pdf', txt: 'txt', docx: 'docx' }[format] || 'md';
}
</script>
