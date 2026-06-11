package com.example.aiagent.kb.controller;

import com.example.aiagent.kb.entity.Document;
import com.example.aiagent.kb.entity.KnowledgeBase;
import com.example.aiagent.kb.service.KnowledgeBaseQueryService;
import com.example.aiagent.kb.service.KnowledgeBaseService;
import com.example.aiagent.rag.DocumentIngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口
 *
 * 所有接口均受 JWT 认证保护（SecurityConfig 中 anyRequest().authenticated()）。
 * 多租户隔离通过将 userId 作为 tenantId 实现（每个用户独立的知识空间）。
 *
 * 接口列表：
 *   POST   /api/v1/kb                          → 创建知识库
 *   GET    /api/v1/kb                          → 列出我的知识库
 *   DELETE /api/v1/kb/{kbId}                   → 删除知识库
 *   POST   /api/v1/kb/{kbId}/documents         → 上传文档
 *   GET    /api/v1/kb/{kbId}/documents         → 列出文档
 *   DELETE /api/v1/kb/{kbId}/documents/{docId} → 删除文档
 *   POST   /api/v1/kb/{kbId}/query             → 知识库问答
 *   GET    /api/v1/kb/{kbId}/stats             → 统计信息
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;
    private final KnowledgeBaseQueryService kbQueryService;
    private final DocumentIngestService ingestService;

    // ── 知识库 CRUD ───────────────────────────────────────

    /**
     * 创建知识库
     * POST /api/v1/kb
     * Body: {"name": "产品手册", "description": "公司产品相关文档"}
     */
    @PostMapping
    public ResponseEntity<?> createKnowledgeBase(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal String userId) {
        try {
            KnowledgeBase kb = kbService.createKnowledgeBase(
                    userId,
                    body.get("name"),
                    body.get("description"));
            return ResponseEntity.status(HttpStatus.CREATED).body(kb);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 列出当前用户的知识库
     * GET /api/v1/kb
     */
    @GetMapping
    public ResponseEntity<List<KnowledgeBase>> listKnowledgeBases(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(kbService.listKnowledgeBases(userId));
    }

    /**
     * 删除知识库（级联删除所有文档和切片）
     * DELETE /api/v1/kb/{kbId}
     */
    @DeleteMapping("/{kbId}")
    public ResponseEntity<?> deleteKnowledgeBase(
            @PathVariable Long kbId,
            @AuthenticationPrincipal String userId) {
        try {
            kbService.deleteKnowledgeBase(userId, kbId);
            return ResponseEntity.ok(Map.of("message", "知识库已删除", "kbId", kbId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ── 文档管理 ──────────────────────────────────────────

    /**
     * 上传文档到知识库（支持 PDF、Word、TXT 等）
     * POST /api/v1/kb/{kbId}/documents
     * Content-Type: multipart/form-data
     */
    @PostMapping("/{kbId}/documents")
    public ResponseEntity<?> uploadDocument(
            @PathVariable Long kbId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal String userId) {

        // 校验知识库归属
        try {
            kbService.getKnowledgeBase(userId, kbId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件不能为空"));
        }

        try {
            int chunkCount = ingestService.ingestFile(file, userId, kbId);
            log.info("文档上传完成 userId={} kbId={} file={} chunks={}",
                    userId, kbId, file.getOriginalFilename(), chunkCount);
            return ResponseEntity.ok(Map.of(
                    "message",    "文档上传成功",
                    "filename",   file.getOriginalFilename(),
                    "chunkCount", chunkCount,
                    "kbId",       kbId));
        } catch (IOException e) {
            log.error("文档上传失败 kbId={} file={}: {}", kbId, file.getOriginalFilename(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "文档处理失败：" + e.getMessage()));
        }
    }

    /**
     * 列出知识库下所有文档
     * GET /api/v1/kb/{kbId}/documents
     */
    @GetMapping("/{kbId}/documents")
    public ResponseEntity<?> listDocuments(
            @PathVariable Long kbId,
            @AuthenticationPrincipal String userId) {
        try {
            List<Document> docs = kbService.getDocuments(userId, kbId);
            return ResponseEntity.ok(docs);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 删除文档
     * DELETE /api/v1/kb/{kbId}/documents/{docId}
     */
    @DeleteMapping("/{kbId}/documents/{docId}")
    public ResponseEntity<?> deleteDocument(
            @PathVariable Long kbId,
            @PathVariable Long docId,
            @AuthenticationPrincipal String userId) {
        try {
            kbService.deleteDocument(userId, docId);
            return ResponseEntity.ok(Map.of("message", "文档已删除", "docId", docId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ── 知识库问答 ────────────────────────────────────────

    /**
     * 知识库问答（置信度评估 + 多租户隔离）
     * POST /api/v1/kb/{kbId}/query
     * Body: {"question": "退款政策是什么？"}
     */
    @PostMapping("/{kbId}/query")
    public ResponseEntity<?> query(
            @PathVariable Long kbId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal String userId) {

        String question = body.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "问题不能为空"));
        }

        try {
            KnowledgeBaseQueryService.QueryResult result =
                    kbQueryService.query(userId, kbId, userId, question);

            return ResponseEntity.ok(Map.of(
                    "answer",      result.answer(),
                    "answerFound", result.answerFound(),
                    "confidence",  String.format("%.3f", result.confidence()),
                    "citations",   result.ragResponse().getCitations().stream()
                            .limit(3)
                            .map(c -> Map.of(
                                    "source",   c.getDocumentName() != null ? c.getDocumentName() : "未知来源",
                                    "score",    String.format("%.3f", c.getRelevanceScore()),
                                    "snippet",  c.getExcerpt() != null && c.getExcerpt().length() > 100
                                                    ? c.getExcerpt().substring(0, 100) + "..."
                                                    : (c.getExcerpt() != null ? c.getExcerpt() : "")))
                            .toList()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ── 统计信息 ──────────────────────────────────────────

    /**
     * 知识库统计信息（文档数、切片数、近7天查询量等）
     * GET /api/v1/kb/{kbId}/stats
     */
    @GetMapping("/{kbId}/stats")
    public ResponseEntity<?> getStats(
            @PathVariable Long kbId,
            @AuthenticationPrincipal String userId) {
        try {
            Map<String, Object> stats = kbService.getStats(userId, kbId);
            return ResponseEntity.ok(stats);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}

