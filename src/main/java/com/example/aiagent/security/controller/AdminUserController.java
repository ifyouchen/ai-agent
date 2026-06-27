package com.example.aiagent.security.controller;

import com.example.aiagent.admin.service.AdminUserQueryService;
import com.example.aiagent.security.entity.SysUser;
import com.example.aiagent.security.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员用户管理接口
 *
 * <p>所有接口均需要 ADMIN 角色（由 SecurityConfig 统一拦截）。
 *
 * <ul>
 *   <li>GET  /api/v1/admin/users               → 分页查询全部用户</li>
 *   <li>PUT  /api/v1/admin/users/{userId}/enable  → 启用账号</li>
 *   <li>PUT  /api/v1/admin/users/{userId}/disable → 禁用账号</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final SysUserMapper sysUserMapper;
    private final AdminUserQueryService adminUserQueryService;

    /**
     * 分页查询全部用户（支持 keyword 关键词过滤）
     * GET /api/v1/admin/users?page=0&size=20&keyword=xxx
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> listUsers(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String keyword,
            @AuthenticationPrincipal String operatorId) {

        return ResponseEntity.ok(adminUserQueryService.listUsers(page, size, keyword));
    }

    /**
     * 查询用户详情
     * GET /api/v1/admin/users/{userId}
     */
    @GetMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getUser(@PathVariable String userId) {
        return ResponseEntity.ok(adminUserQueryService.userDetail(userId));
    }

    /**
     * 启用账号
     * PUT /api/v1/admin/users/{userId}/enable
     */
    @PutMapping("/{userId}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> enableUser(
            @PathVariable String userId,
            @AuthenticationPrincipal String operatorId) {

        try {
            sysUserMapper.updateEnabled(userId, 1);
            log.info("[Admin] 启用用户 userId={} operatorId={}", userId, operatorId);
            return ResponseEntity.ok(Map.of("message", "已启用", "userId", userId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 设置用户角色
     * PUT /api/v1/admin/users/{userId}/role
     * Body: {"role": "ROLE_ADMIN"}
     */
    @PutMapping("/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> setRole(
            @PathVariable String userId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal String operatorId) {

        String role = body.get("role");
        if (role == null || (!role.equals("ROLE_ADMIN") && !role.equals("ROLE_USER"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "角色只能为 ROLE_ADMIN 或 ROLE_USER"));
        }
        if (userId.equals(operatorId) && role.equals("ROLE_USER")) {
            return ResponseEntity.badRequest().body(Map.of("error", "不能降级自己的管理员权限"));
        }
        // 保留现有角色列表，追加或覆盖
        SysUser target = sysUserMapper.findByUserId(userId)
                .orElse(null);
        if (target == null) return ResponseEntity.notFound().build();

        List<String> roles = new ArrayList<>(target.getRoleList());
        if (!roles.contains(role)) {
            if (role.equals("ROLE_ADMIN") && !roles.contains("ROLE_ADMIN")) {
                roles.add("ROLE_ADMIN");
            } else if (role.equals("ROLE_USER")) {
                roles.remove("ROLE_ADMIN"); // 降级
            }
        }
        sysUserMapper.updateRoles(userId, String.join(",", roles));
        log.info("[Admin] 更新角色 userId={} role={} operatorId={}", userId, role, operatorId);
        return ResponseEntity.ok(Map.of("message", "角色已更新", "userId", userId, "roles", roles));
    }

    /**
     * 禁用账号
     * PUT /api/v1/admin/users/{userId}/disable
     */
    @PutMapping("/{userId}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> disableUser(
            @PathVariable String userId,
            @AuthenticationPrincipal String operatorId) {

        if (userId.equals(operatorId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "不能禁用自己的账号"));
        }

        try {
            sysUserMapper.updateEnabled(userId, 0);
            log.info("[Admin] 禁用用户 userId={} operatorId={}", userId, operatorId);
            return ResponseEntity.ok(Map.of("message", "已禁用", "userId", userId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}

