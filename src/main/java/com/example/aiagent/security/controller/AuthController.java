package com.example.aiagent.security.controller;

import com.example.aiagent.security.dto.AuthResponse;
import com.example.aiagent.security.dto.EmailCodeRequest;
import com.example.aiagent.security.dto.ForgotPasswordRequest;
import com.example.aiagent.security.dto.LoginRequest;
import com.example.aiagent.security.dto.RegisterRequest;
import com.example.aiagent.security.dto.ResetPasswordRequest;
import com.example.aiagent.security.mapper.SysUserMapper;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 认证接口
 *
     * POST /api/v1/auth/login     → 登录，返回 JWT
     * POST /api/v1/auth/register  → 注册，返回 JWT
     * POST /api/v1/auth/email-code → 发送注册邮箱验证码
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
    private final SysUserMapper sysUserMapper;

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
            log.warn("[AUTH] 登录失败 username={} ip={} reason={}", request.username(), clientIp, e.getMessage());

            auditLogService.log(AuditLogService.EventType.LOGIN_FAILED,
                    null, null, clientIp, false,
                    Map.of("username", request.username(), "reason", e.getMessage()));

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "用户名或密码错误"));
        }
    }

    /**
     * 发送邮箱验证码。
     *
     * <p>purpose 支持 register（默认）、reset_password、change_password。
     */
    @PostMapping("/email-code")
    public ResponseEntity<?> sendEmailCode(@Valid @RequestBody EmailCodeRequest request) {
        log.info("[AUTH] 发送验证码 email={} purpose={}", request.email(), request.purpose());
        try {
            String purpose = request.purpose() != null ? request.purpose() : "register";
            authService.sendEmailCode(request.email(), purpose);
            return ResponseEntity.ok(Map.of("message", "验证码已发送"));
        } catch (IllegalArgumentException e) {
            log.warn("[AUTH] 发送验证码失败 email={} reason={}", request.email(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            log.warn("[AUTH] 发送验证码失败 email={} reason={}", request.email(), e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 用户注册
     *
     * 请求体：{"username": "alice", "password": "secret123", "email": "alice@example.com", "emailCode": "123456"}
     * 响应体：同登录，注册成功直接返回 Token（免二次登录）
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request,
                                       HttpServletRequest httpRequest) {
        String clientIp = getClientIp(httpRequest);
        log.info("[AUTH] 用户注册 username={} ip={}", request.username(), clientIp);
        try {
            AuthResponse response = authService.register(request);

            auditLogService.log(AuditLogService.EventType.REGISTER_SUCCESS,
                    response.userId(), null, clientIp, true,
                    Map.of("username", request.username(), "email", request.email()));

            log.info("[AUTH] 注册成功 userId={} username={}", response.userId(), request.username());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            log.warn("[AUTH] 注册失败 username={} ip={} reason={}", request.username(), clientIp, e.getMessage());

            auditLogService.log(AuditLogService.EventType.REGISTER_FAILED,
                    null, null, clientIp, false,
                    Map.of("username", request.username(), "reason", e.getMessage()));

            HttpStatus status = isRegisterConflict(e.getMessage())
                    ? HttpStatus.CONFLICT
                    : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 忘记密码：发送重置验证码到邮箱。
     * POST /api/v1/auth/forgot-password
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        log.info("[AUTH] 忘记密码 email={}", request.email());
        try {
            authService.forgotPassword(request.email());
            // 统一返回文案，避免邮箱枚举
            return ResponseEntity.ok(Map.of("message", "如果该邮箱已注册，验证码将很快送达"));
        } catch (IllegalArgumentException e) {
            log.warn("[AUTH] 忘记密码失败 email={} reason={}", request.email(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            log.warn("[AUTH] 忘记密码失败 email={} reason={}", request.email(), e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 重置密码：使用邮箱验证码设置新密码。
     * POST /api/v1/auth/reset-password
     */
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        log.info("[AUTH] 重置密码 email={}", request.email());
        try {
            authService.resetPassword(request.email(), request.emailCode(), request.newPassword());
            log.info("[AUTH] 重置密码成功 email={}", request.email());
            return ResponseEntity.ok(Map.of("message", "密码重置成功，请使用新密码登录"));
        } catch (IllegalArgumentException e) {
            log.warn("[AUTH] 重置密码失败 email={} reason={}", request.email(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取当前用户个人资料
     * GET /api/v1/auth/profile
     */
    @GetMapping("/profile")
    public ResponseEntity<?> getProfile(@AuthenticationPrincipal String userId) {
        log.info("[AUTH] 查询个人资料 userId={}", userId);
        try {
            return ResponseEntity.ok(authService.getProfile(userId));
        } catch (IllegalArgumentException e) {
            log.warn("[AUTH] 查询个人资料失败 userId={} reason={}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 更新用户 Profile（仅昵称，邮箱注册后不可修改）
     * PUT /api/v1/auth/profile
     * Body: {"nickname": "..."}
     */
    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> request) {
        String nickname = request.get("nickname");
        log.info("[AUTH] 更新个人资料 userId={} nickname={}", userId, nickname);
        try {
            authService.updateProfile(userId, nickname);
            return ResponseEntity.ok(authService.getProfile(userId));
        } catch (IllegalArgumentException e) {
            log.warn("[AUTH] 更新个人资料失败 userId={} reason={}", userId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 修改密码
     * PUT /api/v1/auth/profile/password
     * Body: {"newPassword": "...", "emailCode": "123456"}
     */
    @PutMapping("/profile/password")
    public ResponseEntity<?> changePassword(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> request) {

        String newPwd = request.get("newPassword");
        String emailCode = request.get("emailCode");

        if (newPwd == null || emailCode == null) {
            log.warn("[AUTH] 修改密码失败 userId={} reason=参数缺失", userId);
            return ResponseEntity.badRequest().body(Map.of("error", "新密码和邮箱验证码不能为空"));
        }

        log.info("[AUTH] 修改密码 userId={}", userId);
        try {
            authService.changePassword(userId, newPwd, emailCode);
            log.info("[AUTH] 修改密码成功 userId={}", userId);
            return ResponseEntity.ok(Map.of("message", "密码修改成功，请重新登录"));
        } catch (IllegalArgumentException e) {
            log.warn("[AUTH] 修改密码失败 userId={} reason={}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 用户名模糊搜索（供知识库/组织成员添加时使用）
     *
     * GET /api/v1/auth/users/search?keyword=alice
     * 需要 JWT 认证，返回匹配的用户列表（最多 10 条），字段：userId、username
     */
    @GetMapping("/users/search")
    public ResponseEntity<List<Map<String, String>>> searchUsers(
            @RequestParam(defaultValue = "") String keyword) {
        if (keyword.isBlank()) {
            return ResponseEntity.ok(List.of());
        }
        log.info("[AUTH] 搜索用户 keyword={}", keyword);
        var users = sysUserMapper.searchByUsername(keyword.trim(), 10);
        var result = users.stream()
                .map(u -> Map.of("userId", u.getUserId(), "username", u.getUsername()))
                .toList();
        log.info("[AUTH] 搜索用户完成 keyword={} count={}", keyword, result.size());
        return ResponseEntity.ok(result);
    }

    /** 提取客户端真实 IP（兼容 Nginx 反向代理） */
    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean isRegisterConflict(String message) {
        return message != null && (message.contains("用户名已存在") || message.contains("邮箱已被注册"));
    }
}

