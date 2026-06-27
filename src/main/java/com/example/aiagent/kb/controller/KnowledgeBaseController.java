package com.example.aiagent.kb.controller;

import com.example.aiagent.kb.entity.Document;
import com.example.aiagent.kb.entity.KnowledgeBase;
import com.example.aiagent.kb.mapper.ChunkMapper;
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
import org.springframework.web.bind.annotation.PutMapping;
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
 * <p>【组织联动】所有接口支持可选的 {@code ?orgId=} 请求参数：
 * <ul>
 *   <li>不传 → 使用用户个人默认组织（向后兼容）</li>
 *   <li>传入 → 校验用户是否为该组织成员，通过后使用该组织的 tenantId</li>
 * </ul>
 *
 * <p>接口列表：
 *   POST   /api/v1/kb                              → 创建知识库（归属 orgId 指定的组织）
 *   GET    /api/v1/kb?orgId=xxx                    → 列出指定组织下的知识库
 *   DELETE /api/v1/kb/{kbId}                       → 删除知识库
 *   POST   /api/v1/kb/{kbId}/documents             → 上传文档
 *   GET    /api/v1/kb/{kbId}/documents             → 列出文档
 *   DELETE /api/v1/kb/{kbId}/documents/{docId}     → 删除文档
 *   POST   /api/v1/kb/{kbId}/query                 → 知识库问答
 *   GET    /api/v1/kb/{kbId}/stats                 → 统计信息
 *   POST   /api/v1/kb/{kbId}/members               → 添加知识库成员
 *   GET    /api/v1/kb/{kbId}/members               → 列出知识库成员
 *   DELETE /api/v1/kb/{kbId}/members/{userId}       → 移除知识库成员
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
    private final ChunkMapper chunkMapper;

    // ── 知识库 CRUD ───────────────────────────────────────

    /**
     * 创建知识库
     * POST /api/v1/kb?orgId=xxx
     * Body: {"name": "产品手册", "description": "公司产品相关文档"}
     *
     * <p>知识库归属由 orgId 参数决定：
     * <ul>
     *   <li>不传 orgId → 在个人默认组织下创建</li>
     *   <li>传 orgId   → 在指定组织下创建（需是该组织成员）</li>
     * </ul>
     */
    @PostMapping
    public ResponseEntity<?> createKnowledgeBase(
            @RequestBody Map<String, String> body,
            @RequestParam(required = false) String orgId,
            @AuthenticationPrincipal String userId) {
        try {
            String tenantId = orgService.resolveOrgId(userId, orgId);

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
     * GET /api/v1/kb?orgId=xxx
     *
     * <p>列出逻辑：只列出指定组织（orgId）下的所有知识库。
     * 跨组织共享知识库不混入当前组织列表，避免前端用当前 orgId 访问其他租户的 kbId。
     * <p>不传 orgId 则使用默认个人组织（向后兼容）。
     */
    @GetMapping
    public ResponseEntity<?> listKnowledgeBases(
            @RequestParam(required = false) String orgId,
            @AuthenticationPrincipal String userId) {
        try {
            String tenantId = orgService.resolveOrgId(userId, orgId);
            List<KnowledgeBase> kbs = kbService.listKnowledgeBases(tenantId);

            return ResponseEntity.ok(kbs);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 编辑知识库名称和描述
     * PUT /api/v1/kb/{kbId}
     * Body: {"name": "新名称", "description": "新描述"}
     *
     * <p>仅 OWNER 角色可编辑
     */
    @PutMapping("/{kbId}")
    public ResponseEntity<?> updateKnowledgeBase(
            @PathVariable Long kbId,
            @RequestBody Map<String, String> body,
            @RequestParam(required = false) String orgId,
            @AuthenticationPrincipal String userId) {
        try {
            String tenantId = orgService.resolveOrgId(userId, orgId);

            String role = kbMemberService.checkAccess(kbId, userId, tenantId);
            if (!"OWNER".equals(role)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "只有知识库拥有者才能编辑知识库"));
            }

            String name = body.get("name");
            if (name == null || name.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "知识库名称不能为空"));
            }

            KnowledgeBase updated = kbService.updateKnowledgeBase(
                    tenantId, kbId, name.trim(), body.getOrDefault("description", ""));
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
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
            @RequestParam(required = false) String orgId,
            @AuthenticationPrincipal String userId) {
        try {
            String tenantId = orgService.resolveOrgId(userId, orgId);

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
            @RequestParam(required = false) String orgId,
            @AuthenticationPrincipal String userId) {

        try {
            String tenantId = orgService.resolveOrgId(userId, orgId);

            if (!kbMemberService.canEdit(kbId, userId, tenantId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "您没有编辑权限，需要知识库 EDITOR/OWNER，或组织 ADMIN/OWNER 角色"));
            }

            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "文件不能为空"));
            }

            // 异步解析：立即返回 documentId，后台完成解析和向量化
            // 前端可通过 GET /api/v1/kb/{kbId}/documents 轮询 parseStatus 获取进度
            Long documentId = ingestService.ingestFileAsync(file, tenantId, kbId, userId);
            log.info("文档已提交异步解析 userId={} tenantId={} kbId={} file={} documentId={}",
                    userId, tenantId, kbId, file.getOriginalFilename(), documentId);
            return ResponseEntity.ok(Map.of(
                    "message",    "文档上传成功，正在后台解析...",
                    "filename",   file.getOriginalFilename(),
                    "documentId", documentId,
                    "status",     "PROCESSING",
                    "kbId",       kbId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
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
            @RequestParam(required = false) String orgId,
            @AuthenticationPrincipal String userId) {
        try {
            String tenantId = orgService.resolveOrgId(userId, orgId);

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
     * 查询单个文档解析状态（前端轮询用，比拉全量列表更轻量）
     * GET /api/v1/kb/{kbId}/documents/{docId}/status
     *
     * <p>返回 {id, parseStatus, chunkCount, parseError}
     * <p>需要至少 VIEWER 角色
     */
    @GetMapping("/{kbId}/documents/{docId}/status")
    public ResponseEntity<?> getDocumentStatus(
            @PathVariable Long kbId,
            @PathVariable Long docId,
            @RequestParam(required = false) String orgId,
            @AuthenticationPrincipal String userId) {
        try {
            String tenantId = orgService.resolveOrgId(userId, orgId);
            if (kbMemberService.checkAccess(kbId, userId, tenantId) == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "您没有访问该知识库的权限"));
            }
            Document doc = kbService.getDocumentById(docId)
                    .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
            return ResponseEntity.ok(Map.of(
                    "id",          doc.getId(),
                    "parseStatus", doc.getParseStatus(),
                    "chunkCount",  doc.getChunkCount(),
                    "parseError",  doc.getParseError() != null ? doc.getParseError() : ""
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 查询文档切片列表（前端预览 RAG 分片效果）
     * GET /api/v1/kb/{kbId}/documents/{docId}/chunks?limit=20
     *
     * <p>需要至少 VIEWER 角色；默认返回前 20 个激活切片
     */
    @GetMapping("/{kbId}/documents/{docId}/chunks")
    public ResponseEntity<?> listChunks(
            @PathVariable Long kbId,
            @PathVariable Long docId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String orgId,
            @AuthenticationPrincipal String userId) {
        try {
            String tenantId = orgService.resolveOrgId(userId, orgId);
            if (kbMemberService.checkAccess(kbId, userId, tenantId) == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "您没有访问该知识库的权限"));
            }
            // 限制返回数量，防止超大文档
            int safeLimit = Math.min(limit, 50);
            var chunks = chunkMapper.findByDocIdAndIsActive(docId, true);
            var items = chunks.stream().limit(safeLimit).map(c -> {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("id",         c.getId());
                item.put("index",      c.getChunkIndex());
                item.put("content",    c.getContent() != null && c.getContent().length() > 500
                                        ? c.getContent().substring(0, 500) + "…"
                                        : c.getContent());
                item.put("tokenCount", c.getTokenCount());
                return item;
            }).toList();
            return ResponseEntity.ok(Map.of(
                    "chunks",     items,
                    "total",      chunks.size(),
                    "showing",    items.size()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
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
            @RequestParam(required = false) String orgId,
            @AuthenticationPrincipal String userId) {
        try {
            String tenantId = orgService.resolveOrgId(userId, orgId);

            if (!kbMemberService.canEdit(kbId, userId, tenantId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "您没有编辑权限，需要知识库 EDITOR/OWNER，或组织 ADMIN/OWNER 角色"));
            }

            kbService.deleteDocument(tenantId, docId);
            return ResponseEntity.ok(Map.of("message", "文档已删除", "docId", docId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Fix 3: 重新解析失败文档
     * POST /api/v1/kb/{kbId}/documents/{docId}/retry
     *
     * <p>只有 parseStatus=FAILED 的文档才能重试，需要 EDITOR 或 OWNER 角色
     */
    @PostMapping("/{kbId}/documents/{docId}/retry")
    public ResponseEntity<?> retryDocument(
            @PathVariable Long kbId,
            @PathVariable Long docId,
            @RequestParam(required = false) String orgId,
            @AuthenticationPrincipal String userId) {
        try {
            String tenantId = orgService.resolveOrgId(userId, orgId);

            if (!kbMemberService.canEdit(kbId, userId, tenantId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "您没有编辑权限，需要知识库 EDITOR/OWNER，或组织 ADMIN/OWNER 角色"));
            }

            kbService.retryDocument(tenantId, docId, userId);
            return ResponseEntity.ok(Map.of("message", "已重新提交解析", "docId", docId));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
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
            @RequestParam(required = false) String orgId,
            @AuthenticationPrincipal String userId) {

        String question = body.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "问题不能为空"));
        }

        try {
            String tenantId = orgService.resolveOrgId(userId, orgId);

            String role = kbMemberService.checkAccess(kbId, userId, tenantId);
            if (role == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "您没有访问该知识库的权限"));
            }

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
            @RequestParam(required = false) String orgId,
            @AuthenticationPrincipal String userId) {
        try {
            String tenantId = orgService.resolveOrgId(userId, orgId);
            if (!kbMemberService.canManageMembers(kbId, userId, tenantId)) {
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
     * 修改知识库成员角色
     * PUT /api/v1/kb/{kbId}/members/{memberUserId}
     * Body: {"role": "EDITOR"}
     *
     * <p>仅 OWNER 角色可修改成员角色
     */
    @PutMapping("/{kbId}/members/{memberUserId}")
    public ResponseEntity<?> updateMemberRole(
            @PathVariable Long kbId,
            @PathVariable String memberUserId,
            @RequestBody Map<String, String> body,
            @RequestParam(required = false) String orgId,
            @AuthenticationPrincipal String userId) {
        try {
            String tenantId = orgService.resolveOrgId(userId, orgId);
            if (!kbMemberService.canManageMembers(kbId, userId, tenantId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "只有知识库拥有者才能修改成员角色"));
            }
            String newRole = body.get("role");
            if (newRole == null || (!newRole.equals("VIEWER") && !newRole.equals("EDITOR"))) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "角色必须为 VIEWER 或 EDITOR"));
            }
            kbMemberService.updateMemberRole(kbId, memberUserId, newRole, userId);
            return ResponseEntity.ok(Map.of("message", "成员角色已更新",
                    "kbId", kbId, "userId", memberUserId, "role", newRole));
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
            @RequestParam(required = false) String orgId,
            @AuthenticationPrincipal String userId) {
        try {
            String tenantId = orgService.resolveOrgId(userId, orgId);
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
            @RequestParam(required = false) String orgId,
            @AuthenticationPrincipal String userId) {
        try {
            String tenantId = orgService.resolveOrgId(userId, orgId);
            if (!kbMemberService.canManageMembers(kbId, userId, tenantId)) {
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
            @RequestParam(required = false) String orgId,
            @AuthenticationPrincipal String userId) {
        try {
            String tenantId = orgService.resolveOrgId(userId, orgId);

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

