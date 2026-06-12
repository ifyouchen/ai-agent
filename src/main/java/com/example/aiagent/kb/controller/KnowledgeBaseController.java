package com.example.aiagent.kb.controller;

import com.example.aiagent.kb.entity.Document;
import com.example.aiagent.kb.entity.KnowledgeBase;
import com.example.aiagent.kb.service.KbMemberService;
import com.example.aiagent.kb.service.KnowledgeBaseQueryService;
import com.example.aiagent.kb.service.KnowledgeBaseService;
import com.example.aiagent.rag.DocumentIngestService;
import com.example.aiagent.security.service.OrganizationService;
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
 * <p>所有接口均受 JWT 认证保护（SecurityConfig 中 anyRequest().authenticated()）。
 *
 * <p>多租户隔离模型（V2 企业级）：
 * <ul>
 *   <li>tenantId = 组织 ID（orgId），而非 userId</li>
 *   <li>个人用户：注册时自动创建"个人组织"，tenantId = "org_" + userId，行为等价于旧方案</li>
 *   <li>企业用户：管理员创建"企业组织"，邀请员工加入，共享知识库</li>
 *   <li>知识库访问通过 kb_member 表细粒度控制（OWNER/EDITOR/VIEWER）</li>
 * </ul>
 *
 * <p>接口列表：
 *   POST   /api/v1/kb                          → 创建知识库
 *   GET    /api/v1/kb                          → 列出我可访问的知识库
 *   DELETE /api/v1/kb/{kbId}                   → 删除知识库
 *   POST   /api/v1/kb/{kbId}/documents         → 上传文档
 *   GET    /api/v1/kb/{kbId}/documents         → 列出文档
 *   DELETE /api/v1/kb/{kbId}/documents/{docId} → 删除文档
 *   POST   /api/v1/kb/{kbId}/query             → 知识库问答
 *   GET    /api/v1/kb/{kbId}/stats             → 统计信息
 *   POST   /api/v1/kb/{kbId}/members           → 添加知识库成员
 *   GET    /api/v1/kb/{kbId}/members           → 列出知识库成员
 *   DELETE /api/v1/kb/{kbId}/members/{userId}   → 移除知识库成员
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;
    private final KnowledgeBaseQueryService kbQueryService;
    private final DocumentIngestService ingestService;
    private final OrganizationService orgService;
    private final KbMemberService kbMemberService;

    // ── 知识库 CRUD ───────────────────────────────────────

    /**
     * 创建知识库
     * POST /api/v1/kb
     * Body: {"name": "产品手册", "description": "公司产品相关文档"}
     *
     * <p>知识库创建在用户当前默认组织下。个人用户的默认组织就是个人组织，
     * 企业用户可以通过切换默认组织来选择知识库归属。
     */
    @PostMapping
    public ResponseEntity<?> createKnowledgeBase(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal String userId) {
        try {
            // ★ 关键变更：tenantId 从组织获取，而非直接用 userId
            String tenantId = orgService.getDefaultOrgId(userId);

            KnowledgeBase kb = kbService.createKnowledgeBase(
                    tenantId,
                    body.get("name"),
                    body.get("description"),
                    userId);

            // 创建者自动成为知识库 OWNER
            kbMemberService.addMember(kb.getId(), userId, "OWNER", userId);

            return ResponseEntity.status(HttpStatus.CREATED).body(kb);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 列出当前用户可访问的知识库
     * GET /api/v1/kb
     *
     * <p>包括：
     * 1. 用户所属组织下的知识库（组织成员可访问）
     * 2. 通过 kb_member 显式授权的知识库
     */
    @GetMapping
    public ResponseEntity<?> listKnowledgeBases(
            @AuthenticationPrincipal String userId) {
        // ★ 变更：列出用户可访问的所有知识库（跨组织）
        String tenantId = orgService.getDefaultOrgId(userId);
        List<KnowledgeBase> kbs = kbService.listKnowledgeBases(tenantId);

        // 同时获取通过 kb_member 授权的知识库
        List<Long> memberKbIds = kbMemberService.getAccessibleKbIds(userId);
        for (Long kbId : memberKbIds) {
            if (kbs.stream().noneMatch(kb -> kb.getId().equals(kbId))) {
                kbService.getKnowledgeBase(tenantId, kbId);  // 会校验权限
                // 如果不在列表中，追加
                kbs.add(kbService.getKnowledgeBase(tenantId, kbId));
            }
        }

        return ResponseEntity.ok(kbs);
    }

    /**
     * 删除知识库（级联删除所有文档和切片）
     * DELETE /api/v1/kb/{kbId}
     *
     * <p>仅 OWNER 角色可删除知识库
     */
    @DeleteMapping("/{kbId}")
    public ResponseEntity<?> deleteKnowledgeBase(
            @PathVariable Long kbId,
            @AuthenticationPrincipal String userId) {
        try {
            String tenantId = orgService.getDefaultOrgId(userId);

            // ★ 变更：权限检查通过 kb_member
            String role = kbMemberService.checkAccess(kbId, userId, tenantId);
            if (!"OWNER".equals(role)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "只有知识库拥有者才能删除知识库"));
            }

            kbService.deleteKnowledgeBase(tenantId, kbId);
            kbMemberService.deleteAllMembers(kbId);

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
     *
     * <p>需要 EDITOR 或 OWNER 角色
     */
    @PostMapping("/{kbId}/documents")
    public ResponseEntity<?> uploadDocument(
            @PathVariable Long kbId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal String userId) {

        String tenantId = orgService.getDefaultOrgId(userId);

        // ★ 变更：权限检查
        if (!kbMemberService.canEdit(kbId, userId, tenantId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "您没有编辑权限，需要 EDITOR 或 OWNER 角色"));
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件不能为空"));
        }

        try {
            int chunkCount = ingestService.ingestFile(file, tenantId, kbId);
            log.info("文档上传完成 userId={} tenantId={} kbId={} file={} chunks={}",
                    userId, tenantId, kbId, file.getOriginalFilename(), chunkCount);
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
     *
     * <p>需要至少 VIEWER 角色
     */
    @GetMapping("/{kbId}/documents")
    public ResponseEntity<?> listDocuments(
            @PathVariable Long kbId,
            @AuthenticationPrincipal String userId) {
        try {
            String tenantId = orgService.getDefaultOrgId(userId);

            // ★ 变更：权限检查
            String role = kbMemberService.checkAccess(kbId, userId, tenantId);
            if (role == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "您没有访问该知识库的权限"));
            }

            List<Document> docs = kbService.getDocuments(tenantId, kbId);
            return ResponseEntity.ok(docs);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 删除文档
     * DELETE /api/v1/kb/{kbId}/documents/{docId}
     *
     * <p>需要 EDITOR 或 OWNER 角色
     */
    @DeleteMapping("/{kbId}/documents/{docId}")
    public ResponseEntity<?> deleteDocument(
            @PathVariable Long kbId,
            @PathVariable Long docId,
            @AuthenticationPrincipal String userId) {
        try {
            String tenantId = orgService.getDefaultOrgId(userId);

            // ★ 变更：权限检查
            if (!kbMemberService.canEdit(kbId, userId, tenantId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "您没有编辑权限，需要 EDITOR 或 OWNER 角色"));
            }

            kbService.deleteDocument(tenantId, docId);
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
     *
     * <p>需要至少 VIEWER 角色
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
            String tenantId = orgService.getDefaultOrgId(userId);

            // ★ 变更：权限检查
            String role = kbMemberService.checkAccess(kbId, userId, tenantId);
            if (role == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "您没有访问该知识库的权限"));
            }

            // ★ 变更：tenantId 用组织 ID（而非 userId）
            KnowledgeBaseQueryService.QueryResult result =
                    kbQueryService.query(tenantId, kbId, userId, question);

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

    // ── 知识库成员管理 ────────────────────────────────────

    /**
     * 添加知识库成员
     * POST /api/v1/kb/{kbId}/members
     * Body: {"userId": "user-xxx", "role": "EDITOR"}
     *
     * <p>仅 OWNER 角色可管理成员
     */
    @PostMapping("/{kbId}/members")
    public ResponseEntity<?> addMember(
            @PathVariable Long kbId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal String userId) {
        try {
            if (!kbMemberService.canManageMembers(kbId, userId, orgService.getDefaultOrgId(userId))) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "只有知识库拥有者才能管理成员"));
            }

            String targetUserId = body.get("userId");
            String role = body.getOrDefault("role", "VIEWER");

            kbMemberService.addMember(kbId, targetUserId, role, userId);
            return ResponseEntity.ok(Map.of("message", "成员添加成功",
                    "kbId", kbId, "userId", targetUserId, "role", role));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 列出知识库成员
     * GET /api/v1/kb/{kbId}/members
     */
    @GetMapping("/{kbId}/members")
    public ResponseEntity<?> listMembers(
            @PathVariable Long kbId,
            @AuthenticationPrincipal String userId) {
        try {
            String tenantId = orgService.getDefaultOrgId(userId);
            String role = kbMemberService.checkAccess(kbId, userId, tenantId);
            if (role == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "您没有访问该知识库的权限"));
            }

            return ResponseEntity.ok(kbMemberService.getMembers(kbId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 移除知识库成员
     * DELETE /api/v1/kb/{kbId}/members/{memberUserId}
     */
    @DeleteMapping("/{kbId}/members/{memberUserId}")
    public ResponseEntity<?> removeMember(
            @PathVariable Long kbId,
            @PathVariable String memberUserId,
            @AuthenticationPrincipal String userId) {
        try {
            if (!kbMemberService.canManageMembers(kbId, userId, orgService.getDefaultOrgId(userId))) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "只有知识库拥有者才能移除成员"));
            }

            kbMemberService.removeMember(kbId, memberUserId, userId);
            return ResponseEntity.ok(Map.of("message", "成员已移除",
                    "kbId", kbId, "userId", memberUserId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
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
            String tenantId = orgService.getDefaultOrgId(userId);

            String role = kbMemberService.checkAccess(kbId, userId, tenantId);
            if (role == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "您没有访问该知识库的权限"));
            }

            Map<String, Object> stats = kbService.getStats(tenantId, kbId);
            return ResponseEntity.ok(stats);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}

