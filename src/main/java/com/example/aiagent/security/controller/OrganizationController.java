package com.example.aiagent.security.controller;

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
import org.springframework.web.bind.annotation.PutMapping;
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
     * 获取组织详情（含 org 基本信息 + 成员列表，成员包含 username）
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

        var org = orgService.getOrganizationById(orgId);
        if (org == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "组织不存在"));
        }

        List<Map<String, Object>> members = orgService.getOrgMembersWithUsername(orgId);
        return ResponseEntity.ok(Map.of(
                "orgId",       org.getOrgId(),
                "name",        org.getName() != null ? org.getName() : "",
                "orgType",     org.getOrgType(),
                "description", org.getDescription() != null ? org.getDescription() : "",
                "ownerId",     org.getOwnerId(),
                "members",     members
        ));
    }

    /**
     * 编辑企业组织名称/描述
     * PUT /api/v1/org/{orgId}
     * Body: {"name": "新名称", "description": "新描述"}
     *
     * <p>仅 OWNER 可操作
     */
    @PutMapping("/{orgId}")
    public ResponseEntity<?> updateOrganization(
            @PathVariable String orgId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal String userId) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "组织名称不能为空"));
        }
        try {
            Organization updated = orgService.updateOrganization(
                    orgId, name.trim(), body.getOrDefault("description", ""), userId);
            return ResponseEntity.ok(Map.of(
                    "orgId",       updated.getOrgId(),
                    "name",        updated.getName(),
                    "description", updated.getDescription() != null ? updated.getDescription() : "",
                    "message",     "组织信息已更新"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 修改组织成员角色
     * PUT /api/v1/org/{orgId}/members/{memberUserId}
     * Body: {"role": "ADMIN"}
     *
     * <p>OWNER 可将成员改为 MEMBER / ADMIN；ADMIN 可将成员改为 MEMBER
     */
    @PutMapping("/{orgId}/members/{memberUserId}")
    public ResponseEntity<?> updateMemberRole(
            @PathVariable String orgId,
            @PathVariable String memberUserId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal String userId) {
        String newRole = body.get("role");
        if (newRole == null || (!newRole.equals("MEMBER") && !newRole.equals("ADMIN"))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "角色必须为 MEMBER 或 ADMIN"));
        }
        try {
            orgService.updateMemberRole(orgId, memberUserId, newRole, userId);
            return ResponseEntity.ok(Map.of(
                    "message", "成员角色已更新",
                    "orgId", orgId,
                    "userId", memberUserId,
                    "role", newRole
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 删除企业组织
     * DELETE /api/v1/org/{orgId}
     *
     * <p>仅 OWNER 可操作，PERSONAL 组织不可删除
     */
    @DeleteMapping("/{orgId}")
    public ResponseEntity<?> deleteOrganization(
            @PathVariable String orgId,
            @AuthenticationPrincipal String userId) {
        try {
            orgService.deleteOrganization(orgId, userId);
            return ResponseEntity.ok(Map.of("message", "组织已删除", "orgId", orgId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 退出组织
     * DELETE /api/v1/org/{orgId}/leave
     *
     * <p>非 OWNER 成员主动退出；OWNER 须先转让身份
     */
    @DeleteMapping("/{orgId}/leave")
    public ResponseEntity<?> leaveOrganization(
            @PathVariable String orgId,
            @AuthenticationPrincipal String userId) {
        try {
            orgService.leaveOrganization(orgId, userId);
            return ResponseEntity.ok(Map.of("message", "已成功退出组织", "orgId", orgId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 邀请成员加入组织（通过邮箱或用户名）
     * POST /api/v1/org/{orgId}/members
     * Body: {"emailOrUsername": "alice@example.com", "role": "MEMBER"}
     */
    @PostMapping("/{orgId}/members")
    public ResponseEntity<?> inviteMember(
            @PathVariable String orgId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal String userId) {
        try {
            String emailOrUsername = body.get("emailOrUsername");
            String role = body.getOrDefault("role", "MEMBER");

            String token = orgService.inviteByEmailOrUsername(orgId, emailOrUsername, role, userId);

            return ResponseEntity.ok(Map.of(
                    "message", "邀请已发送",
                    "orgId", orgId,
                    "emailOrUsername", emailOrUsername != null ? emailOrUsername : "",
                    "role", role,
                    "token", token
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 列出组织的待处理邀请
     * GET /api/v1/org/{orgId}/invitations
     */
    @GetMapping("/{orgId}/invitations")
    public ResponseEntity<?> listInvitations(
            @PathVariable String orgId,
            @AuthenticationPrincipal String userId) {
        if (!orgService.isMemberOf(orgId, userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "您不是该组织的成员"));
        }
        return ResponseEntity.ok(orgService.getPendingInvitations(orgId));
    }

    /**
     * 撤销组织邀请
     * DELETE /api/v1/org/{orgId}/invitations/{invitationId}
     */
    @DeleteMapping("/{orgId}/invitations/{invitationId}")
    public ResponseEntity<?> cancelInvitation(
            @PathVariable String orgId,
            @PathVariable Long invitationId,
            @AuthenticationPrincipal String userId) {
        try {
            orgService.cancelInvitation(orgId, invitationId, userId);
            return ResponseEntity.ok(Map.of("message", "邀请已撤销", "invitationId", invitationId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 接受组织邀请
     * POST /api/v1/org/invitations/{token}/accept
     */
    @PostMapping("/invitations/{token}/accept")
    public ResponseEntity<?> acceptInvitation(
            @PathVariable String token,
            @AuthenticationPrincipal String userId) {
        try {
            orgService.acceptInvitation(token, userId);
            return ResponseEntity.ok(Map.of("message", "已接受邀请"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 拒绝组织邀请
     * POST /api/v1/org/invitations/{token}/reject
     */
    @PostMapping("/invitations/{token}/reject")
    public ResponseEntity<?> rejectInvitation(
            @PathVariable String token,
            @AuthenticationPrincipal String userId) {
        try {
            orgService.rejectInvitation(token, userId);
            return ResponseEntity.ok(Map.of("message", "已拒绝邀请"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取当前用户收到的待处理邀请
     * GET /api/v1/org/invitations/my
     */
    @GetMapping("/invitations/my")
    public ResponseEntity<?> myInvitations(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(orgService.getMyPendingInvitations(userId));
    }

    /**
     * 列出组织成员（含 username）
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

        return ResponseEntity.ok(orgService.getOrgMembersWithUsername(orgId));
    }

    /**
     * 提交加入组织申请
     * POST /api/v1/org/{orgId}/join-requests
     * Body: {"message": "申请理由（可选）"}
     */
    @PostMapping("/{orgId}/join-requests")
    public ResponseEntity<?> applyJoin(
            @PathVariable String orgId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal String userId) {
        try {
            orgService.applyJoin(orgId, userId, body.get("message"));
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "message", "加入申请已提交，等待组织管理员审批",
                    "orgId", orgId
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 列出组织的待处理加入申请
     * GET /api/v1/org/{orgId}/join-requests
     */
    @GetMapping("/{orgId}/join-requests")
    public ResponseEntity<?> listJoinRequests(
            @PathVariable String orgId,
            @AuthenticationPrincipal String userId) {
        if (!orgService.isMemberOf(orgId, userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "您不是该组织的成员"));
        }
        return ResponseEntity.ok(orgService.getPendingJoinRequests(orgId));
    }

    /**
     * 通过加入申请
     * POST /api/v1/org/join-requests/{requestId}/approve
     */
    @PostMapping("/join-requests/{requestId}/approve")
    public ResponseEntity<?> approveJoinRequest(
            @PathVariable Long requestId,
            @AuthenticationPrincipal String userId) {
        try {
            orgService.approveJoinRequest(requestId, userId);
            return ResponseEntity.ok(Map.of("message", "已通过加入申请"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 拒绝加入申请
     * POST /api/v1/org/join-requests/{requestId}/reject
     */
    @PostMapping("/join-requests/{requestId}/reject")
    public ResponseEntity<?> rejectJoinRequest(
            @PathVariable Long requestId,
            @AuthenticationPrincipal String userId) {
        try {
            orgService.rejectJoinRequest(requestId, userId);
            return ResponseEntity.ok(Map.of("message", "已拒绝加入申请"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取当前用户提交的加入申请
     * GET /api/v1/org/join-requests/my
     */
    @GetMapping("/join-requests/my")
    public ResponseEntity<?> myJoinRequests(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(orgService.getMyJoinRequests(userId));
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

