package com.example.aiagent.security.controller;

import com.example.aiagent.security.entity.OrgMember;
import com.example.aiagent.security.entity.Organization;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 组织管理接口
 *
 * <p>企业级多租户核心：组织是 tenantId 的载体。
 * <ul>
 *   <li>个人用户注册时自动创建"个人组织"（PERSONAL）</li>
 *   <li>企业管理员可创建"企业组织"（ENTERPRISE），邀请员工加入</li>
 *   <li>知识库的 tenantId 指向组织 ID，实现同组织内知识库共享</li>
 * </ul>
 *
 * <p>接口列表：
 *   POST   /api/v1/org                          → 创建企业组织
 *   GET    /api/v1/org                          → 列出我的组织
 *   GET    /api/v1/org/{orgId}                  → 获取组织详情
 *   POST   /api/v1/org/{orgId}/members          → 邀请成员
 *   GET    /api/v1/org/{orgId}/members          → 列出组织成员
 *   DELETE /api/v1/org/{orgId}/members/{userId}  → 移除成员
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/org")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService orgService;

    /**
     * 创建企业组织
     * POST /api/v1/org
     * Body: {"name": "XX公司", "description": "公司共享知识库"}
     */
    @PostMapping
    public ResponseEntity<?> createOrganization(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal String userId) {
        try {
            Organization org = orgService.createEnterpriseOrganization(
                    body.get("name"),
                    userId,
                    body.get("description"));

            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "orgId", org.getOrgId(),
                    "name", org.getName(),
                    "orgType", org.getOrgType(),
                    "message", "企业组织创建成功，您是组织拥有者"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 列出我加入的所有组织（含组织名称和类型）
     * GET /api/v1/org
     */
    @GetMapping
    public ResponseEntity<?> listMyOrganizations(
            @AuthenticationPrincipal String userId) {
        List<Map<String, Object>> result = orgService.getUserOrganizationsWithDetail(userId);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取组织详情
     * GET /api/v1/org/{orgId}
     */
    @GetMapping("/{orgId}")
    public ResponseEntity<?> getOrganization(
            @PathVariable String orgId,
            @AuthenticationPrincipal String userId) {
        if (!orgService.isMemberOf(orgId, userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "您不是该组织的成员"));
        }

        // 返回成员列表和组织信息
        List<OrgMember> members = orgService.getOrgMembers(orgId);
        return ResponseEntity.ok(Map.of("members", members));
    }

    /**
     * 邀请成员加入组织
     * POST /api/v1/org/{orgId}/members
     * Body: {"userId": "user-xxx", "role": "MEMBER"}
     */
    @PostMapping("/{orgId}/members")
    public ResponseEntity<?> inviteMember(
            @PathVariable String orgId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal String userId) {
        try {
            String targetUserId = body.get("userId");
            String role = body.getOrDefault("role", "MEMBER");

            orgService.inviteMember(orgId, targetUserId, role, userId);

            return ResponseEntity.ok(Map.of(
                    "message", "成员邀请成功",
                    "orgId", orgId,
                    "userId", targetUserId,
                    "role", role
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 列出组织成员
     * GET /api/v1/org/{orgId}/members
     */
    @GetMapping("/{orgId}/members")
    public ResponseEntity<?> listMembers(
            @PathVariable String orgId,
            @AuthenticationPrincipal String userId) {
        if (!orgService.isMemberOf(orgId, userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "您不是该组织的成员"));
        }

        List<OrgMember> members = orgService.getOrgMembers(orgId);
        return ResponseEntity.ok(members);
    }

    /**
     * 移除组织成员
     * DELETE /api/v1/org/{orgId}/members/{memberUserId}
     */
    @DeleteMapping("/{orgId}/members/{memberUserId}")
    public ResponseEntity<?> removeMember(
            @PathVariable String orgId,
            @PathVariable String memberUserId,
            @AuthenticationPrincipal String userId) {
        try {
            orgService.removeMember(orgId, memberUserId, userId);
            return ResponseEntity.ok(Map.of(
                    "message", "成员已移除",
                    "orgId", orgId,
                    "userId", memberUserId
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}

