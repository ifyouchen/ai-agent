package com.example.aiagent.story;

import com.example.aiagent.story.entity.*;
import com.example.aiagent.story.mapper.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
        log.info("story.project.create action=create_project projectId={} type={} title={}", project.getId(), type, title);
        return getProject(project.getId());
    }

    @Transactional
    public Map<String, Object> importText(Map<String, Object> payload) {
        String content = string(payload.get("content"), "");
        String detectedType = detectType(content);
        Map<String, Object> project = createProject(Map.of(
                "title", string(payload.get("title"), "导入作品"),
                "type", detectedType,
                "description", "由导入文本生成"
        ));

        Long projectId = longValue(project.get("id"));
        List<StoryChapter> existing = chapterMapper.findByProjectId(projectId);
        int chapterNo = 1;
        for (StoryChapter chapter : existing) {
            if (chapterNo == 1) {
                List<String> chunks = splitImportUnits(content);
                StoryChapter first = chapter;
                first.setTitle(guessChapterTitle(chunks.get(0), 1));
                first.setContent(chunks.get(0).trim());
                first.setWordCount(wordCount(first.getContent()));
                first.setVersionNo(first.getVersionNo() + 1);
                first.setStatus("draft");
                chapterMapper.update(first);
                snapshotChapter(first, "import", "导入正文");
                for (int i = 1; i < chunks.size(); i++) {
                    createChapter(projectId, Map.of(
                            "title", guessChapterTitle(chunks.get(i), i + 1),
                            "content", chunks.get(i).trim()
                    ));
                }
            }
            chapterNo++;
        }
        log.info("story.import.text action=import_text projectId={} detectedType={} wordCount={} chapterCount={}",
                projectId, detectedType, wordCount(content), splitImportUnits(content).size());
        return getProject(projectId);
    }

    public Map<String, Object> previewImportText(Map<String, Object> payload) {
        Map<String, Object> preview = buildImportPreview(
                string(payload.get("title"), "导入作品"),
                string(payload.get("content"), ""),
                "text"
        );
        log.info("story.import.preview action=preview_text detectedType={} wordCount={} chapterCount={}",
                preview.get("detectedType"), preview.get("wordCount"), preview.get("chapterCount"));
        return preview;
    }

    @Transactional
    public Map<String, Object> importFile(MultipartFile file, String title) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("导入文件不能为空");
        }
        String filename = cleanUploadFilename(file.getOriginalFilename());
        Path tempFile = Files.createTempFile("story-import-", "-" + safeFilename(filename));
        try {
            Files.copy(file.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            String content = parseUploadText(tempFile, filename);
            Map<String, Object> imported = importText(Map.of(
                    "title", string(title, stripExtension(filename)),
                    "content", content
            ));
            log.info("story.import.file action=import_file projectId={} filename={} size={} detectedType={} wordCount={}",
                    imported.get("id"), filename, file.getSize(), detectType(content), wordCount(content));
            return imported;
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public Map<String, Object> previewImportFile(MultipartFile file, String title) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("导入文件不能为空");
        }
        String filename = cleanUploadFilename(file.getOriginalFilename());
        Path tempFile = Files.createTempFile("story-import-preview-", "-" + safeFilename(filename));
        try {
            Files.copy(file.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            String content = parseUploadText(tempFile, filename);
            Map<String, Object> preview = buildImportPreview(string(title, stripExtension(filename)), content, filename);
            log.info("story.import.preview action=preview_file filename={} size={} detectedType={} wordCount={} chapterCount={}",
                    filename, file.getSize(), preview.get("detectedType"), preview.get("wordCount"), preview.get("chapterCount"));
            return preview;
        } finally {
            Files.deleteIfExists(tempFile);
        }
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
        log.info("story.project.update action=update_project projectId={} updateAssets={} updatePromptConfig={}",
                id, payload.containsKey("assets"), payload.containsKey("promptConfig"));
        return getProject(id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportProject(Long projectId, Map<String, Object> payload) {
        Map<String, Object> project = getProject(projectId);
        String format = normalizeExportFormat(string(payload.get("format"), "md"));
        String markdown = buildProjectMarkdown(project, payload);
        String content = previewExportContent(project.get("title"), markdown, format);
        log.info("story.project.export action=export_preview projectId={} format={} contentLength={}",
                projectId, format, content.length());
        return Map.of(
                "projectId", projectId,
                "format", format,
                "filename", exportFilename(project.get("title"), format),
                "content", content
        );
    }

    @Transactional(readOnly = true)
    public ExportFile exportProjectFile(Long projectId, Map<String, Object> payload) throws IOException {
        Map<String, Object> project = getProject(projectId);
        String format = normalizeExportFormat(string(payload.get("format"), "md"));
        String markdown = buildProjectMarkdown(project, payload);
        ExportFile file = buildExportFile(project.get("title"), markdown, format);
        log.info("story.project.export action=export_file projectId={} format={} filename={} bytes={}",
                projectId, format, file.filename(), file.bytes().length);
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
        log.info("story.chapter.create action=create_chapter projectId={} chapterId={} chapterNo={} wordCount={}",
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
        log.info("story.chapter.update action=update_chapter projectId={} chapterId={} versionNo={} wordCount={} source={}",
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
        log.info("story.chapter.delete action=delete_chapter projectId={} chapterId={} remainingCount={}",
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
        log.info("story.chapter.restore action=restore_chapter projectId={} chapterId={} restoredVersion={} newVersion={}",
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
        log.info("story.generate action=generate_writing projectId={} generateAction={} useFallback={} useCustomPrompt={} instructionLength={} params={} sourceLength={} resultLength={}",
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
                .map(text -> {
                    Map<String, Object> segment = new LinkedHashMap<>();
                    String fallback = rewriteText(text, rewriteMode);
                    segment.put("source", text);
                    segment.put("rewritten", useFallback(payload) ? fallback : storyAiService.rewriteSegment(text, rewriteMode, instruction, fallback));
                    segment.put("note", "降低书面腔，保留剧情功能，增加动作和口语感。");
                    segment.put("status", "pending");
                    return segment;
                })
                .toList();

        RewriteTask task = RewriteTask.builder()
                .projectId(projectId)
                .chapterId(nullableLong(payload.get("chapterId")))
                .sourceType("chapter")
                .sourceText(source)
                .rewriteMode(rewriteMode)
                .instruction(instruction)
                .status("completed")
                .segmentsJson(toJson(segments))
                .resultText(segments.stream().map(s -> string(s.get("rewritten"), "")).reduce((a, b) -> a + "\n\n" + b).orElse(""))
                .diffPayload("{}")
                .completedAt(Instant.now())
                .build();
        rewriteTaskMapper.insert(task);
        projectMapper.updateStatus(projectId, "rewriting");
        log.info("story.rewrite.create action=create_rewrite projectId={} chapterId={} taskId={} mode={} segmentCount={} sourceLength={}",
                projectId, task.getChapterId(), task.getId(), rewriteMode, segments.size(), source.length());
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
        List<Map<String, Object>> segments = segmentsFrom(payload.get("segments") == null ? task.getSegmentsJson() : payload.get("segments"));
        if (task.getChapterId() != null) {
            String content = segments.stream()
                    .map(segment -> "rejected".equals(segment.get("status")) ? string(segment.get("source"), "") : string(segment.get("rewritten"), ""))
                    .reduce((a, b) -> a + "\n\n" + b)
                    .orElse("");
            updateChapter(task.getChapterId(), Map.of("content", content, "source", "rewrite_accept", "note", "接受改写任务 " + id));
        }
        task.setStatus("accepted");
        task.setSegmentsJson(toJson(segments));
        task.setResultText(segments.stream().map(s -> string(s.get("rewritten"), "")).reduce((a, b) -> a + "\n\n" + b).orElse(""));
        task.setDiffPayload("{}");
        task.setCompletedAt(Instant.now());
        rewriteTaskMapper.update(task);
        log.info("story.rewrite.accept action=accept_rewrite projectId={} chapterId={} taskId={} segmentCount={}",
                task.getProjectId(), task.getChapterId(), id, segments.size());
        return Map.of("projectId", task.getProjectId(), "taskId", id, "status", "accepted");
    }

    @Transactional
    public Map<String, Object> retryRewrite(Long id, Map<String, Object> payload) {
        RewriteTask old = rewriteTaskMapper.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("改写任务不存在：" + id));
        log.info("story.rewrite.retry action=retry_rewrite oldTaskId={} projectId={} chapterId={} mode={}",
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
    public Map<String, Object> convertToScript(Map<String, Object> payload) {
        Long projectId = longValue(payload.get("projectId"));
        StoryProject project = projectMapper.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("作品不存在：" + projectId));
        int targetEpisodes = intValue(payload.get("targetEpisodes"), 3);
        String sourceText = projectSourceText(projectId);
        Map<String, Object> fallback = fallbackScriptDraft(project.getTitle(), targetEpisodes);
        Map<String, Object> generated = useFallback(payload)
                ? fallback
                : storyAiService.generateScriptDraft(project.getTitle(), sourceText, targetEpisodes, fallback);
        @SuppressWarnings("unchecked")
        Map<String, Object> adaptationPlan = generated.get("adaptationPlan") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : fallbackAdaptationPlan();
        List<Map<String, Object>> generatedEpisodes = mapList(generated.get("episodes"));
        if (generatedEpisodes.isEmpty()) {
            generatedEpisodes = mapList(fallbackScriptDraft(project.getTitle(), targetEpisodes).get("episodes"));
        }

        GenerationTask task = GenerationTask.builder()
                .taskType("script_convert")
                .projectId(projectId)
                .status("completed")
                .progress(100)
                .currentStep("短剧分场稿已生成")
                .tokenUsage(estimateTokenUsage(sourceText, generatedEpisodes))
                .build();
        generationTaskMapper.insert(task);

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

        int episodeIndex = 1;
        for (Map<String, Object> episodePayload : generatedEpisodes) {
            ScriptEpisode episode = buildEpisode(draft.getId(), episodePayload, episodeIndex);
            episodeMapper.insert(episode);
            List<Map<String, Object>> scenes = mapList(episodePayload.get("scenes"));
            if (scenes.isEmpty()) scenes = mapList(fallbackEpisode(episodeIndex).get("scenes"));
            int sceneIndex = 1;
            for (Map<String, Object> scenePayload : scenes) {
                ScriptScene scene = buildScene(episode.getId(), scenePayload, sceneIndex);
                sceneMapper.insert(scene);
                sceneIndex++;
            }
            episodeIndex++;
        }
        projectMapper.updateStatus(projectId, "adapting");
        log.info("story.script.convert action=convert_to_script projectId={} taskId={} draftId={} targetEpisodes={} generatedEpisodes={} useFallback={} sourceLength={}",
                projectId, task.getId(), draft.getId(), targetEpisodes, generatedEpisodes.size(), useFallback(payload), sourceText.length());
        return Map.of("id", task.getId(), "draftId", draft.getId(), "projectId", projectId, "status", "completed", "progress", 100);
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

    @Transactional
    public Map<String, Object> cancelGenerationTask(Long taskId) {
        GenerationTask task = generationTaskMapper.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("生成任务不存在：" + taskId));
        if ("completed".equals(task.getStatus())) {
            return generationTaskMap(task);
        }
        task.setStatus("canceled");
        task.setProgress(task.getProgress() == null ? 0 : task.getProgress());
        task.setCurrentStep("用户已取消任务");
        task.setErrorMessage(null);
        generationTaskMapper.update(task);
        log.info("story.task.cancel action=cancel_task taskId={} projectId={} taskType={} status={}",
                taskId, task.getProjectId(), task.getTaskType(), task.getStatus());
        return generationTaskMap(task);
    }

    @Transactional
    public Map<String, Object> retryGenerationTask(Long taskId) {
        GenerationTask old = generationTaskMapper.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("生成任务不存在：" + taskId));
        if ("script_convert".equals(old.getTaskType())) {
            log.info("story.task.retry action=retry_task taskId={} projectId={} taskType={}",
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
        log.info("story.task.retry action=retry_task_unsupported oldTaskId={} retryTaskId={} taskType={}",
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
        log.info("story.script.episode.ai action=improve_episode episodeId={} draftId={} aiAction={}",
                episodeId, episode.getDraftId(), string(payload.get("action"), "rewrite"));
        return episodeMap(episode);
    }
    @Transactional
    public Map<String, Object> createScene(Long episodeId, Map<String, Object> payload) {
        Integer nextNo = sceneMapper.nextSceneNo(episodeId);
        ScriptScene scene = buildScene(episodeId, payload == null ? Map.of() : payload, nextNo);
        scene.setSceneNo(nextNo);
        sceneMapper.insert(scene);
        log.info("story.script.scene.create action=create_scene episodeId={} sceneId={} sceneNo={}",
                episodeId, scene.getId(), scene.getSceneNo());
        return sceneMap(scene);
    }

    @Transactional
    public Map<String, Object> updateScene(Long sceneId, Map<String, Object> payload) {
        ScriptScene scene = sceneMapper.findById(sceneId)
                .orElseThrow(() -> new IllegalArgumentException("场次不存在：" + sceneId));
        applyScenePayload(scene, payload);
        sceneMapper.update(scene);
        log.info("story.script.scene.update action=update_scene episodeId={} sceneId={} sceneNo={}",
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
        log.info("story.script.scene.delete action=delete_scene episodeId={} sceneId={}", episodeId, sceneId);
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
        log.info("story.script.scene.move action=move_scene episodeId={} sceneId={} direction={} fromIndex={} toIndex={}",
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
        log.info("story.script.scene.ai action=improve_scene episodeId={} sceneId={} aiAction={}",
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
                : storyAiService.qualityCheck(buildExportMarkdown(draftMap, Map.of("includeQualityReport", false)), fallback);
        report.put("draftId", draftId);
        int score = intValue(report.get("totalScore"), 86);
        draft.setQualityScore(score);
        draft.setQualityReport(toJson(report));
        draftMapper.update(draft);
        log.info("story.script.quality action=quality_check draftId={} projectId={} score={} useFallback={}",
                draftId, draft.getProjectId(), score, useFallback(payload));
        return report;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportDraft(Long draftId, Map<String, Object> payload) {
        Map<String, Object> draft = getDraft(draftId);
        String format = normalizeExportFormat(string(payload.get("format"), "md"));
        String markdown = buildExportMarkdown(draft, payload);
        String content = previewExportContent(draft.get("title"), markdown, format);
        log.info("story.script.export action=export_preview draftId={} format={} scope={} contentLength={}",
                draftId, format, string(payload.get("scope"), "all"), content.length());
        return Map.of("draftId", draftId, "format", format, "filename", exportFilename(draft.get("title"), format), "content", content);
    }

    @Transactional(readOnly = true)
    public ExportFile exportDraftFile(Long draftId, Map<String, Object> payload) throws IOException {
        Map<String, Object> draft = getDraft(draftId);
        String format = normalizeExportFormat(string(payload.get("format"), "md"));
        String markdown = buildExportMarkdown(draft, payload);
        ExportFile file = buildExportFile(draft.get("title"), markdown, format);
        log.info("story.script.export action=export_file draftId={} format={} filename={} bytes={}",
                draftId, format, file.filename(), file.bytes().length);
        return file;
    }

    private String buildProjectMarkdown(Map<String, Object> project, Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(project.get("title")).append("\n\n");
        String description = string(project.get("description"), "");
        if (!description.isBlank()) {
            sb.append("> ").append(description).append("\n\n");
        }
        if (Boolean.TRUE.equals(payload.get("includeAssets"))) {
            appendProjectAssets(sb, objectMap(project.get("assets")));
        }
        for (Map<String, Object> chapter : mapList(project.get("chapters"))) {
            String title = string(chapter.get("title"), "第" + chapter.get("chapterNo") + "章");
            sb.append("## ").append(title).append("\n\n");
            sb.append(contentWithoutDuplicateHeading(title, string(chapter.get("content"), ""))).append("\n\n");
        }
        return sb.toString();
    }

    private String contentWithoutDuplicateHeading(String title, String content) {
        if (title == null || title.isBlank() || content == null || content.isBlank()) return content;
        String stripped = content.stripLeading();
        int lineEnd = stripped.indexOf('\n');
        String firstLine = lineEnd >= 0 ? stripped.substring(0, lineEnd) : stripped;
        if (!firstLine.trim().equals(title.trim())) return content;
        return lineEnd >= 0 ? stripped.substring(lineEnd + 1).stripLeading() : "";
    }

    private String buildExportMarkdown(Map<String, Object> draft, Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(draft.get("title")).append("\n\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> episodes = filterExportEpisodes((List<Map<String, Object>>) draft.get("episodes"), payload);
        if (Boolean.TRUE.equals(payload.get("includeAdaptationPlan"))) {
            appendAdaptationPlan(sb, draft);
        }
        if (Boolean.TRUE.equals(payload.get("includeCharacterTable"))) {
            appendCharacterTable(sb, episodes);
        }
        if (Boolean.TRUE.equals(payload.get("includeSceneDirectory"))) {
            appendSceneDirectory(sb, episodes);
        }
        for (Map<String, Object> episode : episodes) {
            sb.append("## 第").append(episode.get("episodeNo")).append("集\n");
            sb.append("预计时长：").append(episode.get("estimatedDuration")).append("\n");
            sb.append("核心爽点：").append(episode.get("coreHook")).append("\n");
            sb.append("本集冲突：").append(episode.get("mainConflict")).append("\n");
            sb.append("结尾钩子：").append(episode.get("endingHook")).append("\n\n");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> scenes = (List<Map<String, Object>>) episode.get("scenes");
            for (Map<String, Object> scene : scenes) {
                sb.append("### 【第").append(scene.get("sceneNo")).append("场】").append(scene.get("sceneTitle")).append("\n");
                sb.append("场景：").append(scene.get("location")).append("\n");
                sb.append("人物：").append(scene.get("characters")).append("\n");
                sb.append("本场功能：").append(scene.get("sceneFunction")).append("\n");
                sb.append("画面：").append(scene.get("visualAction")).append("\n");
                sb.append("旁白：").append(scene.get("narration")).append("\n");
                sb.append("对白：\n").append(scene.get("dialogue")).append("\n");
                sb.append("表演/镜头：").append(scene.get("performanceCameraNote")).append("\n");
                sb.append("钩子：").append(scene.get("hook")).append("\n\n");
            }
        }
        if (Boolean.TRUE.equals(payload.get("includeQualityReport")) && draft.get("qualityReport") != null) {
            sb.append("## 质量报告\n").append(draft.get("qualityReport")).append("\n");
        }
        return sb.toString();
    }

    private List<Map<String, Object>> filterExportEpisodes(List<Map<String, Object>> episodes, Map<String, Object> payload) {
        if (episodes == null) return List.of();
        String scope = string(payload.get("scope"), "all");
        Integer episodeNo = payload.get("episodeNo") == null ? null : intValue(payload.get("episodeNo"), -1);
        Long sceneId = nullableLong(payload.get("sceneId"));
        if ("episode".equals(scope) && episodeNo != null && episodeNo > 0) {
            return episodes.stream()
                    .filter(ep -> intValue(ep.get("episodeNo"), -1) == episodeNo)
                    .toList();
        }
        if ("scene".equals(scope) && sceneId != null) {
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> episode : episodes) {
                List<Map<String, Object>> scenes = mapList(episode.get("scenes")).stream()
                        .filter(scene -> sceneId.equals(nullableLong(scene.get("id"))))
                        .toList();
                if (!scenes.isEmpty()) {
                    Map<String, Object> copy = new LinkedHashMap<>(episode);
                    copy.put("scenes", scenes);
                    filtered.add(copy);
                }
            }
            return filtered;
        }
        return episodes;
    }

    private void appendAdaptationPlan(StringBuilder sb, Map<String, Object> draft) {
        Map<String, Object> plan = objectMap(draft.get("adaptationPlan"));
        if (plan.isEmpty()) return;
        sb.append("## 改编方案\n");
        appendPlanLine(sb, "故事核", plan.get("storyCore"));
        appendPlanLine(sb, "人物关系", plan.get("characterRelations"));
        appendPlanLine(sb, "情节取舍", plan.get("plotSelection"));
        appendPlanLine(sb, "改编策略", plan.get("strategy"));
        sb.append("\n");
    }

    private void appendPlanLine(StringBuilder sb, String label, Object value) {
        String text = string(value, "");
        if (!text.isBlank()) sb.append(label).append("：").append(text).append("\n");
    }

    private void appendCharacterTable(StringBuilder sb, List<Map<String, Object>> episodes) {
        Map<String, Integer> characters = new LinkedHashMap<>();
        for (Map<String, Object> episode : episodes) {
            for (Map<String, Object> scene : mapList(episode.get("scenes"))) {
                for (String name : splitCharacters(string(scene.get("characters"), ""))) {
                    characters.merge(name, 1, Integer::sum);
                }
            }
        }
        if (characters.isEmpty()) return;
        sb.append("## 人物表\n");
        sb.append("| 人物 | 出现场次 | 备注 |\n|---|---:|---|\n");
        characters.forEach((name, count) -> sb.append("| ").append(name).append(" | ").append(count).append(" | 由分场人物字段自动汇总 |\n"));
        sb.append("\n");
    }

    private void appendSceneDirectory(StringBuilder sb, List<Map<String, Object>> episodes) {
        sb.append("## 场次目录\n");
        for (Map<String, Object> episode : episodes) {
            sb.append("- 第").append(episode.get("episodeNo")).append("集：").append(episode.get("title")).append("\n");
            for (Map<String, Object> scene : mapList(episode.get("scenes"))) {
                sb.append("  - 第").append(scene.get("sceneNo")).append("场：")
                        .append(scene.get("sceneTitle")).append("｜")
                        .append(scene.get("location")).append("｜")
                        .append(scene.get("sceneFunction")).append("\n");
            }
        }
        sb.append("\n");
    }

    private List<String> splitCharacters(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String[] parts = raw.split("[、,，/／\\s]+");
        List<String> names = new ArrayList<>();
        for (String part : parts) {
            String name = part.trim();
            if (!name.isBlank() && !names.contains(name)) names.add(name);
        }
        return names;
    }

    private void appendProjectAssets(StringBuilder sb, Map<String, Object> assets) {
        if (assets == null || assets.isEmpty()) return;
        appendProjectAsset(sb, "设定", assets.get("setting"));
        appendProjectAsset(sb, "人物", assets.get("characters"));
        appendProjectAsset(sb, "大纲", assets.get("outline"));
    }

    private void appendProjectAsset(StringBuilder sb, String label, Object value) {
        String text = string(value, "");
        if (!text.isBlank()) {
            sb.append("## ").append(label).append("\n\n").append(text).append("\n\n");
        }
    }

    private ExportFile buildExportFile(Object title, String markdown, String format) throws IOException {
        String normalized = normalizeExportFormat(format);
        String filename = exportFilename(title, normalized);
        return switch (normalized) {
            case "docx" -> new ExportFile(
                    filename,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    buildDocx(markdown)
            );
            case "html" -> new ExportFile(
                    filename,
                    "text/html; charset=UTF-8",
                    buildHtmlDocument(string(title, "导出内容"), markdown).getBytes(StandardCharsets.UTF_8)
            );
            case "pdf" -> new ExportFile(
                    filename,
                    "application/pdf",
                    buildPdf(string(title, "导出内容"), markdown)
            );
            case "txt" -> new ExportFile(
                    filename,
                    "text/plain; charset=UTF-8",
                    markdownToPlainText(markdown).getBytes(StandardCharsets.UTF_8)
            );
            default -> new ExportFile(
                    filename,
                    "text/markdown; charset=UTF-8",
                    markdown.getBytes(StandardCharsets.UTF_8)
            );
        };
    }

    private String previewExportContent(Object title, String markdown, String format) {
        String normalized = normalizeExportFormat(format);
        if ("html".equals(normalized)) return buildHtmlDocument(string(title, "导出内容"), markdown);
        if ("txt".equals(normalized)) return markdownToPlainText(markdown);
        return markdown;
    }

    private String normalizeExportFormat(String format) {
        String value = string(format, "md").trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "markdown" -> "md";
            case "htm" -> "html";
            case "text" -> "txt";
            case "word", "doc" -> "docx";
            case "md", "html", "pdf", "txt", "docx" -> value;
            default -> "md";
        };
    }

    private String exportFilename(Object title, String format) {
        return safeFilename(string(title, "导出内容")) + "." + exportExtension(format);
    }

    private String exportExtension(String format) {
        return switch (normalizeExportFormat(format)) {
            case "docx" -> "docx";
            case "html" -> "html";
            case "pdf" -> "pdf";
            case "txt" -> "txt";
            default -> "md";
        };
    }

    private String buildHtmlDocument(String title, String markdown) {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                  <style>
                    body { margin: 0; background: #f5f7fb; color: #172033; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif; line-height: 1.85; }
                    main { max-width: 820px; margin: 48px auto; padding: 44px 52px; background: #fff; border: 1px solid #e8edf6; border-radius: 8px; box-shadow: 0 18px 50px rgba(20,31,56,.08); }
                    h1 { margin: 0 0 28px; font-size: 32px; line-height: 1.25; }
                    h2 { margin: 34px 0 14px; padding-top: 18px; border-top: 1px solid #edf1f7; font-size: 22px; }
                    h3 { margin: 26px 0 10px; font-size: 18px; }
                    p { margin: 0 0 14px; white-space: pre-wrap; }
                    blockquote { margin: 0 0 22px; padding: 12px 16px; color: #5b667a; background: #f7f9ff; border-left: 4px solid #4f67f5; }
                    table { width: 100%%; border-collapse: collapse; margin: 16px 0 24px; }
                    th, td { border: 1px solid #e4e9f2; padding: 8px 10px; text-align: left; }
                  </style>
                </head>
                <body><main>
                %s
                </main></body>
                </html>
                """.formatted(htmlEscape(title), markdownToHtml(markdown));
    }

    private String markdownToHtml(String markdown) {
        StringBuilder html = new StringBuilder();
        boolean inTable = false;
        for (String rawLine : string(markdown, "").split("\\R")) {
            String line = rawLine.stripTrailing();
            if (line.isBlank()) {
                if (inTable) {
                    html.append("</tbody></table>\n");
                    inTable = false;
                }
                continue;
            }
            if (line.startsWith("|") && line.endsWith("|")) {
                if (line.matches("\\|\\s*[-:]+.*")) continue;
                String[] cells = line.substring(1, line.length() - 1).split("\\|");
                if (!inTable) {
                    html.append("<table><tbody>\n");
                    inTable = true;
                }
                html.append("<tr>");
                for (String cell : cells) html.append("<td>").append(htmlEscape(cell.trim())).append("</td>");
                html.append("</tr>\n");
                continue;
            }
            if (inTable) {
                html.append("</tbody></table>\n");
                inTable = false;
            }
            if (line.startsWith("### ")) html.append("<h3>").append(htmlEscape(line.substring(4))).append("</h3>\n");
            else if (line.startsWith("## ")) html.append("<h2>").append(htmlEscape(line.substring(3))).append("</h2>\n");
            else if (line.startsWith("# ")) html.append("<h1>").append(htmlEscape(line.substring(2))).append("</h1>\n");
            else if (line.startsWith("> ")) html.append("<blockquote>").append(htmlEscape(line.substring(2))).append("</blockquote>\n");
            else if (line.startsWith("- ")) html.append("<p>").append(htmlEscape(line)).append("</p>\n");
            else html.append("<p>").append(htmlEscape(line)).append("</p>\n");
        }
        if (inTable) html.append("</tbody></table>\n");
        return html.toString();
    }

    private String markdownToPlainText(String markdown) {
        StringBuilder text = new StringBuilder();
        for (String rawLine : string(markdown, "").split("\\R")) {
            String line = rawLine
                    .replaceFirst("^#{1,6}\\s+", "")
                    .replaceFirst("^>\\s+", "")
                    .replace("**", "")
                    .replace("`", "");
            if (line.matches("\\|\\s*[-:]+.*")) continue;
            if (line.startsWith("|") && line.endsWith("|")) {
                line = line.substring(1, line.length() - 1).replace("|", "\t");
            }
            text.append(line).append("\n");
        }
        return text.toString();
    }

    private String htmlEscape(String value) {
        return string(value, "")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String parseUploadText(Path filePath, String filename) throws IOException {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            try {
                return Files.readString(filePath);
            } catch (MalformedInputException e) {
                return Files.readString(filePath, Charset.forName("GBK"));
            }
        }
        dev.langchain4j.data.document.Document document = FileSystemDocumentLoader.loadDocument(
                filePath,
                new ApacheTikaDocumentParser()
        );
        return string(document.text(), "");
    }

    private byte[] buildDocx(String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            putZipEntry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """.stripLeading());
            putZipEntry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>
                    """.stripLeading());
            putZipEntry(zip, "word/document.xml", wordDocumentXml(content));
        }
        return out.toByteArray();
    }

    private byte[] buildPdf(String title, String markdown) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFont font = loadPdfFont(document);
            float fontSize = 11.5f;
            float titleSize = 18f;
            float leading = 18f;
            float margin = 54f;
            PDRectangle pageSize = PDRectangle.A4;
            float width = pageSize.getWidth() - margin * 2;
            PDPage page = new PDPage(pageSize);
            document.addPage(page);
            PDPageContentStream stream = new PDPageContentStream(document, page);
            float y = pageSize.getHeight() - margin;

            stream.beginText();
            stream.setFont(font, titleSize);
            stream.setLeading(leading + 4);
            stream.newLineAtOffset(margin, y);
            stream.showText(pdfText(font, title));
            stream.newLine();
            y -= leading + 10;
            stream.setFont(font, fontSize);
            stream.setLeading(leading);

            for (String rawLine : markdownToPlainText(markdown).split("\\R")) {
                List<String> lines = wrapPdfLine(font, pdfText(font, rawLine), fontSize, width);
                if (lines.isEmpty()) lines = List.of("");
                for (String line : lines) {
                    if (y < margin + leading) {
                        stream.endText();
                        stream.close();
                        page = new PDPage(pageSize);
                        document.addPage(page);
                        stream = new PDPageContentStream(document, page);
                        y = pageSize.getHeight() - margin;
                        stream.beginText();
                        stream.setFont(font, fontSize);
                        stream.setLeading(leading);
                        stream.newLineAtOffset(margin, y);
                    }
                    stream.showText(line);
                    stream.newLine();
                    y -= leading;
                }
            }
            stream.endText();
            stream.close();
            document.save(out);
            return out.toByteArray();
        }
    }

    private PDFont loadPdfFont(PDDocument document) {
        String[] candidates = {
                "C:/Windows/Fonts/msyh.ttc",
                "C:/Windows/Fonts/msyh.ttf",
                "C:/Windows/Fonts/simhei.ttf",
                "C:/Windows/Fonts/simsun.ttc",
                "C:/Windows/Fonts/simsun.ttf",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.otf",
                "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
                "/System/Library/Fonts/PingFang.ttc"
        };
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (!file.isFile()) continue;
            try {
                return PDType0Font.load(document, file);
            } catch (Exception e) {
                log.debug("story.export.pdf action=load_font_failed path={} message={}", candidate, e.getMessage());
            }
        }
        log.warn("story.export.pdf action=load_font fallback=Helvetica message=Chinese glyphs may not render");
        return PDType1Font.HELVETICA;
    }

    private List<String> wrapPdfLine(PDFont font, String line, float fontSize, float maxWidth) {
        if (line == null || line.isBlank()) return List.of("");
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int offset = 0; offset < line.length(); ) {
            int codePoint = line.codePointAt(offset);
            String ch = new String(Character.toChars(codePoint));
            String candidate = current + ch;
            if (current.length() > 0 && pdfTextWidth(font, candidate, fontSize) > maxWidth) {
                lines.add(current.toString());
                current = new StringBuilder(ch);
            } else {
                current.append(ch);
            }
            offset += Character.charCount(codePoint);
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    private float pdfTextWidth(PDFont font, String text, float fontSize) {
        try {
            return font.getStringWidth(text) / 1000f * fontSize;
        } catch (IOException e) {
            return text.length() * fontSize;
        }
    }

    private String pdfText(PDFont font, String text) {
        String value = string(text, "");
        if (!(font instanceof PDType1Font)) return value;
        return value.replaceAll("[^\\x20-\\x7E]", "?");
    }

    private void putZipEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String wordDocumentXml(String content) {
        StringBuilder body = new StringBuilder();
        for (String line : content.split("\\R", -1)) {
            appendWordParagraph(body, line);
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                """.stripLeading() + body + """
                    <w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>
                  </w:body>
                </w:document>
                """;
    }

    private void appendWordParagraph(StringBuilder body, String rawLine) {
        String line = rawLine == null ? "" : rawLine.stripTrailing();
        if (line.isBlank()) {
            body.append("<w:p/>");
            return;
        }

        int headingLevel = markdownHeadingLevel(line);
        if (headingLevel > 0) {
            String text = line.substring(headingLevel).stripLeading();
            int size = switch (headingLevel) {
                case 1 -> 34;
                case 2 -> 28;
                case 3 -> 24;
                default -> 22;
            };
            appendWordTextParagraph(body, text, true, false, size, 180, 80, 0);
            return;
        }

        boolean quote = line.startsWith("> ");
        if (quote) {
            appendWordTextParagraph(body, line.substring(2).stripLeading(), false, true, 22, 80, 80, 360);
            return;
        }

        if (line.matches("^[-*+]\\s+.*")) {
            appendWordTextParagraph(body, "• " + line.substring(2).stripLeading(), false, false, 22, 40, 40, 240);
            return;
        }

        if (line.matches("^\\d+[.)]\\s+.*")) {
            appendWordTextParagraph(body, line, false, false, 22, 40, 40, 240);
            return;
        }

        appendWordTextParagraph(body, line, false, false, 22, 40, 40, 0);
    }

    private int markdownHeadingLevel(String line) {
        int count = 0;
        while (count < line.length() && count < 6 && line.charAt(count) == '#') count++;
        return count > 0 && count < line.length() && Character.isWhitespace(line.charAt(count)) ? count : 0;
    }

    private void appendWordTextParagraph(StringBuilder body,
                                         String text,
                                         boolean bold,
                                         boolean italic,
                                         int size,
                                         int before,
                                         int after,
                                         int leftIndent) {
        body.append("<w:p><w:pPr><w:spacing w:before=\"")
                .append(before)
                .append("\" w:after=\"")
                .append(after)
                .append("\"/>");
        if (leftIndent > 0) {
            body.append("<w:ind w:left=\"").append(leftIndent).append("\"/>");
        }
        body.append("</w:pPr>");
        appendWordRuns(body, text, bold, italic, size);
        body.append("</w:p>");
    }

    private void appendWordRuns(StringBuilder body, String text, boolean baseBold, boolean baseItalic, int size) {
        String value = string(text, "");
        int index = 0;
        boolean inlineBold = false;
        while (index < value.length()) {
            int marker = value.indexOf("**", index);
            if (marker < 0) {
                appendWordRun(body, value.substring(index), baseBold || inlineBold, baseItalic, size);
                break;
            }
            if (marker > index) {
                appendWordRun(body, value.substring(index, marker), baseBold || inlineBold, baseItalic, size);
            }
            inlineBold = !inlineBold;
            index = marker + 2;
        }
    }

    private void appendWordRun(StringBuilder body, String text, boolean bold, boolean italic, int size) {
        if (text == null || text.isEmpty()) return;
        body.append("<w:r><w:rPr><w:rFonts w:ascii=\"Calibri\" w:eastAsia=\"Microsoft YaHei\" w:hAnsi=\"Calibri\"/>")
                .append("<w:sz w:val=\"").append(size).append("\"/>")
                .append("<w:szCs w:val=\"").append(size).append("\"/>");
        if (bold) body.append("<w:b/>");
        if (italic) body.append("<w:i/>");
        body.append("</w:rPr><w:t xml:space=\"preserve\">")
                .append(xmlEscape(text))
                .append("</w:t></w:r>");
    }

    private String xmlEscape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String cleanUploadFilename(String filename) {
        String value = string(filename, "导入作品").replace('\\', '/');
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private String safeFilename(String filename) {
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    public record ExportFile(String filename, String contentType, byte[] bytes) {}

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
        map.put("diffPayload", fromJsonObject(task.getDiffPayload()));
        map.put("createdAt", instant(task.getCreatedAt()));
        map.put("completedAt", instant(task.getCompletedAt()));
        return map;
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
        map.put("status", task.getStatus());
        map.put("progress", task.getProgress());
        map.put("currentStep", task.getCurrentStep());
        map.put("errorMessage", task.getErrorMessage());
        map.put("tokenUsage", fromJsonObject(task.getTokenUsage()));
        return map;
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
        int sceneNo = intValue(payload.get("sceneNo"), fallbackNo);
        return ScriptScene.builder()
                .episodeId(episodeId)
                .sceneNo(sceneNo)
                .sceneTitle(string(payload.get("sceneTitle"), sceneNo == 1 ? "开场压迫" : "反击前夜"))
                .location(string(payload.get("location"), "内景｜议事厅｜夜"))
                .timeOfDay(string(payload.get("timeOfDay"), "夜"))
                .characters(string(payload.get("characters"), "主角、对手、旁观者"))
                .sceneFunction(string(payload.get("sceneFunction"), sceneNo == 1 ? "建立冲突和压迫" : "推进反转并留下钩子"))
                .estimatedDuration(string(payload.get("estimatedDuration"), "40秒"))
                .visualAction(string(payload.get("visualAction"), "众人围住主角，对手把证据拍在桌上，主角没有退后。"))
                .narration(string(payload.get("narration"), "少量交代背景，避免替代表演。"))
                .dialogue(string(payload.get("dialogue"), "对手：你还有什么话说？\n主角：话当然有，但不是现在说。"))
                .performanceCameraNote(string(payload.get("performanceCameraNote"), "镜头从证据推到主角手指，手指收紧。"))
                .hook(string(payload.get("hook"), "门外传来一句：证据是假的。"))
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

    private Map<String, Object> buildImportPreview(String title, String content, String sourceName) {
        List<String> chunks = splitImportUnits(content);
        List<Map<String, Object>> chapterPreview = new ArrayList<>();
        for (int i = 0; i < Math.min(chunks.size(), 20); i++) {
            String chunk = chunks.get(i).trim();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chapterNo", i + 1);
            item.put("title", guessChapterTitle(chunk, i + 1));
            item.put("wordCount", wordCount(chunk));
            item.put("preview", chunk.length() <= 120 ? chunk : chunk.substring(0, 120));
            chapterPreview.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", title);
        result.put("sourceName", sourceName);
        result.put("content", content);
        String detectedType = detectType(content);
        result.put("detectedType", detectedType);
        result.put("detectedTypeLabel", typeLabel(detectedType));
        result.put("chapterCount", chunks.size());
        result.put("wordCount", wordCount(content));
        result.put("chapters", chapterPreview);
        result.put("truncated", chunks.size() > chapterPreview.size());
        return result;
    }

    private Map<String, Object> fallbackScriptDraft(String projectTitle, int targetEpisodes) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("adaptationPlan", fallbackAdaptationPlan());
        List<Map<String, Object>> episodes = new ArrayList<>();
        int count = Math.max(1, Math.min(targetEpisodes, 8));
        for (int i = 1; i <= count; i++) episodes.add(fallbackEpisode(i));
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
        Map<String, Object> episode = new LinkedHashMap<>();
        episode.put("episodeNo", episodeNo);
        episode.put("title", "第" + episodeNo + "集");
        episode.put("estimatedDuration", "1-3分钟");
        episode.put("coreHook", episodeNo == 1 ? "主角被当众逼入绝境" : "误会升级，关系反转");
        episode.put("mainConflict", "主角目标与外部阻碍正面冲突");
        episode.put("endingHook", "关键证据出现，但说出真相的人突然沉默");
        episode.put("summary", "围绕核心冲突推进一轮短剧节奏。");
        episode.put("scenes", List.of(fallbackScene(1), fallbackScene(2)));
        return episode;
    }

    private Map<String, Object> fallbackScene(int sceneNo) {
        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("sceneNo", sceneNo);
        scene.put("sceneTitle", sceneNo == 1 ? "开场压迫" : "反击前夜");
        scene.put("location", "内景｜议事厅｜夜");
        scene.put("timeOfDay", "夜");
        scene.put("characters", "主角、对手、旁观者");
        scene.put("sceneFunction", sceneNo == 1 ? "建立冲突和压迫" : "推进反转并留下钩子");
        scene.put("estimatedDuration", "40秒");
        scene.put("visualAction", "众人围住主角，对手把证据拍在桌上，主角没有退后。");
        scene.put("narration", "少量交代背景，避免替代表演。");
        scene.put("dialogue", "对手：你还有什么话说？\n主角：话当然有，但不是现在说。");
        scene.put("performanceCameraNote", "镜头从证据推到主角手指，手指收紧。");
        scene.put("hook", "门外传来一句：证据是假的。");
        return scene;
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

    private List<String> splitChapters(String content) {
        if (content == null || content.isBlank()) return List.of("");
        String[] parts = content.split("(?=第[一二三四五六七八九十百千万0-9]+[章节回])");
        List<String> result = new ArrayList<>();
        for (String part : parts) if (!part.isBlank()) result.add(part);
        return result.isEmpty() ? List.of(content) : result;
    }

    private List<String> splitImportUnits(String content) {
        if ("short_drama".equals(detectType(content))) {
            String[] parts = content.split("(?=第[一二三四五六七八九十百千万0-9]+集)");
            List<String> result = new ArrayList<>();
            for (String part : parts) if (!part.isBlank()) result.add(part);
            return result.isEmpty() ? List.of(content) : result;
        }
        return splitChapters(content);
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

    private String guessChapterTitle(String text, int index) {
        String firstLine = text.strip().split("\\R", 2)[0].trim();
        return firstLine.length() <= 40 ? firstLine : "第" + index + "章";
    }

    private String detectType(String content) {
        return StoryImportClassifier.detectType(content);
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


