package com.example.aiagent.security.controller;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    /**
     * 分页查询全部用户
     * GET /api/v1/admin/users?page=0&size=20
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> listUsers(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal String operatorId) {

        if (size > 100) size = 100;
        int offset = page * size;

        List<SysUser> users = sysUserMapper.findAll(offset, size);
        long total = sysUserMapper.countAll();

        // 脱敏：不返回 passwordHash
        List<Map<String, Object>> items = users.stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId",    u.getUserId());
            m.put("username",  u.getUsername());
            m.put("roles",     u.getRoleList());
            m.put("enabled",   u.getEnabled());
            m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : null);
            return m;
        }).toList();

        return ResponseEntity.ok(Map.of(
                "items",      items,
                "total",      total,
                "page",       page,
                "size",       size,
                "totalPages", (int) Math.ceil((double) total / size)
        ));
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

