package com.example.aiagent.story;

import com.example.aiagent.story.entity.*;
import com.example.aiagent.story.mapper.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

@Service
@RequiredArgsConstructor
@Slf4j
public class StoryWorkspaceService {

    private static final String DEFAULT_TENANT = "default";

    private final StoryProjectMapper projectMapper;
    private final StoryChapterMapper chapterMapper;
    private final StoryChapterVersionMapper chapterVersionMapper;
    private final RewriteTaskMapper rewriteTaskMapper;
    private final ScriptDraftMapper draftMapper;
    private final ScriptEpisodeMapper episodeMapper;
    private final ScriptSceneMapper sceneMapper;
    private final GenerationTaskMapper generationTaskMapper;
    private final ObjectMapper objectMapper;
    private final StoryAiService storyAiService;
    private final StoryExportService storyExportService;
    private final StoryImportService storyImportService;
    @Resource(name = "sseTaskExecutor")
    private Executor rewriteTaskExecutor;
    private final Map<Long, FutureTask<Void>> runningRewriteTasks = new ConcurrentHashMap<>();
    private final Map<Long, FutureTask<Void>> runningGenerationTasks = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listProjects(String type) {
        return projectMapper.findByTenantId(DEFAULT_TENANT, type).stream()
                .map(this::projectMap)
                .toList();
    }

    @Transactional
    public Map<String, Object> createProject(Map<String, Object> payload) {
        String type = string(payload.get("type"), "long_novel");
        String title = string(payload.get("title"), "未命名作品");
        StoryProject project = StoryProject.builder()
                .tenantId(DEFAULT_TENANT)
                .title(title)
                .type(type)
                .status("writing")
                .description(string(payload.get("description"), ""))
                .metadata("{}")
                .createdBy("system")
                .build();
        projectMapper.insert(project);

        createChapter(project.getId(), Map.of(
                "title", "第1章",
                "content", defaultOpening(type)
        ));
        log.info("创建故事项目 title={} type={} projectId={}", title, type, project.getId());
        return getProject(project.getId());
    }

    @Transactional
    public Map<String, Object> importText(Map<String, Object> payload) {
        StoryImportService.StoryImportAnalysis analysis = storyImportService.analyzeText(
                string(payload.get("title"), "导入作品"),
                string(payload.get("content"), ""),
                "text"
        );
        Map<String, Object> project = createImportedProject(analysis);
        Long projectId = longValue(project.get("id"));
        log.info("导入文本生成作品 projectId={} detectedType={} wordCount={} chapterCount={}",
                projectId, analysis.detectedType(), analysis.wordCount(), analysis.units().size());
        return project;
    }

    public Map<String, Object> previewImportText(Map<String, Object> payload) {
        StoryImportService.StoryImportAnalysis analysis = storyImportService.analyzeText(
                string(payload.get("title"), "导入作品"),
                string(payload.get("content"), ""),
                "text"
        );
        Map<String, Object> preview = storyImportService.preview(analysis);
        log.info("预览文本导入 detectedType={} wordCount={} chapterCount={}",
                preview.get("detectedType"), preview.get("wordCount"), preview.get("chapterCount"));
        return preview;
    }

    @Transactional
    public Map<String, Object> importFile(MultipartFile file, String title) throws IOException {
        StoryImportService.StoryImportAnalysis analysis = storyImportService.analyzeFile(file, title);
        Map<String, Object> imported = createImportedProject(analysis);
        log.info("导入文件生成作品 projectId={} filename={} size={} detectedType={} wordCount={}",
                imported.get("id"), analysis.sourceName(), file.getSize(), analysis.detectedType(), analysis.wordCount());
        return imported;
    }

    public Map<String, Object> previewImportFile(MultipartFile file, String title) throws IOException {
        StoryImportService.StoryImportAnalysis analysis = storyImportService.analyzeFile(file, title);
        Map<String, Object> preview = storyImportService.preview(analysis);
        log.info("预览文件导入 filename={} size={} detectedType={} wordCount={} chapterCount={}",
                analysis.sourceName(), file.getSize(), preview.get("detectedType"), preview.get("wordCount"), preview.get("chapterCount"));
        return preview;
    }

    private Map<String, Object> createImportedProject(StoryImportService.StoryImportAnalysis analysis) {
        Map<String, Object> project = createProject(Map.of(
                "title", analysis.title(),
                "type", analysis.detectedType(),
                "description", "由导入文本生成"
        ));
        Long projectId = longValue(project.get("id"));
        List<StoryChapter> existing = chapterMapper.findByProjectId(projectId);
        List<StoryImportService.StoryImportUnit> units = analysis.units();
        if (!existing.isEmpty() && !units.isEmpty()) {
            StoryImportService.StoryImportUnit firstUnit = units.get(0);
            StoryChapter first = existing.get(0);
            first.setTitle(firstUnit.title());
            first.setContent(firstUnit.content());
            first.setWordCount(firstUnit.wordCount());
            first.setVersionNo(first.getVersionNo() + 1);
            first.setStatus("draft");
            chapterMapper.update(first);
            snapshotChapter(first, "import", "导入正文");
            for (int i = 1; i < units.size(); i++) {
                StoryImportService.StoryImportUnit unit = units.get(i);
                createChapter(projectId, Map.of(
                        "title", unit.title(),
                        "content", unit.content()
                ));
            }
        }
        return getProject(projectId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getProject(Long id) {
        StoryProject project = projectMapper.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("作品不存在：" + id));
        Map<String, Object> result = projectMap(project);
        result.put("chapters", chapterMapper.findByProjectId(id).stream().map(this::chapterMap).toList());
        result.put("scriptDrafts", draftMapper.findByProjectId(id).stream().map(this::draftSummaryMap).toList());
        return result;
    }

    @Transactional
    public Map<String, Object> updateProject(Long id, Map<String, Object> payload) {
        StoryProject project = projectMapper.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("作品不存在：" + id));
        if (payload.containsKey("title")) project.setTitle(string(payload.get("title"), project.getTitle()));
        if (payload.containsKey("description")) project.setDescription(string(payload.get("description"), project.getDescription()));
        if (payload.containsKey("status")) project.setStatus(string(payload.get("status"), project.getStatus()));
        if (payload.containsKey("assets") || payload.containsKey("promptConfig")) {
            Map<String, Object> metadata = fromJsonObject(project.getMetadata());
            if (payload.containsKey("assets")) metadata.put("assets", objectMap(payload.get("assets")));
            if (payload.containsKey("promptConfig")) metadata.put("promptConfig", objectMap(payload.get("promptConfig")));
            project.setMetadata(toJson(metadata));
        }
        projectMapper.update(project);
        log.info("更新故事项目 projectId={} 更新资产={} 更新AI配置={}",
                id, payload.containsKey("assets"), payload.containsKey("promptConfig"));
        return getProject(id);
    }

    @Transactional
    public Map<String, Object> deleteProject(Long id) {
        StoryProject project = projectMapper.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("作品不存在：" + id));
        projectMapper.deleteById(id);
        log.info("删除故事项目 projectId={} title={}", id, project.getTitle());
        return Map.of("deletedProjectId", id, "status", "deleted");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportProject(Long projectId, Map<String, Object> payload) {
        Map<String, Object> project = getProject(projectId);
        String format = storyExportService.normalizeFormat(string(payload.get("format"), "md"));
        String markdown = storyExportService.buildProjectMarkdown(project, payload);
        String content = storyExportService.previewContent(project.get("title"), markdown, format);
        log.info("预览导出作品 projectId={} format={} 字数={}", projectId, format, content.length());
        return Map.of(
                "projectId", projectId,
                "format", format,
                "filename", storyExportService.filename(project.get("title"), format),
                "content", content
        );
    }

    @Transactional(readOnly = true)
    public StoryExportFile exportProjectFile(Long projectId, Map<String, Object> payload) throws IOException {
        Map<String, Object> project = getProject(projectId);
        String format = storyExportService.normalizeFormat(string(payload.get("format"), "md"));
        String markdown = storyExportService.buildProjectMarkdown(project, payload);
        StoryExportFile file = storyExportService.buildFile(project.get("title"), markdown, format);
        log.info("下载导出作品 projectId={} format={} filename={} bytes={}", projectId, format, file.filename(), file.bytes().length);
        return file;
    }

    @Transactional
    public Map<String, Object> createChapter(Long projectId, Map<String, Object> payload) {
        projectMapper.findById(projectId).orElseThrow(() -> new IllegalArgumentException("作品不存在：" + projectId));
        Integer nextNo = chapterMapper.nextChapterNo(projectId);
        String content = string(payload.get("content"), "");
        StoryChapter chapter = StoryChapter.builder()
                .projectId(projectId)
                .title(string(payload.get("title"), "第" + nextNo + "章"))
                .chapterNo(nextNo)
                .content(content)
                .wordCount(wordCount(content))
                .versionNo(1)
                .status("draft")
                .build();
        chapterMapper.insert(chapter);
        snapshotChapter(chapter, "create", "创建章节初始版本");
        projectMapper.updateStatus(projectId, "writing");
        log.info("创建章节 projectId={} chapterId={} chapterNo={} wordCount={}",
                projectId, chapter.getId(), chapter.getChapterNo(), chapter.getWordCount());
        return chapterMap(chapter);
    }

    @Transactional
    public Map<String, Object> updateChapter(Long chapterId, Map<String, Object> payload) {
        StoryChapter chapter = chapterMapper.findById(chapterId)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在：" + chapterId));
        if (payload.containsKey("title")) chapter.setTitle(string(payload.get("title"), chapter.getTitle()));
        if (payload.containsKey("content")) chapter.setContent(string(payload.get("content"), chapter.getContent()));
        chapter.setWordCount(wordCount(chapter.getContent()));
        chapter.setVersionNo(chapter.getVersionNo() + 1);
        chapterMapper.update(chapter);
        snapshotChapter(chapter, string(payload.get("source"), "manual"), string(payload.get("note"), "保存章节"));
        projectMapper.updateStatus(chapter.getProjectId(), "writing");
        log.info("更新章节 projectId={} chapterId={} versionNo={} wordCount={} source={}",
                chapter.getProjectId(), chapterId, chapter.getVersionNo(), chapter.getWordCount(), string(payload.get("source"), "manual"));
        return chapterMap(chapter);
    }

    @Transactional
    public Map<String, Object> deleteChapter(Long chapterId) {
        StoryChapter chapter = chapterMapper.findById(chapterId)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在：" + chapterId));
        List<StoryChapter> projectChapters = chapterMapper.findByProjectId(chapter.getProjectId());
        if (projectChapters.size() <= 1) {
            throw new IllegalArgumentException("至少需要保留一个章节");
        }
        chapterMapper.deleteById(chapterId);
        projectMapper.updateStatus(chapter.getProjectId(), "writing");
        List<Map<String, Object>> remaining = chapterMapper.findByProjectId(chapter.getProjectId()).stream()
                .map(this::chapterMap)
                .toList();
        log.info("删除章节 projectId={} chapterId={} remainingCount={}",
                chapter.getProjectId(), chapterId, remaining.size());
        return Map.of(
                "projectId", chapter.getProjectId(),
                "deletedChapterId", chapterId,
                "chapters", remaining,
                "status", "deleted"
        );
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> chapterVersions(Long chapterId) {
        chapterMapper.findById(chapterId).orElseThrow(() -> new IllegalArgumentException("章节不存在：" + chapterId));
        return chapterVersionMapper.findByChapterId(chapterId).stream()
                .map(this::chapterVersionMap)
                .toList();
    }

    @Transactional
    public Map<String, Object> restoreChapter(Long chapterId, Map<String, Object> payload) {
        StoryChapter chapter = chapterMapper.findById(chapterId)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在：" + chapterId));
        StoryChapterVersion version = resolveVersion(chapterId, payload);
        chapter.setTitle(version.getTitle());
        chapter.setContent(version.getContent());
        chapter.setWordCount(version.getWordCount());
        chapter.setVersionNo(chapter.getVersionNo() + 1);
        chapterMapper.update(chapter);
        snapshotChapter(chapter, "restore", "恢复自版本 " + version.getVersionNo());
        projectMapper.updateStatus(chapter.getProjectId(), "writing");
        log.info("恢复章节版本 projectId={} chapterId={} restoredVersion={} newVersion={}",
                chapter.getProjectId(), chapterId, version.getVersionNo(), chapter.getVersionNo());
        return chapterMap(chapter);
    }

    public Map<String, Object> generate(Long projectId, Map<String, Object> payload) {
        Map<String, Object> request = payload == null ? Map.of() : payload;
        StoryProject project = projectMapper.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("作品不存在：" + projectId));
        String action = string(request.get("action"), "continue");
        String source = string(request.get("content"), "");
        String instruction = string(request.get("instruction"), "");
        Map<String, Object> metadata = fromJsonObject(project.getMetadata());
        Map<String, Object> promptConfig = objectMap(metadata.get("promptConfig"));
        Map<String, Object> assets = objectMap(metadata.get("assets"));
        Map<String, Object> actionConfig = objectMap(objectMap(promptConfig.get("actions")).get(action));
        Map<String, Object> params = new LinkedHashMap<>(objectMap(actionConfig.get("params")));
        params.putAll(objectMap(request.get("params")));
        boolean useCustomPrompt = booleanValue(request.get("useCustomPrompt")) || booleanValue(actionConfig.get("useCustomPrompt"));
        String fallback = generateFallback(action);
        String content = useFallback(request)
                ? fallback
                : storyAiService.generateWriting(
                        action,
                        project.getTitle(),
                        source,
                        fallback,
                        promptConfig,
                        assets,
                        instruction,
                        params,
                        actionConfig,
                        useCustomPrompt
                );
        log.info("执行创作AI projectId={} action={} 使用兜底={} 使用自定义提示={} instructionLength={} params={} sourceLength={} resultLength={}",
                projectId, action, useFallback(request), useCustomPrompt, instruction.length(), safeLogMap(params), source.length(), content.length());
        return Map.of("projectId", projectId, "action", action, "content", content);
    }

    @Transactional
    public Map<String, Object> createRewrite(Map<String, Object> payload) {
        Long projectId = longValue(payload.get("projectId"));
        projectMapper.findById(projectId).orElseThrow(() -> new IllegalArgumentException("作品不存在：" + projectId));
        String source = string(payload.get("sourceText"), "");
        String rewriteMode = string(payload.get("rewriteMode"), "deslop");
        String instruction = string(payload.get("instruction"), "");
        List<Map<String, Object>> segments = splitParagraphs(source).stream()
                .map(text -> rewriteSegmentSkeleton(text))
                .toList();

        RewriteTask task = RewriteTask.builder()
                .projectId(projectId)
                .chapterId(nullableLong(payload.get("chapterId")))
                .sourceType("chapter")
                .sourceText(source)
                .rewriteMode(rewriteMode)
                .instruction(instruction)
                .status("pending")
                .segmentsJson(toJson(segments))
                .resultText("")
                .diffPayload(toJson(rewriteMeta(0, "排队中", null, rewriteSummaryNote(rewriteMode))))
                .completedAt(null)
                .build();
        rewriteTaskMapper.insert(task);
        projectMapper.updateStatus(projectId, "rewriting");
        log.info("提交改写任务 projectId={} chapterId={} taskId={} mode={} segmentCount={} sourceLength={}",
                projectId, task.getChapterId(), task.getId(), rewriteMode, segments.size(), source.length());
        startRewriteTask(task.getId(), useFallback(payload));
        return rewriteTaskMap(task);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRewrite(Long id) {
        return rewriteTaskMap(rewriteTaskMapper.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("改写任务不存在：" + id)));
    }

    @Transactional
    public Map<String, Object> acceptRewrite(Long id, Map<String, Object> payload) {
        RewriteTask task = rewriteTaskMapper.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("改写任务不存在：" + id));
        if (!List.of("completed", "accepted").contains(task.getStatus())) {
            throw new IllegalArgumentException("改写任务尚未完成，不能保存为新版本");
        }
        List<Map<String, Object>> segments = segmentsFrom(payload.get("segments") == null ? task.getSegmentsJson() : payload.get("segments"));
        String content = rewriteResultText(segments);
        if (task.getChapterId() != null) {
            updateChapter(task.getChapterId(), Map.of("content", content, "source", "rewrite_accept", "note", "接受改写任务 " + id));
        }
        task.setStatus("accepted");
        task.setSegmentsJson(toJson(segments));
        task.setResultText(content);
        task.setDiffPayload(toJson(rewriteMeta(100, "已保存为新版本", null, rewriteSummaryNote(task.getRewriteMode()))));
        task.setCompletedAt(Instant.now());
        rewriteTaskMapper.update(task);
        log.info("接受改写结果 projectId={} chapterId={} taskId={} segmentCount={}",
                task.getProjectId(), task.getChapterId(), id, segments.size());
        return Map.of("projectId", task.getProjectId(), "taskId", id, "status", "accepted");
    }

    @Transactional
    public Map<String, Object> retryRewrite(Long id, Map<String, Object> payload) {
        RewriteTask old = rewriteTaskMapper.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("改写任务不存在：" + id));
        log.info("重新改写 oldTaskId={} projectId={} chapterId={} mode={}",
                id, old.getProjectId(), old.getChapterId(), string(payload.get("rewriteMode"), old.getRewriteMode()));
        return createRewrite(Map.of(
                "projectId", old.getProjectId(),
                "chapterId", old.getChapterId() == null ? "" : old.getChapterId(),
                "sourceText", old.getSourceText(),
                "rewriteMode", string(payload.get("rewriteMode"), old.getRewriteMode()),
                "instruction", string(payload.get("instruction"), old.getInstruction())
        ));
    }

    @Transactional
    public Map<String, Object> cancelRewrite(Long id) {
        RewriteTask task = rewriteTaskMapper.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("改写任务不存在：" + id));
        if (List.of("completed", "accepted", "failed", "canceled").contains(task.getStatus())) {
            return rewriteTaskMap(task);
        }
        FutureTask<Void> future = runningRewriteTasks.remove(id);
        if (future != null) future.cancel(true);
        task.setStatus("canceled");
        task.setDiffPayload(toJson(rewriteMeta(
                progressFromMeta(task.getDiffPayload()),
                "用户已终止改写",
                null,
                rewriteSummaryNote(task.getRewriteMode())
        )));
        task.setCompletedAt(Instant.now());
        rewriteTaskMapper.update(task);
        log.info("取消改写任务 taskId={} projectId={} chapterId={}", id, task.getProjectId(), task.getChapterId());
        return rewriteTaskMap(task);
    }

    private void startRewriteTask(Long taskId, boolean forceFallback) {
        Runnable start = () -> {
            try {
                FutureTask<Void> future = new FutureTask<>(() -> {
                    try {
                        runRewriteTask(taskId, forceFallback);
                    } finally {
                        runningRewriteTasks.remove(taskId);
                    }
                    return null;
                });
                runningRewriteTasks.put(taskId, future);
                rewriteTaskExecutor.execute(future);
            } catch (RejectedExecutionException e) {
                runningRewriteTasks.remove(taskId);
                log.warn("改写任务线程池已满 taskId={}", taskId);
                markRewriteFailed(taskId, "服务繁忙，请稍后重试");
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    start.run();
                }
            });
        } else {
            start.run();
        }
    }

    private void runRewriteTask(Long taskId, boolean forceFallback) {
        RewriteTask task = rewriteTaskMapper.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("改写任务不存在：" + taskId));
        if ("canceled".equals(task.getStatus()) || Thread.currentThread().isInterrupted()) {
            markRewriteCanceled(taskId, task);
            return;
        }
        List<Map<String, Object>> segments = new ArrayList<>(segmentsFrom(task.getSegmentsJson()));
        int total = Math.max(segments.size(), 1);
        task.setStatus("running");
        task.setDiffPayload(toJson(rewriteMeta(1, "开始逐段改写", null, rewriteSummaryNote(task.getRewriteMode()))));
        rewriteTaskMapper.update(task);
        try {
            for (int i = 0; i < segments.size(); i++) {
                ensureRewriteNotCanceled(taskId);
                Map<String, Object> segment = new LinkedHashMap<>(segments.get(i));
                String source = string(segment.get("source"), "");
                String fallback = rewriteText(source, task.getRewriteMode());
                String rewritten = forceFallback
                        ? fallback
                        : storyAiService.rewriteSegment(source, task.getRewriteMode(), task.getInstruction(), fallback);
                ensureRewriteNotCanceled(taskId);
                segment.put("rewritten", rewritten);
                segment.put("status", "accepted");
                segment.put("note", "");
                segments.set(i, segment);
                int progress = Math.min(95, Math.max(5, ((i + 1) * 95) / total));
                task.setSegmentsJson(toJson(segments));
                task.setResultText(rewriteResultText(segments));
                task.setDiffPayload(toJson(rewriteMeta(
                        progress,
                        "正在改写第 " + (i + 1) + " / " + segments.size() + " 段",
                        null,
                        rewriteSummaryNote(task.getRewriteMode())
                )));
                rewriteTaskMapper.update(task);
            }
            ensureRewriteNotCanceled(taskId);
            task.setStatus("completed");
            task.setSegmentsJson(toJson(segments));
            task.setResultText(rewriteResultText(segments));
            task.setDiffPayload(toJson(rewriteMeta(100, "改写完成，可进入三栏对照审核", null, rewriteSummaryNote(task.getRewriteMode()))));
            task.setCompletedAt(Instant.now());
            rewriteTaskMapper.update(task);
            log.info("改写任务完成 taskId={} projectId={} segmentCount={}", taskId, task.getProjectId(), segments.size());
        } catch (CancellationException e) {
            markRewriteCanceled(taskId, task);
        } catch (Exception e) {
            log.error("改写任务失败 taskId={}", taskId, e);
            task.setStatus("failed");
            task.setDiffPayload(toJson(rewriteMeta(
                    progressFromMeta(task.getDiffPayload()),
                    "改写失败",
                    e.getMessage(),
                    rewriteSummaryNote(task.getRewriteMode())
            )));
            task.setCompletedAt(Instant.now());
            rewriteTaskMapper.update(task);
        }
    }

    private void ensureRewriteNotCanceled(Long taskId) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("改写任务已取消");
        }
        rewriteTaskMapper.findById(taskId).ifPresent(task -> {
            if ("canceled".equals(task.getStatus())) {
                throw new CancellationException("改写任务已取消");
            }
        });
    }

    private void markRewriteCanceled(Long taskId, RewriteTask task) {
        task.setStatus("canceled");
        task.setDiffPayload(toJson(rewriteMeta(
                progressFromMeta(task.getDiffPayload()),
                "用户已终止改写",
                null,
                rewriteSummaryNote(task.getRewriteMode())
        )));
        task.setCompletedAt(Instant.now());
        rewriteTaskMapper.update(task);
        log.info("改写任务已终止 taskId={} projectId={}", taskId, task.getProjectId());
    }

    private void markRewriteFailed(Long taskId, String message) {
        rewriteTaskMapper.findById(taskId).ifPresent(task -> {
            task.setStatus("failed");
            task.setDiffPayload(toJson(rewriteMeta(0, "改写失败", message, rewriteSummaryNote(task.getRewriteMode()))));
            task.setCompletedAt(Instant.now());
            rewriteTaskMapper.update(task);
        });
    }

    @Transactional
    public Map<String, Object> convertToScript(Map<String, Object> payload) {
        Long projectId = longValue(payload.get("projectId"));
        projectMapper.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("作品不存在：" + projectId));
        int targetEpisodes = intValue(payload.get("targetEpisodes"), 3);
        GenerationTask task = GenerationTask.builder()
                .taskType("script_convert")
                .projectId(projectId)
                .status("pending")
                .progress(0)
                .currentStep("排队中")
                .errorMessage(null)
                .tokenUsage("{}")
                .build();
        generationTaskMapper.insert(task);
        projectMapper.updateStatus(projectId, "adapting");
        log.info("提交转短剧任务 projectId={} taskId={} targetEpisodes={} 使用兜底={}",
                projectId, task.getId(), targetEpisodes, useFallback(payload));
        startScriptConvertTask(task.getId(), projectId, targetEpisodes, useFallback(payload));
        return generationTaskMap(task);
    }

    private void startScriptConvertTask(Long taskId, Long projectId, int targetEpisodes, boolean forceFallback) {
        Runnable start = () -> {
            try {
                FutureTask<Void> future = new FutureTask<>(() -> {
                    try {
                        runScriptConvertTask(taskId, projectId, targetEpisodes, forceFallback);
                    } finally {
                        runningGenerationTasks.remove(taskId);
                    }
                    return null;
                });
                runningGenerationTasks.put(taskId, future);
                rewriteTaskExecutor.execute(future);
            } catch (RejectedExecutionException e) {
                runningGenerationTasks.remove(taskId);
                log.warn("转短剧任务线程池已满 taskId={}", taskId);
                markGenerationFailed(taskId, "服务繁忙，请稍后重试");
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    start.run();
                }
            });
        } else {
            start.run();
        }
    }

    private void runScriptConvertTask(Long taskId, Long projectId, int targetEpisodes, boolean forceFallback) {
        GenerationTask task = generationTaskMapper.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("生成任务不存在：" + taskId));
        Long draftId = null;
        try {
            ensureGenerationNotCanceled(taskId);
            task.setStatus("running");
            task.setProgress(5);
            task.setCurrentStep("正在读取小说正文");
            task.setErrorMessage(null);
            generationTaskMapper.update(task);

            StoryProject project = projectMapper.findById(projectId)
                    .orElseThrow(() -> new IllegalArgumentException("作品不存在：" + projectId));
            String sourceText = projectSourceText(projectId);
            Map<String, Object> fallback = fallbackScriptDraft(project.getTitle(), sourceText, targetEpisodes);
            ensureGenerationNotCanceled(taskId);

            task.setProgress(15);
            task.setCurrentStep("正在生成改编方案和分集");
            generationTaskMapper.update(task);

            Map<String, Object> generated = forceFallback
                    ? fallback
                    : storyAiService.generateScriptDraft(project.getTitle(), sourceText, targetEpisodes, fallback);
            ensureGenerationNotCanceled(taskId);

            @SuppressWarnings("unchecked")
            Map<String, Object> adaptationPlan = generated.get("adaptationPlan") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map
                    : fallbackAdaptationPlan();
            List<Map<String, Object>> generatedEpisodes = mapList(generated.get("episodes"));
            if (generatedEpisodes.isEmpty()) {
                generatedEpisodes = mapList(fallbackScriptDraft(project.getTitle(), sourceText, targetEpisodes).get("episodes"));
            }

            task.setProgress(65);
            task.setCurrentStep("正在保存短剧分场稿");
            task.setTokenUsage(estimateTokenUsage(sourceText, generatedEpisodes));
            generationTaskMapper.update(task);

            ScriptDraft draft = ScriptDraft.builder()
                    .projectId(projectId)
                    .title(project.getTitle() + " - 短剧分场稿")
                    .sourceRef("project:" + projectId)
                    .episodeCount(generatedEpisodes.size())
                    .status("draft")
                    .qualityScore(0)
                    .adaptationPlan(toJson(adaptationPlan))
                    .qualityReport("{}")
                    .build();
            draftMapper.insert(draft);
            draftId = draft.getId();

            int episodeIndex = 1;
            for (Map<String, Object> episodePayload : generatedEpisodes) {
                ensureGenerationNotCanceled(taskId);
                ScriptEpisode episode = buildEpisode(draft.getId(), episodePayload, episodeIndex);
                episodeMapper.insert(episode);
                List<Map<String, Object>> scenes = mapList(episodePayload.get("scenes"));
                if (scenes.isEmpty()) scenes = mapList(fallbackEpisode(episodeIndex, episodePayload).get("scenes"));
                int sceneIndex = 1;
                for (Map<String, Object> scenePayload : scenes) {
                    ensureGenerationNotCanceled(taskId);
                    ScriptScene scene = buildScene(episode.getId(), scenePayload, sceneIndex, episodePayload);
                    sceneMapper.insert(scene);
                    sceneIndex++;
                }
                int progress = Math.min(95, 65 + (episodeIndex * 30) / Math.max(generatedEpisodes.size(), 1));
                task.setProgress(progress);
                task.setCurrentStep("正在保存第 " + episodeIndex + " / " + generatedEpisodes.size() + " 集");
                generationTaskMapper.update(task);
                episodeIndex++;
            }

            ensureGenerationNotCanceled(taskId);
            task.setStatus("completed");
            task.setProgress(100);
            task.setCurrentStep("短剧分场稿已生成");
            task.setErrorMessage(null);
            generationTaskMapper.update(task);
            projectMapper.updateStatus(projectId, "adapting");
            log.info("转短剧完成 projectId={} taskId={} draftId={} targetEpisodes={} generatedEpisodes={} 使用兜底={} sourceLength={}",
                    projectId, taskId, draft.getId(), targetEpisodes, generatedEpisodes.size(), forceFallback, sourceText.length());
        } catch (CancellationException e) {
            cleanupScriptDraft(draftId);
            markGenerationCanceled(taskId);
        } catch (Exception e) {
            cleanupScriptDraft(draftId);
            log.error("转短剧任务失败 taskId={} projectId={}", taskId, projectId, e);
            markGenerationFailed(taskId, e.getMessage());
        }
    }

    private void cleanupScriptDraft(Long draftId) {
        if (draftId == null) return;
        try {
            draftMapper.deleteById(draftId);
            log.info("清理未完成短剧草稿 draftId={}", draftId);
        } catch (Exception cleanupError) {
            log.warn("清理未完成短剧草稿失败 draftId={} error={}", draftId, cleanupError.getMessage());
        }
    }

    private void ensureGenerationNotCanceled(Long taskId) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("生成任务已取消");
        }
        generationTaskMapper.findById(taskId).ifPresent(task -> {
            if ("canceled".equals(task.getStatus())) {
                throw new CancellationException("生成任务已取消");
            }
        });
    }

    private void markGenerationCanceled(Long taskId) {
        generationTaskMapper.findById(taskId).ifPresent(task -> {
            if ("completed".equals(task.getStatus())) return;
            task.setStatus("canceled");
            task.setProgress(task.getProgress() == null ? 0 : task.getProgress());
            task.setCurrentStep("用户已终止任务");
            task.setErrorMessage(null);
            generationTaskMapper.update(task);
            log.info("生成任务已终止 taskId={} projectId={} taskType={}",
                    taskId, task.getProjectId(), task.getTaskType());
        });
    }

    private void markGenerationFailed(Long taskId, String message) {
        generationTaskMapper.findById(taskId).ifPresent(task -> {
            if ("canceled".equals(task.getStatus())) return;
            task.setStatus("failed");
            task.setProgress(task.getProgress() == null ? 0 : task.getProgress());
            task.setCurrentStep("任务失败");
            task.setErrorMessage(string(message, "生成失败"));
            generationTaskMapper.update(task);
        });
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getScriptTask(Long taskId) {
        GenerationTask task = generationTaskMapper.findById(taskId)
                .orElse(GenerationTask.builder().id(taskId).status("completed").progress(100).currentStep("任务已完成").build());
        return generationTaskMap(task);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getGenerationTask(Long taskId) {
        GenerationTask task = generationTaskMapper.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("生成任务不存在：" + taskId));
        return generationTaskMap(task);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listTaskHistory(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<Map<String, Object>> generationTasks = generationTaskMapper.findRecent(safeLimit).stream()
                .map(this::generationTaskHistoryMap)
                .toList();
        List<Map<String, Object>> rewriteTasks = rewriteTaskMapper.findRecent(safeLimit).stream()
                .map(this::rewriteTaskHistoryMap)
                .toList();
        List<Map<String, Object>> tasks = new ArrayList<>();
        tasks.addAll(generationTasks);
        tasks.addAll(rewriteTasks);
        tasks.sort((a, b) -> String.valueOf(b.get("updatedAt")).compareTo(String.valueOf(a.get("updatedAt"))));
        if (tasks.size() > safeLimit) {
            tasks = new ArrayList<>(tasks.subList(0, safeLimit));
        }
        return Map.of("tasks", tasks, "limit", safeLimit);
    }

    @Transactional
    public Map<String, Object> cancelGenerationTask(Long taskId) {
        GenerationTask task = generationTaskMapper.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("生成任务不存在：" + taskId));
        if (List.of("completed", "failed", "canceled").contains(task.getStatus())) {
            return generationTaskMap(task);
        }
        FutureTask<Void> future = runningGenerationTasks.remove(taskId);
        if (future != null) future.cancel(true);
        task.setStatus("canceled");
        task.setProgress(task.getProgress() == null ? 0 : task.getProgress());
        task.setCurrentStep("用户已终止任务");
        task.setErrorMessage(null);
        generationTaskMapper.update(task);
        log.info("取消生成任务 taskId={} projectId={} taskType={} status={}",
                taskId, task.getProjectId(), task.getTaskType(), task.getStatus());
        return generationTaskMap(task);
    }

    @Transactional
    public Map<String, Object> retryGenerationTask(Long taskId) {
        GenerationTask old = generationTaskMapper.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("生成任务不存在：" + taskId));
        if ("script_convert".equals(old.getTaskType())) {
            log.info("重试生成任务 taskId={} projectId={} taskType={}",
                    taskId, old.getProjectId(), old.getTaskType());
            return convertToScript(Map.of("projectId", old.getProjectId()));
        }
        GenerationTask retry = GenerationTask.builder()
                .taskType(old.getTaskType())
                .projectId(old.getProjectId())
                .status("failed")
                .progress(0)
                .currentStep("暂不支持该任务类型重试")
                .errorMessage("unsupported_task_type")
                .tokenUsage(estimateTokenUsage("", List.of()))
                .build();
        generationTaskMapper.insert(retry);
        log.info("不支持重试的生成任务 oldTaskId={} retryTaskId={} taskType={}",
                taskId, retry.getId(), old.getTaskType());
        return generationTaskMap(retry);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDraft(Long draftId) {
        ScriptDraft draft = draftMapper.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("脚本草稿不存在：" + draftId));
        return draftMap(draft);
    }

    @Transactional
    public Map<String, Object> improveEpisode(Long episodeId, Map<String, Object> payload) {
        ScriptEpisode episode = episodeMapper.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("分集不存在：" + episodeId));
        Map<String, Object> current = episodeMap(episode);
        if (payload.get("episode") instanceof Map<?, ?> episodePayload) {
            episodePayload.forEach((key, value) -> current.put(String.valueOf(key), value));
        }
        Map<String, Object> improved = fallbackEpisodeImprovement(current, string(payload.get("action"), "rewrite"));
        applyEpisodePayload(episode, improved);
        episodeMapper.update(episode);
        log.info("AI优化分集 episodeId={} draftId={} action={}",
                episodeId, episode.getDraftId(), string(payload.get("action"), "rewrite"));
        return episodeMap(episode);
    }
    @Transactional
    public Map<String, Object> createScene(Long episodeId, Map<String, Object> payload) {
        Integer nextNo = sceneMapper.nextSceneNo(episodeId);
        ScriptScene scene = buildScene(episodeId, payload == null ? Map.of() : payload, nextNo);
        scene.setSceneNo(nextNo);
        sceneMapper.insert(scene);
        log.info("创建短剧场次 episodeId={} sceneId={} sceneNo={}",
                episodeId, scene.getId(), scene.getSceneNo());
        return sceneMap(scene);
    }

    @Transactional
    public Map<String, Object> updateScene(Long sceneId, Map<String, Object> payload) {
        ScriptScene scene = sceneMapper.findById(sceneId)
                .orElseThrow(() -> new IllegalArgumentException("场次不存在：" + sceneId));
        applyScenePayload(scene, payload);
        sceneMapper.update(scene);
        log.info("更新短剧场次 episodeId={} sceneId={} sceneNo={}",
                scene.getEpisodeId(), sceneId, scene.getSceneNo());
        return sceneMap(scene);
    }

    @Transactional
    public Map<String, Object> deleteScene(Long sceneId) {
        ScriptScene scene = sceneMapper.findById(sceneId)
                .orElseThrow(() -> new IllegalArgumentException("场次不存在：" + sceneId));
        Long episodeId = scene.getEpisodeId();
        sceneMapper.deleteById(sceneId);
        renumberScenes(episodeId);
        log.info("删除短剧场次 episodeId={} sceneId={}", episodeId, sceneId);
        return Map.of("episodeId", episodeId, "deletedSceneId", sceneId, "status", "deleted");
    }

    @Transactional
    public List<Map<String, Object>> moveScene(Long sceneId, Map<String, Object> payload) {
        ScriptScene scene = sceneMapper.findById(sceneId)
                .orElseThrow(() -> new IllegalArgumentException("场次不存在：" + sceneId));
        List<ScriptScene> scenes = sceneMapper.findByEpisodeId(scene.getEpisodeId());
        int index = -1;
        for (int i = 0; i < scenes.size(); i++) {
            if (sceneId.equals(scenes.get(i).getId())) {
                index = i;
                break;
            }
        }
        if (index < 0) return scenes.stream().map(this::sceneMap).toList();
        String direction = string(payload.get("direction"), "down");
        int target = "up".equals(direction) ? index - 1 : index + 1;
        if (target < 0 || target >= scenes.size()) return scenes.stream().map(this::sceneMap).toList();
        ScriptScene current = scenes.get(index);
        ScriptScene other = scenes.get(target);
        Integer currentNo = current.getSceneNo();
        sceneMapper.updateSceneNo(current.getId(), other.getSceneNo());
        sceneMapper.updateSceneNo(other.getId(), currentNo);
        log.info("移动短剧场次 episodeId={} sceneId={} direction={} fromIndex={} toIndex={}",
                scene.getEpisodeId(), sceneId, direction, index, target);
        return sceneMapper.findByEpisodeId(scene.getEpisodeId()).stream().map(this::sceneMap).toList();
    }

    @Transactional
    public Map<String, Object> improveScene(Long sceneId, Map<String, Object> payload) {
        ScriptScene scene = sceneMapper.findById(sceneId)
                .orElseThrow(() -> new IllegalArgumentException("场次不存在：" + sceneId));
        Map<String, Object> current = sceneMap(scene);
        if (payload.get("scene") instanceof Map<?, ?> scenePayload) {
            scenePayload.forEach((key, value) -> current.put(String.valueOf(key), value));
        }
        String instruction = string(payload.get("instruction"), "");
        Map<String, Object> improved = storyAiService.improveScene(
                string(payload.get("action"), "rewrite"),
                current,
                instruction,
                fallbackSceneImprovement(current, string(payload.get("action"), "rewrite"))
        );
        applyScenePayload(scene, improved);
        sceneMapper.update(scene);
        log.info("AI优化短剧场次 episodeId={} sceneId={} action={}",
                scene.getEpisodeId(), sceneId, string(payload.get("action"), "rewrite"));
        return sceneMap(scene);
    }

    private void renumberScenes(Long episodeId) {
        List<ScriptScene> scenes = sceneMapper.findByEpisodeId(episodeId);
        for (int i = 0; i < scenes.size(); i++) {
            int nextNo = i + 1;
            if (scenes.get(i).getSceneNo() == null || scenes.get(i).getSceneNo() != nextNo) {
                sceneMapper.updateSceneNo(scenes.get(i).getId(), nextNo);
            }
        }
    }

    @Transactional
    public Map<String, Object> qualityCheck(Long draftId, Map<String, Object> payload) {
        ScriptDraft draft = draftMapper.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("脚本草稿不存在：" + draftId));
        Map<String, Object> draftMap = draftMap(draft);
        Map<String, Object> fallback = fallbackQualityReport(draftId);
        Map<String, Object> report = useFallback(payload)
                ? fallback
                : storyAiService.qualityCheck(storyExportService.buildDraftMarkdown(draftMap, Map.of("includeQualityReport", false)), fallback);
        report.put("draftId", draftId);
        int score = intValue(report.get("totalScore"), 86);
        draft.setQualityScore(score);
        draft.setQualityReport(toJson(report));
        draftMapper.update(draft);
        log.info("质检短剧草稿 draftId={} projectId={} score={} 使用兜底={}", draftId, draft.getProjectId(), score, useFallback(payload));
        return report;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportDraft(Long draftId, Map<String, Object> payload) {
        Map<String, Object> draft = getDraft(draftId);
        String format = storyExportService.normalizeFormat(string(payload.get("format"), "md"));
        String markdown = storyExportService.buildDraftMarkdown(draft, payload);
        String content = storyExportService.previewContent(draft.get("title"), markdown, format);
        log.info("预览导出短剧 draftId={} format={} scope={} 字数={}", draftId, format, string(payload.get("scope"), "all"), content.length());
        return Map.of("draftId", draftId, "format", format, "filename", storyExportService.filename(draft.get("title"), format), "content", content);
    }

    @Transactional(readOnly = true)
    public StoryExportFile exportDraftFile(Long draftId, Map<String, Object> payload) throws IOException {
        Map<String, Object> draft = getDraft(draftId);
        String format = storyExportService.normalizeFormat(string(payload.get("format"), "md"));
        String markdown = storyExportService.buildDraftMarkdown(draft, payload);
        StoryExportFile file = storyExportService.buildFile(draft.get("title"), markdown, format);
        log.info("下载导出短剧 draftId={} format={} filename={} bytes={}", draftId, format, file.filename(), file.bytes().length);
        return file;
    }

    private Map<String, Object> projectMap(StoryProject project) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", project.getId());
        map.put("tenantId", project.getTenantId());
        map.put("title", project.getTitle());
        map.put("type", project.getType());
        map.put("typeLabel", typeLabel(project.getType()));
        map.put("status", project.getStatus());
        map.put("description", project.getDescription());
        map.put("linkedKbId", project.getLinkedKbId());
        Map<String, Object> metadata = fromJsonObject(project.getMetadata());
        map.put("metadata", metadata);
        map.put("assets", objectMap(metadata.get("assets")));
        map.put("promptConfig", objectMap(metadata.get("promptConfig")));
        map.put("createdAt", instant(project.getCreatedAt()));
        map.put("updatedAt", instant(project.getUpdatedAt()));
        map.put("chapterCount", project.getChapterCount() == null ? 0 : project.getChapterCount());
        map.put("scriptDraftCount", project.getScriptDraftCount() == null ? 0 : project.getScriptDraftCount());
        List<ScriptDraft> drafts = draftMapper.findByProjectId(project.getId());
        if (!drafts.isEmpty()) {
            ScriptDraft latest = drafts.get(0);
            map.put("latestScriptDraftId", latest.getId());
            map.put("latestScriptDraftTitle", latest.getTitle());
            map.put("latestScriptUpdatedAt", instant(latest.getUpdatedAt()));
        }
        return map;
    }

    private Map<String, Object> chapterMap(StoryChapter chapter) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", chapter.getId());
        map.put("projectId", chapter.getProjectId());
        map.put("title", chapter.getTitle());
        map.put("chapterNo", chapter.getChapterNo());
        map.put("content", chapter.getContent());
        map.put("wordCount", chapter.getWordCount());
        map.put("versionNo", chapter.getVersionNo());
        map.put("status", chapter.getStatus());
        map.put("createdAt", instant(chapter.getCreatedAt()));
        map.put("updatedAt", instant(chapter.getUpdatedAt()));
        return map;
    }

    private Map<String, Object> chapterVersionMap(StoryChapterVersion version) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", version.getId());
        map.put("chapterId", version.getChapterId());
        map.put("projectId", version.getProjectId());
        map.put("title", version.getTitle());
        map.put("content", version.getContent());
        map.put("wordCount", version.getWordCount());
        map.put("versionNo", version.getVersionNo());
        map.put("source", version.getSource());
        map.put("note", version.getNote());
        map.put("createdAt", instant(version.getCreatedAt()));
        return map;
    }

    private Map<String, Object> rewriteTaskMap(RewriteTask task) {
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Object> diff = fromJsonObject(task.getDiffPayload());
        map.put("id", task.getId());
        map.put("projectId", task.getProjectId());
        map.put("chapterId", task.getChapterId());
        map.put("sourceType", task.getSourceType());
        map.put("sourceText", task.getSourceText());
        map.put("rewriteMode", task.getRewriteMode());
        map.put("instruction", task.getInstruction());
        map.put("status", task.getStatus());
        map.put("segments", segmentsFrom(task.getSegmentsJson()));
        map.put("resultText", task.getResultText());
        map.put("progress", intValue(diff.get("progress"), rewriteProgressFallback(task)));
        map.put("currentStep", string(diff.get("currentStep"), rewriteStepFallback(task)));
        map.put("errorMessage", string(diff.get("errorMessage"), ""));
        map.put("summaryNote", string(diff.get("summaryNote"), rewriteSummaryNote(task.getRewriteMode())));
        map.put("diffPayload", diff);
        map.put("createdAt", instant(task.getCreatedAt()));
        map.put("completedAt", instant(task.getCompletedAt()));
        return map;
    }

    private Map<String, Object> rewriteSegmentSkeleton(String source) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("source", source);
        segment.put("rewritten", "");
        segment.put("note", "");
        segment.put("status", "accepted");
        return segment;
    }

    private String rewriteResultText(List<Map<String, Object>> segments) {
        return segments.stream()
                .map(segment -> "rejected".equals(segment.get("status"))
                        ? string(segment.get("source"), "")
                        : string(segment.get("rewritten"), string(segment.get("source"), "")))
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
    }

    private Map<String, Object> rewriteMeta(int progress, String currentStep, String errorMessage, String summaryNote) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("progress", Math.max(0, Math.min(100, progress)));
        meta.put("currentStep", string(currentStep, ""));
        meta.put("errorMessage", string(errorMessage, ""));
        meta.put("summaryNote", string(summaryNote, rewriteSummaryNote("deslop")));
        return meta;
    }

    private int progressFromMeta(String diffPayload) {
        return intValue(fromJsonObject(diffPayload).get("progress"), 0);
    }

    private int rewriteProgressFallback(RewriteTask task) {
        return switch (string(task.getStatus(), "")) {
            case "completed", "accepted" -> 100;
            case "failed" -> 0;
            case "canceled" -> progressFromMeta(task.getDiffPayload());
            case "running" -> 50;
            default -> 0;
        };
    }

    private String rewriteStepFallback(RewriteTask task) {
        return switch (string(task.getStatus(), "")) {
            case "completed" -> "改写完成，可进入三栏对照审核";
            case "accepted" -> "已保存为新版本";
            case "failed" -> "改写失败";
            case "canceled" -> "用户已终止改写";
            case "running" -> "正在改写";
            default -> "排队中";
        };
    }

    private String rewriteSummaryNote(String rewriteMode) {
        return switch (string(rewriteMode, "deslop")) {
            case "dialogue" -> "本次重点优化对白口语感和冲突感，逐段确认后保存为新版本。";
            case "polish" -> "本次重点提升文字流畅度和可读性，逐段确认后保存为新版本。";
            case "conflict" -> "本次重点强化压迫、误会和反转密度，逐段确认后保存为新版本。";
            default -> "本次重点降低书面腔，保留剧情功能，增加动作和口语感。逐段确认后保存为新版本。";
        };
    }

    private Map<String, Object> draftSummaryMap(ScriptDraft draft) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", draft.getId());
        map.put("projectId", draft.getProjectId());
        map.put("title", draft.getTitle());
        map.put("episodeCount", draft.getEpisodeCount());
        map.put("status", draft.getStatus());
        map.put("qualityScore", draft.getQualityScore());
        map.put("updatedAt", instant(draft.getUpdatedAt()));
        return map;
    }

    private Map<String, Object> draftMap(ScriptDraft draft) {
        Map<String, Object> map = draftSummaryMap(draft);
        map.put("sourceRef", draft.getSourceRef());
        map.put("adaptationPlan", fromJsonObject(draft.getAdaptationPlan()));
        map.put("qualityReport", fromJsonObject(draft.getQualityReport()));
        map.put("sourceChapters", sourceChapterSummaries(draft.getSourceRef()));
        map.put("createdAt", instant(draft.getCreatedAt()));
        List<Map<String, Object>> episodes = episodeMapper.findByDraftId(draft.getId()).stream()
                .map(this::episodeMap)
                .toList();
        map.put("episodes", episodes);
        return map;
    }

    private List<Map<String, Object>> sourceChapterSummaries(String sourceRef) {
        if (sourceRef == null || !sourceRef.startsWith("project:")) return List.of();
        Long projectId = nullableLong(sourceRef.substring("project:".length()));
        if (projectId == null) return List.of();
        return chapterMapper.findByProjectId(projectId).stream()
                .map(chapter -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", chapter.getId());
                    map.put("title", chapter.getTitle());
                    map.put("chapterNo", chapter.getChapterNo());
                    map.put("wordCount", chapter.getWordCount());
                    String content = string(chapter.getContent(), "");
                    map.put("preview", content.length() <= 160 ? content : content.substring(0, 160));
                    return map;
                })
                .toList();
    }
    private Map<String, Object> episodeMap(ScriptEpisode episode) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", episode.getId());
        map.put("draftId", episode.getDraftId());
        map.put("episodeNo", episode.getEpisodeNo());
        map.put("title", episode.getTitle());
        map.put("estimatedDuration", episode.getEstimatedDuration());
        map.put("coreHook", episode.getCoreHook());
        map.put("mainConflict", episode.getMainConflict());
        map.put("endingHook", episode.getEndingHook());
        map.put("summary", episode.getSummary());
        map.put("scenes", sceneMapper.findByEpisodeId(episode.getId()).stream().map(this::sceneMap).toList());
        return map;
    }

    private Map<String, Object> sceneMap(ScriptScene scene) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", scene.getId());
        map.put("episodeId", scene.getEpisodeId());
        map.put("sceneNo", scene.getSceneNo());
        map.put("sceneTitle", scene.getSceneTitle());
        map.put("location", scene.getLocation());
        map.put("timeOfDay", scene.getTimeOfDay());
        map.put("characters", scene.getCharacters());
        map.put("sceneFunction", scene.getSceneFunction());
        map.put("estimatedDuration", scene.getEstimatedDuration());
        map.put("visualAction", scene.getVisualAction());
        map.put("narration", scene.getNarration());
        map.put("dialogue", scene.getDialogue());
        map.put("performanceCameraNote", scene.getPerformanceCameraNote());
        map.put("hook", scene.getHook());
        map.put("updatedAt", instant(scene.getUpdatedAt()));
        return map;
    }

    private Map<String, Object> generationTaskMap(GenerationTask task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", task.getId());
        map.put("taskType", task.getTaskType());
        map.put("projectId", task.getProjectId());
        if ("script_convert".equals(task.getTaskType()) && task.getProjectId() != null && "completed".equals(task.getStatus())) {
            draftMapper.findByProjectId(task.getProjectId()).stream()
                    .max((a, b) -> Long.compare(a.getId(), b.getId()))
                    .ifPresent(draft -> map.put("draftId", draft.getId()));
        }
        map.put("status", task.getStatus());
        map.put("progress", task.getProgress());
        map.put("currentStep", task.getCurrentStep());
        map.put("errorMessage", task.getErrorMessage());
        map.put("tokenUsage", fromJsonObject(task.getTokenUsage()));
        return map;
    }

    private Map<String, Object> generationTaskHistoryMap(GenerationTask task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", "generation");
        map.put("id", "generation-" + task.getId());
        map.put("taskId", task.getId());
        map.put("taskType", task.getTaskType());
        map.put("title", generationTaskTitle(task));
        map.put("status", task.getStatus());
        map.put("progress", task.getProgress());
        map.put("currentStep", task.getCurrentStep());
        map.put("errorMessage", task.getErrorMessage());
        map.put("projectId", task.getProjectId());
        map.put("projectTitle", projectTitle(task.getProjectId()));
        if ("script_convert".equals(task.getTaskType()) && task.getProjectId() != null && "completed".equals(task.getStatus())) {
            draftMapper.findByProjectId(task.getProjectId()).stream()
                    .max((a, b) -> Long.compare(a.getId(), b.getId()))
                    .ifPresent(draft -> map.put("draftId", draft.getId()));
        }
        map.put("createdAt", instant(task.getCreatedAt()));
        map.put("updatedAt", instant(task.getUpdatedAt()));
        return map;
    }

    private Map<String, Object> rewriteTaskHistoryMap(RewriteTask task) {
        Map<String, Object> diff = fromJsonObject(task.getDiffPayload());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", "rewrite");
        map.put("id", "rewrite-" + task.getId());
        map.put("taskId", task.getId());
        map.put("taskType", "story_rewrite");
        map.put("title", "改小说三栏对照");
        map.put("status", task.getStatus());
        map.put("progress", intValue(diff.get("progress"), rewriteProgressFallback(task)));
        map.put("currentStep", string(diff.get("currentStep"), rewriteStepFallback(task)));
        map.put("errorMessage", string(diff.get("errorMessage"), ""));
        map.put("projectId", task.getProjectId());
        map.put("projectTitle", projectTitle(task.getProjectId()));
        map.put("chapterId", task.getChapterId());
        map.put("rewriteMode", task.getRewriteMode());
        map.put("createdAt", instant(task.getCreatedAt()));
        map.put("updatedAt", instant(task.getCompletedAt() == null ? task.getCreatedAt() : task.getCompletedAt()));
        return map;
    }

    private String generationTaskTitle(GenerationTask task) {
        return switch (string(task.getTaskType(), "")) {
            case "script_convert" -> "短剧分场稿生成";
            default -> "AI 生成任务";
        };
    }

    private String projectTitle(Long projectId) {
        if (projectId == null) return "";
        return projectMapper.findById(projectId).map(StoryProject::getTitle).orElse("");
    }

    private ScriptEpisode buildEpisode(Long draftId, Map<String, Object> payload, int fallbackNo) {
        int episodeNo = intValue(payload.get("episodeNo"), fallbackNo);
        return ScriptEpisode.builder()
                .draftId(draftId)
                .episodeNo(episodeNo)
                .title(string(payload.get("title"), "第" + episodeNo + "集"))
                .estimatedDuration(string(payload.get("estimatedDuration"), "1-3分钟"))
                .coreHook(string(payload.get("coreHook"), episodeNo == 1 ? "主角被当众逼入绝境" : "误会升级，关系反转"))
                .mainConflict(string(payload.get("mainConflict"), "主角目标与外部阻碍正面冲突"))
                .endingHook(string(payload.get("endingHook"), "关键证据出现，但说出真相的人突然沉默"))
                .summary(string(payload.get("summary"), "围绕核心冲突推进一轮短剧节奏。"))
                .build();
    }

    private ScriptEpisode buildEpisode(Long draftId, int episodeNo) {
        return ScriptEpisode.builder()
                .draftId(draftId)
                .episodeNo(episodeNo)
                .title("第" + episodeNo + "集")
                .estimatedDuration("1-3分钟")
                .coreHook(episodeNo == 1 ? "主角被当众逼入绝境" : "误会升级，关系反转")
                .mainConflict("主角目标与外部阻碍正面冲突")
                .endingHook("关键证据出现，但说出真相的人突然沉默")
                .summary("围绕核心冲突推进一轮短剧节奏。")
                .build();
    }

    private ScriptScene buildScene(Long episodeId, Map<String, Object> payload, int fallbackNo) {
        return buildScene(episodeId, payload, fallbackNo, Map.of());
    }

    private ScriptScene buildScene(Long episodeId, Map<String, Object> payload, int fallbackNo, Map<String, Object> episodePayload) {
        int sceneNo = intValue(payload.get("sceneNo"), fallbackNo);
        Map<String, Object> fallback = fallbackScene(
                intValue(episodePayload.get("episodeNo"), 1),
                sceneNo,
                string(episodePayload.get("title"), "第" + intValue(episodePayload.get("episodeNo"), 1) + "集"),
                string(episodePayload.get("mainConflict"), ""),
                string(episodePayload.get("summary"), "")
        );
        return ScriptScene.builder()
                .episodeId(episodeId)
                .sceneNo(sceneNo)
                .sceneTitle(scriptField(payload, "sceneTitle", fallback))
                .location(scriptField(payload, "location", fallback))
                .timeOfDay(scriptField(payload, "timeOfDay", fallback))
                .characters(scriptField(payload, "characters", fallback))
                .sceneFunction(scriptField(payload, "sceneFunction", fallback))
                .estimatedDuration(scriptField(payload, "estimatedDuration", fallback))
                .visualAction(scriptField(payload, "visualAction", fallback))
                .narration(scriptField(payload, "narration", fallback))
                .dialogue(scriptField(payload, "dialogue", fallback))
                .performanceCameraNote(scriptField(payload, "performanceCameraNote", fallback))
                .hook(scriptField(payload, "hook", fallback))
                .build();
    }

    private ScriptScene buildScene(Long episodeId, int sceneNo) {
        return ScriptScene.builder()
                .episodeId(episodeId)
                .sceneNo(sceneNo)
                .sceneTitle(sceneNo == 1 ? "开场压迫" : "反击前夜")
                .location("内景｜议事厅｜夜")
                .timeOfDay("夜")
                .characters("主角、对手、旁观者")
                .sceneFunction(sceneNo == 1 ? "建立冲突和压迫" : "推进反转并留下钩子")
                .estimatedDuration("40秒")
                .visualAction("众人围住主角，对手把证据拍在桌上，主角没有退后。")
                .narration("少量交代背景，避免替代表演。")
                .dialogue("对手：你还有什么话说？\n主角：话当然有，但不是现在说。")
                .performanceCameraNote("镜头从证据推到主角手指，手指收紧。")
                .hook("门外传来一句：证据是假的。")
                .build();
    }

    private void applyEpisodePayload(ScriptEpisode episode, Map<String, Object> payload) {
        if (payload.containsKey("title")) episode.setTitle(string(payload.get("title"), episode.getTitle()));
        if (payload.containsKey("estimatedDuration")) episode.setEstimatedDuration(string(payload.get("estimatedDuration"), episode.getEstimatedDuration()));
        if (payload.containsKey("coreHook")) episode.setCoreHook(string(payload.get("coreHook"), episode.getCoreHook()));
        if (payload.containsKey("mainConflict")) episode.setMainConflict(string(payload.get("mainConflict"), episode.getMainConflict()));
        if (payload.containsKey("endingHook")) episode.setEndingHook(string(payload.get("endingHook"), episode.getEndingHook()));
        if (payload.containsKey("summary")) episode.setSummary(string(payload.get("summary"), episode.getSummary()));
    }
    private void applyScenePayload(ScriptScene scene, Map<String, Object> payload) {
        if (payload.containsKey("sceneTitle")) scene.setSceneTitle(string(payload.get("sceneTitle"), scene.getSceneTitle()));
        if (payload.containsKey("location")) scene.setLocation(string(payload.get("location"), scene.getLocation()));
        if (payload.containsKey("timeOfDay")) scene.setTimeOfDay(string(payload.get("timeOfDay"), scene.getTimeOfDay()));
        if (payload.containsKey("characters")) scene.setCharacters(string(payload.get("characters"), scene.getCharacters()));
        if (payload.containsKey("sceneFunction")) scene.setSceneFunction(string(payload.get("sceneFunction"), scene.getSceneFunction()));
        if (payload.containsKey("estimatedDuration")) scene.setEstimatedDuration(string(payload.get("estimatedDuration"), scene.getEstimatedDuration()));
        if (payload.containsKey("visualAction")) scene.setVisualAction(string(payload.get("visualAction"), scene.getVisualAction()));
        if (payload.containsKey("narration")) scene.setNarration(string(payload.get("narration"), scene.getNarration()));
        if (payload.containsKey("dialogue")) scene.setDialogue(string(payload.get("dialogue"), scene.getDialogue()));
        if (payload.containsKey("performanceCameraNote")) scene.setPerformanceCameraNote(string(payload.get("performanceCameraNote"), scene.getPerformanceCameraNote()));
        if (payload.containsKey("hook")) scene.setHook(string(payload.get("hook"), scene.getHook()));
    }

    private void snapshotChapter(StoryChapter chapter, String source, String note) {
        StoryChapterVersion version = StoryChapterVersion.builder()
                .chapterId(chapter.getId())
                .projectId(chapter.getProjectId())
                .title(chapter.getTitle())
                .content(chapter.getContent())
                .wordCount(chapter.getWordCount())
                .versionNo(chapter.getVersionNo())
                .source(source)
                .note(note)
                .build();
        chapterVersionMapper.insert(version);
    }

    private String estimateTokenUsage(String sourceText, List<Map<String, Object>> episodes) {
        int inputChars = sourceText == null ? 0 : sourceText.length();
        int outputChars = 0;
        for (Map<String, Object> episode : episodes == null ? List.<Map<String, Object>>of() : episodes) {
            outputChars += episode.values().stream().map(value -> string(value, "")).mapToInt(String::length).sum();
            for (Map<String, Object> scene : mapList(episode.get("scenes"))) {
                outputChars += scene.values().stream().map(value -> string(value, "")).mapToInt(String::length).sum();
            }
        }
        int inputTokens = Math.max(1, inputChars / 2);
        int outputTokens = Math.max(1, outputChars / 2);
        return toJson(Map.of(
                "estimated", true,
                "inputTokens", inputTokens,
                "outputTokens", outputTokens,
                "totalTokens", inputTokens + outputTokens,
                "basis", "Chinese chars / 2 rough estimate"
        ));
    }

    private boolean useFallback(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get("useFallback");
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private StoryChapterVersion resolveVersion(Long chapterId, Map<String, Object> payload) {
        Long versionId = nullableLong(payload.get("versionId"));
        if (versionId != null) {
            StoryChapterVersion version = chapterVersionMapper.findById(versionId)
                    .orElseThrow(() -> new IllegalArgumentException("章节版本不存在：" + versionId));
            if (!chapterId.equals(version.getChapterId())) {
                throw new IllegalArgumentException("章节版本不属于当前章节");
            }
            return version;
        }
        Integer versionNo = payload.get("versionNo") == null ? null : intValue(payload.get("versionNo"), -1);
        if (versionNo != null && versionNo > 0) {
            return chapterVersionMapper.findByChapterIdAndVersionNo(chapterId, versionNo)
                    .orElseThrow(() -> new IllegalArgumentException("章节版本不存在：" + versionNo));
        }
        return chapterVersionMapper.findByChapterId(chapterId).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("当前章节暂无可恢复版本"));
    }

    private String projectSourceText(Long projectId) {
        return chapterMapper.findByProjectId(projectId).stream()
                .map(chapter -> chapter.getTitle() + "\n" + chapter.getContent())
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
    }

    private Map<String, Object> fallbackScriptDraft(String projectTitle, int targetEpisodes) {
        return fallbackScriptDraft(projectTitle, "", targetEpisodes);
    }

    private Map<String, Object> fallbackScriptDraft(String projectTitle, String sourceText, int targetEpisodes) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("adaptationPlan", fallbackAdaptationPlan());
        List<Map<String, Object>> episodes = new ArrayList<>();
        int count = Math.max(1, Math.min(targetEpisodes, 8));
        List<String> sourceSlices = sourceSlices(sourceText, count);
        for (int i = 1; i <= count; i++) {
            String slice = sourceSlices.size() >= i ? sourceSlices.get(i - 1) : "";
            episodes.add(fallbackEpisode(i, projectTitle, slice));
        }
        draft.put("episodes", episodes);
        return draft;
    }

    private Map<String, Object> fallbackAdaptationPlan() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("storyCore", "保留主角欲望、核心冲突和关键反转，将心理活动外化为画面与对白。");
        plan.put("characterRelations", "主角、对手、关键见证者形成压迫与反击关系。");
        plan.put("plotSelection", "保留强冲突节点，压缩铺垫和解释性旁白。");
        plan.put("strategy", "强开场、强冲突、强结尾钩子。");
        return plan;
    }

    private Map<String, Object> fallbackEpisode(int episodeNo) {
        return fallbackEpisode(episodeNo, "", "");
    }

    private Map<String, Object> fallbackEpisode(int episodeNo, Map<String, Object> base) {
        Map<String, Object> episode = new LinkedHashMap<>(base);
        episode.put("episodeNo", intValue(base.get("episodeNo"), episodeNo));
        episode.put("title", string(base.get("title"), "第" + episodeNo + "集"));
        episode.put("estimatedDuration", string(base.get("estimatedDuration"), "1-3分钟"));
        episode.put("coreHook", string(base.get("coreHook"), episodeNo == 1 ? "主角被迫面对突发压力" : "关系误会升级"));
        episode.put("mainConflict", string(base.get("mainConflict"), string(base.get("summary"), "主角目标与现实阻碍正面冲突")));
        episode.put("endingHook", string(base.get("endingHook"), "新的线索出现，逼出下一场选择"));
        episode.put("summary", string(base.get("summary"), "围绕当前事件推进一次冲突与反转。"));
        episode.put("scenes", List.of(
                fallbackScene(episodeNo, 1, string(episode.get("title"), ""), string(episode.get("mainConflict"), ""), string(episode.get("summary"), "")),
                fallbackScene(episodeNo, 2, string(episode.get("title"), ""), string(episode.get("mainConflict"), ""), string(episode.get("summary"), ""))
        ));
        return episode;
    }

    private Map<String, Object> fallbackEpisode(int episodeNo, String projectTitle, String sourceExcerpt) {
        String seed = trimForFallback(sourceExcerpt, 120);
        String title = episodeNo == 1 ? "第1集：变故开场" : "第" + episodeNo + "集：压力升级";
        String conflict = seed.isBlank()
                ? "主角在关键节点被外部压力逼到必须表态"
                : "围绕“" + seed + "”外化为当场冲突";
        String summary = seed.isBlank()
                ? "从" + string(projectTitle, "原作") + "中提炼当前阶段事件，压缩成一轮可拍的短剧冲突。"
                : "本集截取原文事件：“" + seed + "”，改成动作、对白和场面推进。";
        Map<String, Object> episode = new LinkedHashMap<>();
        episode.put("episodeNo", episodeNo);
        episode.put("title", title);
        episode.put("estimatedDuration", "1-3分钟");
        episode.put("coreHook", episodeNo == 1 ? "开场直接抛出异常事件和主角反应" : "上一集压力继续发酵，人物关系进一步绷紧");
        episode.put("mainConflict", conflict);
        episode.put("endingHook", episodeNo % 2 == 0 ? "关键人物给出反常回应，下一集必须追问原因" : "一个细节证明事情并不简单");
        episode.put("summary", summary);
        episode.put("scenes", List.of(
                fallbackScene(episodeNo, 1, title, conflict, summary),
                fallbackScene(episodeNo, 2, title, conflict, summary)
        ));
        return episode;
    }

    private Map<String, Object> fallbackScene(int sceneNo) {
        return fallbackScene(1, sceneNo, "第1集", "主角目标与外部阻碍正面冲突", "");
    }

    private Map<String, Object> fallbackScene(int episodeNo, int sceneNo, String episodeTitle, String conflict, String summary) {
        boolean opening = sceneNo == 1;
        String conflictText = string(conflict, "当前事件产生新的压力");
        String summaryText = trimForFallback(summary, 100);
        String focus = summaryText.isBlank() ? conflictText : summaryText;
        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("sceneNo", sceneNo);
        scene.put("sceneTitle", opening ? "第" + episodeNo + "集开场钩子" : "第" + episodeNo + "集场内反转");
        scene.put("location", opening ? "内景｜办公区｜夜" : "内景｜走廊/茶水间｜夜");
        scene.put("timeOfDay", "夜");
        scene.put("characters", opening ? "主角、关键对手、同事/旁观者" : "主角、关键知情人");
        scene.put("sceneFunction", opening ? "把“" + conflictText + "”直接摆到主角面前" : "围绕“" + conflictText + "”给出新线索并留下钩子");
        scene.put("estimatedDuration", opening ? "35秒" : "45秒");
        scene.put("visualAction", opening
                ? "主角停在原地，手机屏幕亮起；周围声音压低，所有目光集中到他/她身上。"
                : "主角追到门口拦住知情人，对方避开视线，把一个细节压低声音说出来。");
        scene.put("narration", opening
                ? "只用一句话点出本集处境：" + focus
                : "不解释前因，只保留能推动下一步选择的关键信息。");
        scene.put("dialogue", opening
                ? "对手：这件事，你现在就给个说法。\n主角：我可以说，但你先把话说完整。"
                : "知情人：刚才那句话，不是随口说的。\n主角：你知道什么？\n知情人：现在说出来，我们都脱不了身。");
        scene.put("performanceCameraNote", opening
                ? "近景拍手机屏幕和主角眼神，背景人声逐渐压低。"
                : "跟拍主角追问，切到知情人攥紧的手。");
        scene.put("hook", opening
                ? "对手突然补一句：证据不止这一份。"
                : "知情人转身前留下半句：真正该查的人，不是他/她。");
        return scene;
    }

    private String scriptField(Map<String, Object> payload, String key, Map<String, Object> fallback) {
        String value = string(payload.get(key), "");
        if (!value.isBlank() && !isGenericScriptFallback(value)) return value;
        return string(fallback.get(key), "");
    }

    private boolean isGenericScriptFallback(String value) {
        String text = value == null ? "" : value.trim();
        return text.equals("少量交代背景，避免替代表演。")
                || text.equals("对手：你还有什么话说？\n主角：话当然有，但不是现在说。")
                || text.equals("镜头从证据推到主角手指，手指收紧。")
                || text.equals("众人围住主角，对手把证据拍在桌上，主角没有退后。")
                || text.equals("门外传来一句：证据是假的。");
    }

    private List<String> sourceSlices(String sourceText, int count) {
        String normalized = string(sourceText, "")
                .replaceAll("(?m)^第[一二三四五六七八九十百0-9]+[章节].*$", "\n")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) return List.of();
        int safeCount = Math.max(1, count);
        int sliceLength = Math.max(120, normalized.length() / safeCount);
        List<String> slices = new ArrayList<>();
        for (int i = 0; i < safeCount; i++) {
            int start = Math.min(normalized.length(), i * sliceLength);
            int end = i == safeCount - 1
                    ? normalized.length()
                    : Math.min(normalized.length(), start + sliceLength);
            if (start < end) slices.add(normalized.substring(start, end));
        }
        return slices;
    }

    private String trimForFallback(String value, int maxChars) {
        String text = string(value, "").replaceAll("\\s+", " ").trim();
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars);
    }

    private Map<String, Object> fallbackEpisodeImprovement(Map<String, Object> current, String action) {
        Map<String, Object> improved = new LinkedHashMap<>(current);
        improved.put("title", string(current.get("title"), "本集") + "（强化版）");
        improved.put("estimatedDuration", string(current.get("estimatedDuration"), "1-3分钟"));
        improved.put("coreHook", "开场 3 秒直接抛出身份、背叛或证据反转，让观众立刻站队。");
        improved.put("mainConflict", "主角目标与对手压迫正面相撞，中段至少一次误会升级或当众打脸。");
        improved.put("endingHook", "结尾留下新证据、新身份或关键人物突然出现，推动追看下一集。");
        improved.put("summary", "本集按短剧节奏重排：钩子前置、冲突加密、心理活动外化为动作和对白。");
        return improved;
    }
    private Map<String, Object> fallbackSceneImprovement(Map<String, Object> current, String action) {
        Map<String, Object> improved = new LinkedHashMap<>(current);
        switch (action) {
            case "hook" -> improved.put("hook", "门外突然响起一句：这份证据，是我亲手伪造的。");
            case "dialogue" -> improved.put("dialogue", "对手：你还装？\n主角：我装什么了？证据拿出来。\n对手：你以为我不敢？");
            case "externalize" -> improved.put("visualAction", string(current.get("visualAction"), "") + "\n主角没有解释，只把被攥皱的照片摊在桌上。");
            default -> {
                improved.put("sceneFunction", "用更直接的对抗推进冲突，并在场尾留下追看钩子。");
                improved.put("visualAction", "对手逼近一步，把证据举到众人面前；主角后退半步，又停住。");
            }
        }
        return improved;
    }

    private Map<String, Object> fallbackQualityReport(Long draftId) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("draftId", draftId);
        report.put("totalScore", 86);
        report.put("mainIssues", List.of(
                "第1集开场钩子已具备，但可以更快暴露核心冲突。",
                "部分旁白仍偏小说化，建议改为动作和对白。",
                "每集结尾钩子完整，适合继续拆场。"
        ));
        report.put("episodeIssues", List.of(Map.of("episodeNo", 1, "issues", List.of("开场冲突可以再前置"))));
        report.put("sceneIssues", List.of(Map.of("sceneNo", "1-1", "issues", List.of("旁白可继续压缩"))));
        report.put("autoFixable", List.of("压缩旁白", "补充场次钩子", "对白口语化"));
        report.put("manualReview", List.of("人物动机是否符合原小说", "关键设定是否需要保留"));
        report.put("checks", Map.of(
                "openingHook", "warn",
                "conflictDensity", "pass",
                "endingHook", "pass",
                "dialogueOrality", "warn",
                "novelResidue", "warn",
                "shootability", "pass"
        ));
        return report;
    }

    private List<String> splitParagraphs(String content) {
        if (content == null || content.isBlank()) return List.of("");
        String[] parts = content.split("\\n\\s*\\n|\\r?\\n");
        List<String> result = new ArrayList<>();
        for (String part : parts) if (!part.isBlank()) result.add(part.trim());
        return result.isEmpty() ? List.of(content.trim()) : result;
    }

    private String rewriteText(String text, String mode) {
        String rewritten = text
                .replace("心中不禁", "")
                .replace("深吸一口气", "胸口起伏了一下")
                .replace("眼中闪过一丝", "垂下眼")
                .replace("命运的齿轮开始转动", "事情从这一刻变了");
        if ("dialogue".equals(mode)) rewritten += "\n角色：这句话，我要你当面说清楚。";
        return rewritten;
    }

    private String generateFallback(String action) {
        return switch (action) {
            case "setting" -> """
                    ## 故事设定
                    - 题材定位：强情绪、强冲突、快节奏网文。
                    - 主线矛盾：主角想夺回被剥夺的身份、资源或尊严，对手持续制造误会和压迫。
                    - 世界规则：所有关键设定都要服务冲突推进，避免大段说明。
                    - 爽点机制：受辱、反击、误会揭开、身份反转、当众打脸。
                    - 连续性提醒：每章结尾保留一个未解决问题，下一章开头立即承接。
                    """;
            case "characters" -> """
                    ## 人物小传
                    - 主角：目标明确，外表克制，底层欲望是夺回选择权；弱点是不愿解释，容易被误会利用。
                    - 对手：掌握资源或话语权，擅长当众施压；每次出场都要推动危机升级。
                    - 关键盟友：知道部分真相，但有自己的顾虑；负责制造信息差和反转入口。
                    - 旁观者：用于放大舆论压力，承担短剧里的围观、质疑、反应镜头。
                    """;
            case "outline" -> "## 章节大纲\n- 开场用一个强钩子切入主角困境。\n- 中段制造误会、压迫和选择。\n- 结尾留下反击或身份反转钩子。";
            case "expand" -> "【扩写建议】补一组可拍动作和环境压迫：让对手逼近、旁观者低声议论、主角手里道具出现变化，再用一句短对白把冲突顶上去。";
            case "shorten" -> "【缩写建议】保留目标、阻碍、反转三件事；删除解释性背景，把心理活动改成一个动作或一句对白。";
            case "style" -> "【改风格示例】节奏更短促，句子更利落。少写感慨，多写动作；少写他很愤怒，多写他把证据按在桌上。";
            case "polish" -> "【润色建议】把抽象情绪改成动作，把解释性句子压短，让对白承载冲突。";
            case "deslop" -> "【去 AI 味草稿】他没立刻回答，只把手里的纸攥紧了一点。屋里安静下来，连窗外的雨声都显得刺耳。";
            case "dialogue" -> "角色A：你真以为我什么都不知道？\n角色B：知道又怎样，你有证据吗？";
            case "conflict" -> "就在主角准备离开时，对手当众拿出那份旧证据，逼他必须立刻做选择。";
            case "review" -> """
                    ## 章节审查
                    - 钩子：开头需要在3到5句内出现明确压力或异常信息。
                    - 冲突：中段至少一次升级，不要只停留在解释。
                    - 人设：主角目标要清楚，对手行为要有压迫感。
                    - 爽点：结尾建议留下反击证据、身份反转或新的误会。
                    - 可改项：压缩旁白，把内心活动外化为动作、对白或道具。
                    """;
            default -> "新的场景从一个更具体的动作开始：门被推开，所有人的视线同时落在主角身上。";
        };
    }

    private String defaultOpening(String type) {
        if ("short_story".equals(type)) return "###1.\n我第一次意识到不对，是在那通电话之后。";
        return "第1章\n门外的雨停了，屋里的人却没有一个敢先开口。";
    }

    private int wordCount(String text) {
        return StoryImportClassifier.wordCount(text);
    }

    private String typeLabel(String type) {
        return switch (type) {
            case "short_story" -> "短篇";
            case "adaptation" -> "改编";
            case "short_drama" -> "短剧";
            default -> "长篇";
        };
    }

    private Long nullableLong(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private int intValue(Object value, int fallback) {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private long longValue(Object value) {
        Long parsed = nullableLong(value);
        return parsed == null ? 0L : parsed;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String string(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private String safeLogMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return "{}";
        Map<String, Object> safe = new LinkedHashMap<>();
        value.forEach((key, item) -> {
            if (item instanceof String text) {
                safe.put(key, text.length() > 40 ? text.substring(0, 40) + "..." : text);
            } else {
                safe.put(key, item);
            }
        });
        return safe.toString();
    }

    private String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 序列化失败", e);
        }
    }

    private Map<String, Object> fromJsonObject(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> segmentsFrom(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) return (List<Map<String, Object>>) list;
        try {
            return objectMapper.readValue(String.valueOf(raw), new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) return (List<Map<String, Object>>) list;
        try {
            return objectMapper.readValue(String.valueOf(raw), new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object raw) {
        if (raw == null) return new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) return (Map<String, Object>) map;
        try {
            return objectMapper.readValue(String.valueOf(raw), new TypeReference<>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}


