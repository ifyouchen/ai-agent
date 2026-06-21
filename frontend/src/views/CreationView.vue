<template>
  <div v-if="mode === 'home'" class="creation-view">
    <header class="creation-header">
      <div><h1>创作中心</h1><p>写小说、改小说、转短剧分场稿的一体化工作台。</p></div>
      <button class="creation-primary-btn" type="button" @click="quickCreate('long_novel')">新建作品</button>
    </header>
    <section class="creation-grid">
      <button v-for="item in shortcuts" :key="item.key" class="creation-action-card" type="button" @click="item.action">
        <span>{{ item.icon }}</span><strong>{{ item.title }}</strong><small>{{ item.desc }}</small>
      </button>
    </section>
    <section v-if="activeTask" class="creation-panel">
      <div class="creation-section-title">
        <h2>进行中任务</h2>
        <div class="creation-row">
          <button class="creation-secondary-btn" type="button" @click="refreshTask">刷新</button>
          <button class="creation-secondary-btn" type="button" :disabled="!taskCanCancel" @click="cancelTask">取消</button>
          <button class="creation-secondary-btn" type="button" :disabled="!taskCanRetry" @click="retryTask">重试</button>
        </div>
      </div>
      <p>{{ activeTask.currentStep || activeTask.status }} · {{ activeTask.progress || 0 }}%</p>
      <p v-if="activeTask.tokenUsage?.totalTokens" class="creation-muted">预估 Token：{{ activeTask.tokenUsage.totalTokens }}（输入 {{ activeTask.tokenUsage.inputTokens }} / 输出 {{ activeTask.tokenUsage.outputTokens }}）</p>
      <p v-if="activeTask.errorMessage" class="creation-risk">{{ activeTask.errorMessage }}</p>
    </section>
    <ProjectCards title="最近作品" :projects="projects.slice(0, 6)" :converting-project-id="scriptConvertingProjectId" :exporting-project-id="projectExportingId" @script="startScript" @export="downloadProjectExport" />
  </div>

  <div v-else-if="mode === 'projects'" class="creation-view">
    <header class="creation-header">
      <div><h1>作品库</h1><p>统一管理小说项目、改编项目和短剧脚本草稿。</p></div>
      <div class="creation-row">
        <button class="creation-secondary-btn" type="button" @click="showImport = true">导入文本/DOCX</button>
        <button class="creation-primary-btn" type="button" @click="showCreate = true">新建作品</button>
      </div>
    </header>
    <div class="creation-tabs">
      <button v-for="f in filters" :key="f.value" :class="{ active: filter === f.value }" type="button" @click="filter = f.value">{{ f.label }}</button>
    </div>
    <div class="creation-toolbar">
      <div class="creation-filters">
        <label>状态<select v-model="statusFilter"><option value="all">全部状态</option><option value="writing">写作中</option><option value="rewriting">改写中</option><option value="adapting">改编中</option><option value="completed">已完成</option></select></label>
        <label>排序<select v-model="sortOrder"><option value="updated_desc">最近更新</option><option value="updated_asc">最早更新</option><option value="title_asc">标题 A-Z</option></select></label>
      </div>
      <form class="creation-search" @submit.prevent="applyProjectSearch">
        <input v-model="searchInput" type="search" placeholder="搜索作品标题" />
        <button class="creation-primary-btn" type="submit">搜索</button>
        <button v-if="searchQuery" class="creation-secondary-btn" type="button" @click="clearProjectSearch">清空</button>
      </form>
    </div>
    <ProjectCards title="全部作品" :projects="filteredProjects" :converting-project-id="scriptConvertingProjectId" :exporting-project-id="projectExportingId" @script="startScript" @export="downloadProjectExport" />
  </div>

  <div v-else-if="mode === 'editor'" class="creation-workbench has-ai-config" :class="{ 'ai-config-collapsed': aiConfigCollapsed }">
    <aside class="creation-side">
      <router-link class="creation-back-link" to="/creation/projects">返回作品库</router-link>
      <h2>{{ project?.title || '作品编辑器' }}</h2>
      <div class="creation-side-section">
        <h3>创作资产</h3>
        <button
          v-for="asset in assetTypes"
          :key="asset.type"
          class="creation-list-btn"
          :class="{ active: editorPanel === 'asset' && activeAssetType === asset.type }"
          type="button"
          @click="selectAsset(asset.type)"
        >
          {{ asset.label }}
        </button>
      </div>
      <div class="creation-side-section">
        <h3>章节</h3>
        <button class="creation-primary-btn full" type="button" @click="addChapter">新增章节</button>
        <div
          v-for="chapter in chapters"
          :key="chapter.id"
          class="creation-list-btn creation-chapter-btn"
          :class="{ active: editorPanel === 'chapter' && chapter.id === currentChapter?.id, draft: hasChapterDraft(chapter) }"
          :title="chapterDisplayTitle(chapter)"
        >
          <button class="creation-chapter-main" type="button" @click="selectChapter(chapter)">
            <span class="creation-chapter-title">{{ chapterDisplayTitle(chapter) }}</span>
          </button>
          <span v-if="hasChapterDraft(chapter)" class="creation-draft-dot" title="有未保存草稿"></span>
          <button
            class="creation-chapter-delete"
            type="button"
            title="删除章节"
            :disabled="chapters.length <= 1 || chapterDeletingId === chapter.id"
            @click="deleteChapter(chapter)"
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
    <main v-if="editorPanel === 'asset'" class="creation-editor">
      <div class="creation-asset-head">
        <div>
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
        <span>{{ assetWordCount }} 字</span>
        <button type="button" :disabled="assetSaving" @click="saveAsset">{{ assetSaving ? '保存中...' : `保存${activeAssetLabel}` }}</button>
      </footer>
    </main>
    <main v-else class="creation-editor">
      <input v-model="chapterForm.title" class="creation-title-input" placeholder="章节标题" />
      <textarea v-model="chapterForm.content" class="creation-manuscript" placeholder="在这里写小说正文。"></textarea>
      <footer>
        <span>{{ wordCount }} 字</span>
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
            <select v-model="promptConfig.global.style">
              <option>爽文</option>
              <option>悬疑</option>
              <option>现实向</option>
              <option>轻喜剧</option>
              <option>虐恋</option>
            </select>
          </label>
          <label>
            <span>节奏</span>
            <select v-model="promptConfig.global.pace">
              <option>快节奏</option>
              <option>中等节奏</option>
              <option>慢热</option>
            </select>
          </label>
        </div>
        <label>
          <span>改写力度</span>
          <select v-model="promptConfig.global.rewriteStrength">
            <option>轻微</option>
            <option>中等</option>
            <option>大幅</option>
          </select>
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
            <select v-model="activeActionConfig.params.strength">
              <option>轻微</option>
              <option>中等</option>
              <option>强</option>
            </select>
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
        <button class="creation-secondary-btn full compact" type="button" :disabled="actionForm.loading" @click="savePromptConfig">保存 AI 配置</button>
        <label v-if="actionForm.loading || actionForm.result" class="creation-ai-result">
          <span>{{ activeAction.type === 'review' ? '审查建议' : 'AI 生成草稿' }}</span>
          <div v-if="actionForm.loading" class="creation-ai-pending">
            <span class="creation-spinner"></span>
            <strong>{{ activeAction.label }}生成中</strong>
            <small>AI 正在处理当前正文和配置，请稍候。生成完成后会进入这里，你再决定是否应用。</small>
          </div>
          <textarea v-else v-model="actionForm.result" rows="9"></textarea>
        </label>
        <div v-if="actionForm.result && !isAssetAction(activeAction.type) && activeAction.type !== 'review'" class="creation-row">
          <button class="creation-secondary-btn" type="button" @click="appendActionResult">追加到正文</button>
          <button class="creation-secondary-btn" type="button" @click="replaceChapterWithResult">替换正文</button>
        </div>
      </div>
    </aside>
    <aside class="creation-ai">
      <h3>AI 助手</h3>
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
      <button type="button" @click="startRewrite">改小说三栏对照</button>
      <button class="creation-primary-btn full" type="button" :disabled="scriptConvertingProjectId === project?.id" @click="startScript(project)">
        {{ scriptConvertingProjectId === project?.id ? '转短剧中...' : '转短剧' }}
      </button>
      <div class="creation-versions">
        <h3>版本快照</h3>
        <button v-for="version in chapterVersions" :key="version.id" type="button" @click="restoreVersion(version)">
          V{{ version.versionNo }} · {{ version.note || version.source }}
        </button>
        <p v-if="currentChapter && !chapterVersions.length">暂无快照</p>
      </div>
    </aside>
  </div>

  <div v-else-if="mode === 'rewrite'" class="creation-view">
    <header class="creation-header">
      <div><h1>改小说三栏对照</h1><p>原文、AI 改写稿和修改说明逐段确认。</p></div>
      <button class="creation-primary-btn" type="button" @click="acceptRewrite">保存为新版本</button>
    </header>
    <section class="creation-columns" v-if="rewriteTask">
      <article><h2>原文</h2><p v-for="(s, i) in rewriteTask.segments" :key="i">{{ s.source }}</p></article>
      <article><h2>AI 改写稿</h2><div v-for="(s, i) in rewriteTask.segments" :key="i"><textarea v-model="s.rewritten"></textarea><button type="button" @click="s.status = 'accepted'">接受</button><button type="button" @click="s.status = 'rejected'">拒绝</button></div></article>
      <article>
        <h2>修改说明</h2>
        <label class="creation-field">指定要求<textarea v-model="rewriteForm.instruction" rows="4" placeholder="例如：更口语、更狠一点、保留某个设定"></textarea></label>
        <button class="creation-secondary-btn full" type="button" @click="retryRewrite">按要求再改一次</button>
        <p v-for="(s, i) in rewriteTask.segments" :key="i">{{ s.note }}</p>
      </article>
    </section>
  </div>

  <div v-else-if="mode === 'script'" class="creation-workbench script-workbench">
    <aside class="creation-side">
      <router-link class="creation-back-link" to="/creation/projects">返回作品库</router-link>
      <h2>{{ draft?.title || '短剧改编' }}</h2>
      <button class="creation-primary-btn full" type="button" @click="qualityCheck">质检全稿</button>
      <div v-if="draft?.sourceChapters?.length" class="creation-side-section">
        <h3>原文章节</h3>
        <details v-for="source in draft.sourceChapters" :key="source.id" class="creation-source-item">
          <summary>{{ source.title }} · {{ source.wordCount }}字</summary>
          <p>{{ source.preview }}</p>
        </details>
      </div>
      <template v-for="ep in draft?.episodes || []" :key="ep.id">
        <h3>第{{ ep.episodeNo }}集</h3>
        <button class="creation-secondary-btn full compact" type="button" @click="addScene(ep)">新增场次</button>
        <button v-for="scene in ep.scenes" :key="scene.id" class="creation-list-btn" :class="{ active: scene.id === currentScene?.id }" type="button" @click="selectScene(ep, scene)">第{{ scene.sceneNo }}场</button>
      </template>
    </aside>
    <main class="creation-editor script-editor" v-if="currentScene">
      <input v-model="sceneForm.sceneTitle" class="creation-title-input" />
      <label>场景<input v-model="sceneForm.location" /></label>
      <label>人物<input v-model="sceneForm.characters" /></label>
      <label>本场功能<input v-model="sceneForm.sceneFunction" /></label>
      <label>画面<textarea v-model="sceneForm.visualAction"></textarea></label>
      <label>旁白<textarea v-model="sceneForm.narration"></textarea></label>
      <label>对白<textarea v-model="sceneForm.dialogue"></textarea></label>
      <label>表演/镜头<textarea v-model="sceneForm.performanceCameraNote"></textarea></label>
      <label>钩子<textarea v-model="sceneForm.hook"></textarea></label>
      <footer><span>{{ currentEpisode?.coreHook }}</span><button type="button" @click="saveScene">保存场次</button></footer>
    </main>
    <aside class="creation-ai">
      <h3>短剧助手</h3>
      <button type="button" @click="improveEpisode">重写本集</button>
      <button type="button" @click="improveScene('rewrite')">重写本场</button>
      <button type="button" @click="improveScene('hook')">补钩子</button>
      <button type="button" @click="improveScene('dialogue')">对白口语化</button>
      <button type="button" @click="improveScene('externalize')">心理外化</button>
      <button type="button" @click="moveScene('up')">上移场次</button>
      <button type="button" @click="moveScene('down')">下移场次</button>
      <button type="button" @click="deleteScene">删除场次</button>
      <router-link class="creation-primary-btn full" :to="`/creation/scripts/${route.params.draftId}/export`">导出预览</router-link>
      <div v-if="qualityReport" class="creation-quality"><strong>评分 {{ qualityReport.totalScore }}</strong><p v-for="x in qualityReport.mainIssues" :key="x">{{ x }}</p></div>
      <div v-if="adaptationPlanEntries.length" class="creation-quality">
        <strong>改编方案</strong>
        <p v-for="item in adaptationPlanEntries" :key="item.label">{{ item.label }}：{{ item.value }}</p>
      </div>
    </aside>
  </div>

  <div v-else-if="mode === 'export'" class="creation-view">
    <header class="creation-header">
      <div><h1>导出预览</h1><p>生成 Markdown/DOCX 导出内容。</p></div>
      <div class="creation-row">
        <button class="creation-secondary-btn" type="button" @click="downloadExport">下载文件</button>
        <button class="creation-primary-btn" type="button" @click="exportDraft">生成导出内容</button>
      </div>
    </header>
    <section class="creation-export">
      <aside class="creation-panel">
        <label>格式<select v-model="exportForm.format"><option value="md">Markdown</option><option value="html">HTML</option><option value="pdf">PDF</option><option value="txt">TXT</option><option value="docx">DOCX</option></select></label>
        <label>范围<select v-model="exportForm.scope"><option value="all">全部</option><option value="episode">选中集</option><option value="scene">选中场</option></select></label>
        <label v-if="exportForm.scope === 'episode'">集数<select v-model.number="exportForm.episodeNo"><option v-for="ep in draft?.episodes || []" :key="ep.id" :value="ep.episodeNo">第{{ ep.episodeNo }}集</option></select></label>
        <label v-if="exportForm.scope === 'scene'">场次<select v-model.number="exportForm.sceneId"><option v-for="scene in exportScenes" :key="scene.id" :value="scene.id">{{ scene.label }}</option></select></label>
        <label><input v-model="exportForm.includeQualityReport" type="checkbox" /> 包含质量报告</label>
        <label><input v-model="exportForm.includeAdaptationPlan" type="checkbox" /> 包含改编方案</label>
        <label><input v-model="exportForm.includeCharacterTable" type="checkbox" /> 包含人物表</label>
        <label><input v-model="exportForm.includeSceneDirectory" type="checkbox" /> 包含场次目录</label>
        <div class="creation-check-list">
          <strong>导出前检查 · {{ exportIssueCount }} 项待处理</strong>
          <p v-for="item in exportChecks" :key="item.label" :class="{ pass: item.pass }">{{ item.pass ? '通过' : '待修复' }}：{{ item.label }}</p>
        </div>
        <button class="creation-secondary-btn full" type="button" @click="router.push(`/creation/scripts/${route.params.draftId}`)">返回修复</button>
        <button class="creation-secondary-btn full" type="button" @click="autoFixExportIssues">AI 自动修复可修复项</button>
      </aside>
      <main class="creation-panel"><pre>{{ exported?.content || scriptPreview }}</pre></main>
    </section>
  </div>

  <div v-if="showCreate" class="creation-modal" @click.self="showCreate = false">
    <form class="creation-dialog" @submit.prevent="createProject"><h2>新建作品</h2><input v-model="createForm.title" required placeholder="作品名" /><select v-model="createForm.type"><option value="long_novel">长篇小说</option><option value="short_story">短篇故事</option><option value="adaptation">改编项目</option></select><textarea v-model="createForm.description" placeholder="简介"></textarea><button class="creation-primary-btn">创建</button></form>
  </div>
  <div v-if="showImport" class="creation-modal" @click.self="closeImportDialog">
    <form class="creation-dialog wide" @submit.prevent="previewImport">
      <div class="creation-dialog-head">
        <div>
          <h2>导入小说文本</h2>
          <p>上传 DOCX/TXT，或直接粘贴正文，系统会先解析章节再导入。</p>
        </div>
        <button class="creation-icon-btn" type="button" :disabled="importBusy" @click="closeImportDialog">×</button>
      </div>
      <input v-model="importForm.title" required placeholder="作品名" :disabled="importBusy" />
      <label class="creation-file-field">
        <span>上传文件</span>
        <span class="creation-file-picker">
          <input type="file" accept=".txt,.md,.doc,.docx" :disabled="importBusy" @change="onImportFileChange" />
          <span class="creation-file-button">选择文件</span>
          <span class="creation-file-name">{{ importFileRef?.name || '支持 .txt / .md / .doc / .docx' }}</span>
        </span>
      </label>
      <textarea v-model="importForm.content" rows="12" placeholder="或直接粘贴正文" :disabled="importBusy"></textarea>
      <div v-if="importPreviewLoading || importConfirming" class="creation-import-loading" aria-live="polite">
        <span class="creation-spinner dark"></span>
        <div>
          <strong>{{ importConfirming ? '正在导入作品' : '正在解析章节' }}</strong>
          <p>{{ importConfirming ? '作品库即将更新，并自动进入创作页面。' : '正在读取文件结构、识别章节和正文，请稍候。' }}</p>
        </div>
      </div>
      <section v-if="importPreview" class="creation-import-preview">
        <strong>{{ importPreview.detectedTypeLabel }} · {{ importPreview.wordCount }} 字 · {{ importPreview.chapterCount }} 章</strong>
        <p v-if="importPreview.truncated">仅展示前 20 章预览。</p>
        <div v-for="chapter in importPreview.chapters" :key="chapter.chapterNo">
          第{{ chapter.chapterNo }}章 · {{ chapter.title }} · {{ chapter.wordCount }}字
        </div>
      </section>
      <div class="creation-row">
        <button class="creation-secondary-btn" type="submit" :disabled="importBusy">
          <span v-if="importPreviewLoading" class="creation-spinner dark"></span>
          {{ importPreviewLoading ? '解析中...' : '解析预览' }}
        </button>
        <button class="creation-primary-btn" type="button" :disabled="!importPreview || importBusy" @click="confirmImport">
          <span v-if="importConfirming" class="creation-spinner"></span>
          {{ importConfirming ? '导入中...' : '确认导入' }}
        </button>
      </div>
    </form>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { storyApi } from '../services/storyApi.js';
import { useUiStore } from '../stores/ui.js';

const ProjectCards = defineComponent({
  props: { title: String, projects: Array, convertingProjectId: [String, Number], exportingProjectId: [String, Number] },
  emits: ['script', 'export'],
  setup(props, { emit }) {
    const projectExportFormats = reactive({});
    const exportOptions = [
      { value: 'md', label: 'MD' },
      { value: 'html', label: 'HTML' },
      { value: 'pdf', label: 'PDF' },
      { value: 'txt', label: 'TXT' },
    ];
    const selectedFormat = project => projectExportFormats[project.id] || 'md';
    return () => h('section', { class: 'creation-panel' }, [
      h('div', { class: 'creation-section-title' }, [h('h2', props.title)]),
      !props.projects?.length
        ? h('div', { class: 'creation-empty' }, '暂无作品')
        : h('div', { class: 'creation-projects' }, props.projects.map(project => h('article', { class: 'creation-project-card', key: project.id }, [
            h('span', project.typeLabel || project.type),
            h('h3', project.title),
            h('p', project.description || '还没有简介'),
            h('small', `${project.status || 'writing'} · ${project.chapterCount || 0}章 · ${project.scriptDraftCount || 0}个脚本`),
            h('div', { class: 'creation-row' }, [
              h('a', { href: `#/creation/projects/${project.id}/editor` }, '继续写作'),
              project.latestScriptDraftId ? h('a', { href: `#/creation/scripts/${project.latestScriptDraftId}` }, '查看脚本') : null,
              h('button', {
                type: 'button',
                disabled: props.convertingProjectId === project.id,
                onClick: () => emit('script', project),
              }, props.convertingProjectId === project.id ? '转短剧中...' : '转短剧'),
            ]),
            h('div', { class: 'creation-export-controls' }, [
              h('select', {
                class: 'creation-export-select',
                value: selectedFormat(project),
                disabled: props.exportingProjectId === project.id,
                onChange: event => { projectExportFormats[project.id] = event.target.value; },
              }, exportOptions.map(option => h('option', { value: option.value }, option.label))),
              h('button', {
                class: 'creation-secondary-btn',
                type: 'button',
                disabled: props.exportingProjectId === project.id,
                onClick: () => emit('export', { project, format: selectedFormat(project) }),
              }, props.exportingProjectId === project.id ? '导出中...' : '导出'),
            ]),
          ]))),
    ]);
  },
});

const route = useRoute();
const router = useRouter();
const ui = useUiStore();
const projects = ref([]);
const project = ref(null);
const chapters = ref([]);
const currentChapter = ref(null);
const rewriteTask = ref(null);
const draft = ref(null);
const currentEpisode = ref(null);
const currentScene = ref(null);
const qualityReport = ref(null);
const exported = ref(null);
const chapterVersions = ref([]);
const activeTask = ref(null);
const chapterSaving = ref(false);
const assetSaving = ref(false);
const chapterDeletingId = ref(null);
const scriptConvertingProjectId = ref(null);
const projectExportingId = ref(null);
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
const editorPanel = ref('chapter');
const activeAssetType = ref('setting');
const assetForm = reactive({ content: '', instruction: '' });
const activeActionType = ref('continue');
const aiConfigCollapsed = ref(false);
const actionForm = reactive({ instruction: '', result: '', loading: false, controller: null });
const chapterDrafts = reactive({});
const promptConfig = reactive(defaultPromptConfig());
const globalPreserveText = ref(promptConfig.global.preserve.join('、'));
const globalAvoidText = ref(promptConfig.global.avoid.join('、'));
const chapterForm = reactive({ title: '', content: '' });
const createForm = reactive({ title: '', type: 'long_novel', description: '' });
const importForm = reactive({ title: '', content: '' });
const rewriteForm = reactive({ instruction: '' });
const sceneForm = reactive({});
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
const filters = [{ value: 'all', label: '全部' }, { value: 'long_novel', label: '长篇' }, { value: 'short_story', label: '短篇' }, { value: 'adaptation', label: '改编' }, { value: 'short_drama', label: '短剧' }];
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
const activeAsset = computed(() => assetTypes.find(asset => asset.type === activeAssetType.value) || assetTypes[0]);
const activeAssetLabel = computed(() => activeAsset.value.label);
const activeAssetHint = computed(() => activeAsset.value.hint);
const activeAssetPlaceholder = computed(() => activeAsset.value.placeholder);
const activeAssetInstructionPlaceholder = computed(() => activeAsset.value.instructionPlaceholder);
const activeAction = computed(() => aiActions.find(action => action.type === activeActionType.value));
const activeActionConfig = computed(() => ensureActionConfig(activeActionType.value));
const scriptPreview = computed(() => (draft.value?.episodes || []).map(ep => `第${ep.episodeNo}集\n核心爽点：${ep.coreHook}\n结尾钩子：${ep.endingHook}`).join('\n\n'));
const exportScenes = computed(() => (draft.value?.episodes || []).flatMap(ep => (ep.scenes || []).map(scene => ({
  ...scene,
  label: `第${ep.episodeNo}集 第${scene.sceneNo}场 ${scene.sceneTitle || ''}`,
}))));
const taskCanCancel = computed(() => activeTask.value && !['completed', 'failed', 'canceled'].includes(activeTask.value.status));
const taskCanRetry = computed(() => activeTask.value && ['failed', 'canceled', 'completed'].includes(activeTask.value.status));
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
const shortcuts = [
  { key: 'long', icon: '长', title: '新建长篇', desc: '设定、人物、大纲、章节续写', action: () => quickCreate('long_novel') },
  { key: 'short', icon: '短', title: '新建短篇', desc: '情绪目标、反转、爆点和结尾', action: () => quickCreate('short_story') },
  { key: 'import', icon: '导', title: '导入小说', desc: '支持粘贴文本和 DOCX 文件', action: () => router.push('/creation/projects?import=1') },
  { key: 'rewrite', icon: '改', title: '改写文本', desc: '润色、去 AI 味、爽点增强', action: () => router.push('/creation/projects') },
  { key: 'script', icon: '剧', title: '转短剧', desc: '生成分集大纲和分场稿', action: () => router.push('/creation/projects') },
];

function selectedExportSceneCount(episodes) {
  if (exportForm.scope === 'episode') {
    return episodes.filter(ep => ep.episodeNo === exportForm.episodeNo).flatMap(ep => ep.scenes || []).length;
  }
  if (exportForm.scope === 'scene') {
    return exportScenes.value.some(scene => scene.id === exportForm.sceneId) ? 1 : 0;
  }
  return episodes.flatMap(ep => ep.scenes || []).length;
}

onMounted(loadByMode);
watch(() => route.fullPath, loadByMode);

async function loadByMode() {
  try {
    if (mode.value === 'projects' && route.query.import === '1') showImport.value = true;
    if (mode.value === 'home' || mode.value === 'projects') projects.value = await storyApi.listProjects();
    if (mode.value === 'home') await loadActiveTask();
    if (mode.value === 'editor') await loadProject();
    if (mode.value === 'rewrite') rewriteTask.value = await storyApi.getRewrite(route.params.taskId);
    if (mode.value === 'script' || mode.value === 'export') await loadDraft();
  } catch (err) {
    ui.showToast('error', err.message || '加载失败');
  }
}

async function quickCreate(type) {
  const p = await storyApi.createProject({ title: type === 'short_story' ? '未命名短篇' : '未命名长篇', type, description: '从这里开始创作。' });
  router.push(`/creation/projects/${p.id}/editor`);
}

async function createProject() {
  const p = await storyApi.createProject(createForm);
  showCreate.value = false;
  router.push(`/creation/projects/${p.id}/editor`);
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
  editorPanel.value = 'asset';
  activeAssetType.value = type;
  syncAssetForm();
}

function isAssetAction(action) {
  return ['setting', 'characters', 'outline'].includes(action);
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
  project.value = await storyApi.updateProject(project.value.id, { promptConfig: promptConfigPayload() });
  applyPromptConfig(project.value.promptConfig || project.value.metadata?.promptConfig);
  ui.showToast('success', 'AI 配置已保存');
}

function restoreActionPrompt() {
  const config = ensureActionConfig(activeActionType.value);
  config.userTemplate = '';
  config.useCustomPrompt = false;
  ui.showToast('success', '已恢复默认提示词');
}

function openActionPanel(type) {
  if (actionForm.loading) return;
  activeActionType.value = type;
  aiConfigCollapsed.value = false;
  actionForm.result = '';
  ensureActionConfig(type);
  if (isAssetAction(type)) {
    selectAsset(type);
    actionForm.instruction = assetForm.instruction || actionForm.instruction;
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
  if (!isAssetAction(activeActionType.value) && !chapterForm.content.trim() && activeActionType.value !== 'continue') {
    ui.showToast('warning', '请先输入正文');
    return;
  }
  if (isAssetAction(activeActionType.value) && !actionForm.instruction.trim() && !assetForm.content.trim()) {
    ui.showToast('warning', `请先填写${activeAssetLabel.value}要求，或手动输入${activeAssetLabel.value}内容`);
    return;
  }
  const config = ensureActionConfig(activeActionType.value);
  if (isAssetAction(activeActionType.value)) {
    assetForm.instruction = actionForm.instruction;
  }
  actionForm.result = '';
  actionForm.controller = new AbortController();
  actionForm.loading = true;
  try {
    const result = await storyApi.generate(project.value.id, {
      action: activeActionType.value,
      chapterId: currentChapter.value?.id,
      content: actionSource(),
      instruction: actionForm.instruction,
      params: config.params || {},
      useCustomPrompt: Boolean(config.useCustomPrompt),
    }, {
      signal: actionForm.controller.signal,
    });
    actionForm.result = result.content || '';
    if (isAssetAction(activeActionType.value)) {
      assetForm.content = [assetForm.content, actionForm.result].filter(Boolean).join(assetForm.content ? '\n\n' : '');
      ui.showToast('success', '已生成到资产编辑框，请确认后保存');
      return;
    }
    ui.showToast('success', activeActionType.value === 'review' ? '审查建议已生成' : 'AI 草稿已生成，请确认后应用');
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
  ui.showToast('success', '已追加到正文，记得保存章节');
}

function replaceChapterWithResult() {
  if (!actionForm.result.trim()) return;
  chapterForm.content = actionForm.result;
  actionForm.result = '';
  ui.showToast('success', '已替换正文，记得保存章节');
}

function syncAssetForm() {
  assetForm.content = projectAssets()[activeAssetType.value] || '';
}

async function addChapter() {
  cacheCurrentChapterDraft();
  const chapter = await storyApi.createChapter(project.value.id, { title: `第${chapters.value.length + 1}章`, content: '' });
  chapters.value.push(chapter);
  selectChapter(chapter, { skipCache: true });
}

async function saveChapter() {
  if (chapterSaving.value) return;
  if (!currentChapter.value) await addChapter();
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
  if (assetSaving.value) return;
  assetSaving.value = true;
  try {
    const assets = { ...projectAssets(), [activeAssetType.value]: assetForm.content };
    project.value = await storyApi.updateProject(project.value.id, { assets });
    ui.showToast('success', `${activeAssetLabel.value}已保存`);
  } catch (err) {
    ui.showToast('error', err.message || `${activeAssetLabel.value}保存失败`);
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
  const saved = await storyApi.restoreChapter(currentChapter.value.id, { versionId: version.id });
  Object.assign(currentChapter.value, saved);
  const index = chapters.value.findIndex(chapter => chapter.id === saved.id);
  if (index >= 0) chapters.value[index] = { ...chapters.value[index], ...saved };
  delete chapterDrafts[saved.id];
  chapterForm.title = saved.title;
  chapterForm.content = saved.content;
  await loadChapterVersions();
  ui.showToast('success', `已恢复到 V${version.versionNo}`);
}

async function generateAsset(type) {
  openActionPanel(type);
  actionForm.instruction = assetForm.instruction;
  await executeAction();
}

async function startRewrite() {
  if (!chapterForm.content.trim()) return ui.showToast('warning', '请先输入正文');
  const task = await storyApi.createRewrite({ projectId: project.value.id, chapterId: currentChapter.value?.id, sourceText: chapterForm.content, rewriteMode: 'deslop' });
  router.push(`/creation/rewrite/${task.id}`);
}

async function acceptRewrite() {
  const result = await storyApi.acceptRewrite(route.params.taskId, { segments: rewriteTask.value.segments });
  router.push(`/creation/projects/${result.projectId}/editor`);
}

async function retryRewrite() {
  const task = await storyApi.retryRewrite(route.params.taskId, {
    rewriteMode: rewriteTask.value?.rewriteMode || 'deslop',
    instruction: rewriteForm.instruction,
  });
  rewriteTask.value = task;
  ui.showToast('success', '已重新改写');
}

async function startScript(p) {
  if (!p?.id || scriptConvertingProjectId.value) return;
  const startPath = route.fullPath;
  scriptConvertingProjectId.value = p.id;
  ui.showToast('info', '已开始转短剧，请稍候');
  try {
    const task = await storyApi.convertToScript({ projectId: p.id, targetEpisodes: 20 });
    activeTask.value = task;
    localStorage.setItem('story:lastTaskId', String(task.id));
    if (route.fullPath === startPath) {
      router.push(`/creation/scripts/${task.draftId}`);
    } else {
      ui.showToast('success', '短剧分场稿已生成，可从作品卡片或任务入口打开');
    }
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

async function loadActiveTask() {
  const taskId = localStorage.getItem('story:lastTaskId');
  if (!taskId) return;
  try {
    activeTask.value = await storyApi.getTask(taskId);
  } catch {
    activeTask.value = null;
  }
}

async function refreshTask() {
  if (!activeTask.value?.id) return;
  activeTask.value = await storyApi.getTask(activeTask.value.id);
}

async function cancelTask() {
  if (!activeTask.value?.id) return;
  activeTask.value = await storyApi.cancelTask(activeTask.value.id);
}

async function retryTask() {
  if (!activeTask.value?.id) return;
  const task = await storyApi.retryTask(activeTask.value.id);
  activeTask.value = task;
  localStorage.setItem('story:lastTaskId', String(task.id));
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
  const saved = await storyApi.updateScene(currentScene.value.id, sceneForm);
  Object.assign(currentScene.value, saved);
  ui.showToast('success', '场次已保存');
}

async function addScene(ep) {
  const scene = await storyApi.createScene(ep.id, {
    sceneTitle: '新增场次',
    location: '内景｜待定｜日',
    characters: '',
    sceneFunction: '补充冲突推进',
  });
  ep.scenes = [...(ep.scenes || []), scene];
  selectScene(ep, scene);
  ui.showToast('success', '场次已新增');
}

async function deleteScene() {
  if (!currentScene.value || !currentEpisode.value) return;
  await storyApi.deleteScene(currentScene.value.id);
  currentEpisode.value.scenes = (currentEpisode.value.scenes || []).filter(scene => scene.id !== currentScene.value.id);
  currentEpisode.value.scenes.forEach((scene, index) => { scene.sceneNo = index + 1; });
  selectScene(currentEpisode.value, currentEpisode.value.scenes[0] || null);
  ui.showToast('success', '场次已删除');
}

async function moveScene(direction) {
  if (!currentScene.value || !currentEpisode.value) return;
  const scenes = await storyApi.moveScene(currentScene.value.id, { direction });
  currentEpisode.value.scenes = scenes;
  const moved = scenes.find(scene => scene.id === currentScene.value.id);
  selectScene(currentEpisode.value, moved || scenes[0] || null);
}

async function improveEpisode() {
  if (!currentEpisode.value) return;
  const saved = await storyApi.improveEpisode(currentEpisode.value.id, { action: 'rewrite', episode: currentEpisode.value });
  Object.assign(currentEpisode.value, saved);
  ui.showToast('success', '本集已重写');
}
async function improveScene(action) {
  if (!currentScene.value) return;
  const saved = await storyApi.improveScene(currentScene.value.id, { action, scene: sceneForm });
  Object.assign(currentScene.value, saved);
  Object.assign(sceneForm, saved);
  ui.showToast('success', '场次已更新');
}

async function qualityCheck() {
  qualityReport.value = await storyApi.checkQuality(route.params.draftId);
}

async function autoFixExportIssues() {
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
  ui.showToast(fixed ? 'success' : 'info', fixed ? `已修复 ${fixed} 个可自动处理的场次` : '没有发现可自动修复的空缺项');
}

async function exportDraft() {
  exported.value = await storyApi.exportDraft(route.params.draftId, exportForm);
}

async function downloadExport() {
  const response = await storyApi.exportDraftFile(route.params.draftId, exportForm);
  saveBlobResponse(response, exported.value?.filename || `短剧分场稿.${exportExtension(exportForm.format)}`);
}

function saveBlobResponse(response, fallbackFilename) {
  const disposition = response.headers?.['content-disposition'] || '';
  const match = disposition.match(/filename\*=UTF-8''([^;]+)|filename="?([^"]+)"?/i);
  const filename = match ? decodeURIComponent(match[1] || match[2]) : fallbackFilename;
  const url = URL.createObjectURL(response.data);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

function exportExtension(format) {
  return { markdown: 'md', md: 'md', html: 'html', pdf: 'pdf', txt: 'txt', docx: 'docx' }[format] || 'md';
}
</script>
