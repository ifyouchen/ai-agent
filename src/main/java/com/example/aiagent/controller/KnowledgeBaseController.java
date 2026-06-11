package com.example.aiagent.controller;

import com.example.aiagent.kb.entity.Document;
import com.example.aiagent.kb.entity.KnowledgeBase;
import com.example.aiagent.kb.service.KnowledgeBaseService;
import com.example.aiagent.rag.DocumentIngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口
 *
 * 提供知识库 CRUD、文档管理和统计查询能力。
 * 所有多租户相关接口都需要传入 tenantId 参数。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final DocumentIngestService ingestService;
    private final KnowledgeBaseService  kbService;

    // ====================================================================
    // 原有接口（保持兼容）
    // ====================================================================

    /**
     * 上传并导入文档到知识库
     *
     * POST /api/v1/kb/ingest
     * Content-Type: multipart/form-data
     * file: 你的文档（PDF、Word、TXT 等）
     */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingestDocument(
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "文件不能为空"));
        }

        log.info("收到文档导入请求: {}, 大小: {} bytes",
                file.getOriginalFilename(), file.getSize());

        try {
            int chunks = ingestService.ingestFile(file);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "filename", file.getOriginalFilename(),
                    "chunks", chunks,
                    "message", "文档导入成功，已切分为 " + chunks + " 个片段"
            ));
        } catch (IOException e) {
            log.error("文档导入失败: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "导入失败：" + e.getMessage()));
        }
    }

    /**
     * 健康检查（验证知识库是否可用）
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "message", "知识库服务正常");
    }

    // ====================================================================
    // 知识库管理接口
    // ====================================================================

    /**
     * 列出租户下的所有知识库
     *
     * GET /api/v1/kb/list?tenantId=xxx
     */
    @GetMapping("/list")
    public ResponseEntity<List<KnowledgeBase>> listKnowledgeBases(
            @RequestParam String tenantId) {

        log.info("列出知识库 tenantId={}", tenantId);
        List<KnowledgeBase> list = kbService.listKnowledgeBases(tenantId);
        return ResponseEntity.ok(list);
    }

    /**
     * 创建知识库
     *
     * POST /api/v1/kb/create
     * Body: {"tenantId":"xxx","name":"我的知识库","description":"..."}
     */
    @PostMapping("/create")
    public ResponseEntity<KnowledgeBase> createKnowledgeBase(
            @RequestBody Map<String, String> body) {

        String tenantId    = body.get("tenantId");
        String name        = body.get("name");
        String description = body.get("description");

        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        log.info("创建知识库 tenantId={} name={}", tenantId, name);
        try {
            KnowledgeBase kb = kbService.createKnowledgeBase(tenantId, name, description);
            return ResponseEntity.ok(kb);
        } catch (IllegalArgumentException e) {
            log.warn("创建知识库失败: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 列出知识库下的文档
     *
     * GET /api/v1/kb/{kbId}/documents?tenantId=xxx
     */
    @GetMapping("/{kbId}/documents")
    public ResponseEntity<List<Document>> getDocuments(
            @PathVariable Long kbId,
            @RequestParam String tenantId) {

        log.info("列出文档 kbId={} tenantId={}", kbId, tenantId);
        try {
            List<Document> docs = kbService.getDocuments(tenantId, kbId);
            return ResponseEntity.ok(docs);
        } catch (IllegalArgumentException e) {
            log.warn("获取文档列表失败: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 删除文档（同时删除切片并更新知识库 docCount）
     *
     * DELETE /api/v1/kb/{kbId}/documents/{docId}?tenantId=xxx
     */
    @DeleteMapping("/{kbId}/documents/{docId}")
    public ResponseEntity<Map<String, Object>> deleteDocument(
            @PathVariable Long kbId,
            @PathVariable Long docId,
            @RequestParam String tenantId) {

        log.info("删除文档 kbId={} docId={} tenantId={}", kbId, docId, tenantId);
        try {
            kbService.deleteDocument(tenantId, docId);
            return ResponseEntity.ok(Map.of("success", true, "docId", docId));
        } catch (IllegalArgumentException e) {
            log.warn("删除文档失败: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 获取知识库统计信息
     *
     * GET /api/v1/kb/{kbId}/stats?tenantId=xxx
     *
     * 返回：docCount、chunkCount、recentQueries（近 7 天）、answerStats、recentLogs
     */
    @GetMapping("/{kbId}/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @PathVariable Long kbId,
            @RequestParam String tenantId) {

        log.info("获取统计信息 kbId={} tenantId={}", kbId, tenantId);
        try {
            Map<String, Object> stats = kbService.getStats(tenantId, kbId);
            return ResponseEntity.ok(stats);
        } catch (IllegalArgumentException e) {
            log.warn("获取统计信息失败: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}
