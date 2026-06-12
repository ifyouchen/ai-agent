package com.example.aiagent.security.controller;

import com.example.aiagent.security.dto.AuthResponse;
import com.example.aiagent.security.dto.LoginRequest;
import com.example.aiagent.security.dto.RegisterRequest;
import com.example.aiagent.security.service.AuditLogService;
import com.example.aiagent.security.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口
 *
 * POST /api/v1/auth/login     → 登录，返回 JWT
 * POST /api/v1/auth/register  → 注册，返回 JWT
 *
 * 这两个接口在 SecurityConfig 中设置为 permitAll()，无需 Token 即可访问。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuditLogService auditLogService;

    /**
     * 用户登录
     *
     * 请求体：{"username": "alice", "password": "secret123"}
     * 响应体：{"token": "eyJ...", "tokenType": "Bearer", "expiresIn": 86400, ...}
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                    HttpServletRequest httpRequest) {
        String clientIp = getClientIp(httpRequest);
        try {
            AuthResponse response = authService.login(request);

            auditLogService.log(AuditLogService.EventType.LOGIN_SUCCESS,
                    response.userId(), null, clientIp, true,
                    Map.of("username", request.username()));

            return ResponseEntity.ok(response);

        } catch (AuthenticationException e) {
            log.warn("登录失败 username={} ip={} reason={}", request.username(), clientIp, e.getMessage());

            auditLogService.log(AuditLogService.EventType.LOGIN_FAILED,
                    null, null, clientIp, false,
                    Map.of("username", request.username(), "reason", e.getMessage()));

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "用户名或密码错误"));
        }
    }

    /**
     * 用户注册
     *
     * 请求体：{"username": "alice", "password": "secret123"}
     * 响应体：同登录，注册成功直接返回 Token（免二次登录）
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request,
                                       HttpServletRequest httpRequest) {
        try {
            AuthResponse response = authService.register(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取当前用户个人资料
     * GET /api/v1/auth/profile
     */
    @GetMapping("/profile")
    public ResponseEntity<?> getProfile(@AuthenticationPrincipal String userId) {
        try {
            return ResponseEntity.ok(authService.getProfile(userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 修改密码
     * PUT /api/v1/auth/profile/password
     * Body: {"oldPassword": "...", "newPassword": "..."}
     */
    @PutMapping("/profile/password")
    public ResponseEntity<?> changePassword(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> request) {

        String oldPwd = request.get("oldPassword");
        String newPwd = request.get("newPassword");

        if (oldPwd == null || newPwd == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "旧密码和新密码不能为空"));
        }

        try {
            authService.changePassword(userId, oldPwd, newPwd);
            return ResponseEntity.ok(Map.of("message", "密码修改成功，请重新登录"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    /** 提取客户端真实 IP（兼容 Nginx 反向代理） */
    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

