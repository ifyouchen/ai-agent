package com.example.aiagent.story;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/story")
public class StoryController {

    private final StoryWorkspaceService service;

    @GetMapping("/projects")
    public List<Map<String, Object>> listProjects(@RequestParam(required = false) String type) {
        return service.listProjects(type);
    }

    @PostMapping("/projects")
    public Map<String, Object> createProject(@RequestBody Map<String, Object> payload) {
        log.info("创建故事项目 title={}", payload.get("title"));
        return service.createProject(payload);
    }

    @GetMapping("/projects/{id}")
    public Map<String, Object> getProject(@PathVariable Long id) {
        return service.getProject(id);
    }

    @PutMapping("/projects/{id}")
    public Map<String, Object> updateProject(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        log.info("更新故事项目 projectId={}", id);
        return service.updateProject(id, payload);
    }

    @DeleteMapping("/projects/{id}")
    public Map<String, Object> deleteProject(@PathVariable Long id) {
        log.info("删除故事项目 projectId={}", id);
        return service.deleteProject(id);
    }

    @PostMapping("/projects/{id}/export")
    public Map<String, Object> exportProject(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> payload) {
        return service.exportProject(id, payload == null ? Map.of() : payload);
    }

    @PostMapping("/projects/{id}/export/file")
    public ResponseEntity<byte[]> exportProjectFile(@PathVariable Long id,
                                                    @RequestBody(required = false) Map<String, Object> payload) throws IOException {
        StoryExportFile file = service.exportProjectFile(id, payload == null ? Map.of() : payload);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(file.filename()))
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.bytes());
    }

    @PostMapping("/projects/{id}/chapters")
    public Map<String, Object> createChapter(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        log.info("创建章节 projectId={}", id);
        return service.createChapter(id, payload);
    }

    @PutMapping("/chapters/{chapterId}")
    public Map<String, Object> updateChapter(@PathVariable Long chapterId, @RequestBody Map<String, Object> payload) {
        return service.updateChapter(chapterId, payload);
    }

    @DeleteMapping("/chapters/{chapterId}")
    public Map<String, Object> deleteChapter(@PathVariable Long chapterId) {
        return service.deleteChapter(chapterId);
    }

    @GetMapping("/chapters/{chapterId}/versions")
    public List<Map<String, Object>> chapterVersions(@PathVariable Long chapterId) {
        return service.chapterVersions(chapterId);
    }

    @PostMapping("/chapters/{chapterId}/restore")
    public Map<String, Object> restoreChapter(@PathVariable Long chapterId,
                                              @RequestBody(required = false) Map<String, Object> payload) {
        return service.restoreChapter(chapterId, payload == null ? Map.of() : payload);
    }

    @PostMapping("/projects/{id}/generate")
    public Map<String, Object> generate(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        return service.generate(id, payload);
    }

    @PostMapping("/import/text")
    public Map<String, Object> importText(@RequestBody Map<String, Object> payload) {
        log.info("文本导入 projectId={}", payload.get("projectId"));
        return service.importText(payload);
    }

    @PostMapping("/import/text/preview")
    public Map<String, Object> previewImportText(@RequestBody Map<String, Object> payload) {
        return service.previewImportText(payload);
    }

    @PostMapping(path = "/import/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importFile(@RequestParam("file") MultipartFile file,
                                          @RequestParam(required = false) String title) throws IOException {
        log.info("文件导入 fileName={} size={} title={}", file.getOriginalFilename(), file.getSize(), title);
        return service.importFile(file, title);
    }

    @PostMapping(path = "/import/file/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> previewImportFile(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(required = false) String title) throws IOException {
        return service.previewImportFile(file, title);
    }

    @PostMapping("/rewrite")
    public Map<String, Object> createRewrite(@RequestBody Map<String, Object> payload) {
        return service.createRewrite(payload);
    }

    @GetMapping("/rewrite/{taskId}")
    public Map<String, Object> getRewrite(@PathVariable Long taskId) {
        return service.getRewrite(taskId);
    }

    @PostMapping("/rewrite/{taskId}/accept")
    public Map<String, Object> acceptRewrite(@PathVariable Long taskId, @RequestBody(required = false) Map<String, Object> payload) {
        return service.acceptRewrite(taskId, payload == null ? Map.of() : payload);
    }

    @PostMapping("/rewrite/{taskId}/retry")
    public Map<String, Object> retryRewrite(@PathVariable Long taskId, @RequestBody(required = false) Map<String, Object> payload) {
        return service.retryRewrite(taskId, payload == null ? Map.of() : payload);
    }

    @PostMapping("/script/convert")
    public Map<String, Object> convertToScript(@RequestBody Map<String, Object> payload) {
        return service.convertToScript(payload);
    }

    @GetMapping("/script/tasks/{taskId}")
    public Map<String, Object> getScriptTask(@PathVariable Long taskId) {
        return service.getScriptTask(taskId);
    }

    @GetMapping("/tasks/{taskId}")
    public Map<String, Object> getTask(@PathVariable Long taskId) {
        return service.getGenerationTask(taskId);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Map<String, Object> cancelTask(@PathVariable Long taskId) {
        return service.cancelGenerationTask(taskId);
    }

    @PostMapping("/tasks/{taskId}/retry")
    public Map<String, Object> retryTask(@PathVariable Long taskId) {
        return service.retryGenerationTask(taskId);
    }

    @GetMapping("/script/drafts/{draftId}")
    public Map<String, Object> getDraft(@PathVariable Long draftId) {
        return service.getDraft(draftId);
    }

    @PostMapping("/script/episodes/{episodeId}/ai")
    public Map<String, Object> improveEpisode(@PathVariable Long episodeId, @RequestBody(required = false) Map<String, Object> payload) {
        return service.improveEpisode(episodeId, payload == null ? Map.of() : payload);
    }
    @PostMapping("/script/episodes/{episodeId}/scenes")
    public Map<String, Object> createScene(@PathVariable Long episodeId, @RequestBody(required = false) Map<String, Object> payload) {
        return service.createScene(episodeId, payload == null ? Map.of() : payload);
    }

    @PutMapping("/script/scenes/{sceneId}")
    public Map<String, Object> updateScene(@PathVariable Long sceneId, @RequestBody Map<String, Object> payload) {
        return service.updateScene(sceneId, payload);
    }

    @DeleteMapping("/script/scenes/{sceneId}")
    public Map<String, Object> deleteScene(@PathVariable Long sceneId) {
        return service.deleteScene(sceneId);
    }

    @PostMapping("/script/scenes/{sceneId}/move")
    public List<Map<String, Object>> moveScene(@PathVariable Long sceneId, @RequestBody(required = false) Map<String, Object> payload) {
        return service.moveScene(sceneId, payload == null ? Map.of() : payload);
    }

    @PostMapping("/script/scenes/{sceneId}/ai")
    public Map<String, Object> improveScene(@PathVariable Long sceneId, @RequestBody(required = false) Map<String, Object> payload) {
        return service.improveScene(sceneId, payload == null ? Map.of() : payload);
    }

    @PostMapping("/script/drafts/{draftId}/quality-check")
    public Map<String, Object> qualityCheck(@PathVariable Long draftId,
                                            @RequestBody(required = false) Map<String, Object> payload) {
        return service.qualityCheck(draftId, payload == null ? Map.of() : payload);
    }

    @PostMapping("/script/drafts/{draftId}/export")
    public Map<String, Object> exportDraft(@PathVariable Long draftId, @RequestBody(required = false) Map<String, Object> payload) {
        return service.exportDraft(draftId, payload == null ? Map.of() : payload);
    }

    @PostMapping("/script/drafts/{draftId}/export/file")
    public ResponseEntity<byte[]> exportDraftFile(@PathVariable Long draftId,
                                                  @RequestBody(required = false) Map<String, Object> payload) throws IOException {
        StoryExportFile file = service.exportDraftFile(draftId, payload == null ? Map.of() : payload);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(file.filename()))
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.bytes());
    }

    private String contentDisposition(String filename) {
        String fallback = filename == null || filename.isBlank() ? "download" : filename;
        fallback = fallback.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_")
                .replaceAll("[^\\x20-\\x7E]", "_");
        String encoded = URLEncoder.encode(filename == null ? "download" : filename, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }

    @GetMapping(path = "/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTask(@PathVariable Long taskId) throws IOException {
        SseEmitter emitter = new SseEmitter(30_000L);
        try {
            emitter.send(SseEmitter.event().name("progress").data(service.getGenerationTask(taskId)));
            emitter.complete();
        } catch (Exception e) {
            log.error("streamTask 失败 taskId={}", taskId, e);
            emitter.send(SseEmitter.event().name("error").data(Map.of(
                    "taskId", taskId,
                    "status", "failed",
                    "progress", 0,
                    "errorMessage", e.getMessage()
            )));
            emitter.complete();
        }
        return emitter;
    }
}
