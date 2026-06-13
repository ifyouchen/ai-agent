package com.example.aiagent.security.service;

import com.example.aiagent.security.dto.AuthResponse;
import com.example.aiagent.security.dto.LoginRequest;
import com.example.aiagent.security.dto.RegisterRequest;
import com.example.aiagent.security.entity.Organization;
import com.example.aiagent.security.entity.SysUser;
import com.example.aiagent.security.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 认证业务服务
 *
 * 封装登录和注册逻辑，与 Controller 解耦。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final UserDetailsServiceImpl userDetailsService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final OrganizationService organizationService;
    private final EmailVerificationService emailVerificationService;

    /**
     * 用户登录
     *
     * @return JWT 认证响应
     * @throws AuthenticationException 用户名/密码错误时由 Spring Security 抛出
     */
    public AuthResponse login(LoginRequest request) {
        // 委托给 Spring Security 做认证（内部调用 UserDetailsServiceImpl.loadUserByUsername）
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.username(), request.password()));

        // 认证通过，加载完整用户信息（含 userId、roles）
        SysUser user = userDetailsService.loadSysUserByUsername(request.username());

        List<String> roles = user.getRoleList();
        String token = jwtService.generateToken(user.getUserId(), user.getUsername(), roles);

        log.info("用户登录成功 userId={} username={}", user.getUserId(), user.getUsername());

        return AuthResponse.of(token, jwtService.getExpirationSeconds(),
                user.getUserId(), user.getUsername(), roles);
    }

    /**
     * 用户注册
     *
     * @throws IllegalArgumentException 用户名已存在
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (sysUserMapper.existsByUsername(request.username())) {
            throw new IllegalArgumentException("用户名已存在：" + request.username());
        }
        String normalizedEmail = request.email().strip().toLowerCase(Locale.ROOT);
        if (sysUserMapper.existsByEmail(normalizedEmail)) {
            throw new IllegalArgumentException("邮箱已被注册：" + normalizedEmail);
        }
        emailVerificationService.verifyRegisterCode(normalizedEmail, request.emailCode());

        String userId = UUID.randomUUID().toString().replace("-", "");

        // ★ 关键变更：注册时自动创建个人组织
        Organization personalOrg = organizationService.createPersonalOrganization(userId);

        SysUser user = SysUser.builder()
                .userId(userId)
                .username(request.username())
                .passwordHash(passwordEncoder.encode(request.password()))
                .roles("ROLE_USER")
                .enabled(1)
                .defaultOrgId(personalOrg.getOrgId())
                .email(normalizedEmail)
                .build();

        sysUserMapper.insert(user);
        log.info("用户注册成功 userId={} username={} defaultOrgId={}",
                userId, request.username(), personalOrg.getOrgId());

        List<String> roles = user.getRoleList();
        String token = jwtService.generateToken(userId, request.username(), roles);

        return AuthResponse.of(token, jwtService.getExpirationSeconds(),
                userId, request.username(), roles);
    }

    public void sendRegisterEmailCode(String email) {
        emailVerificationService.sendRegisterCode(email);
    }

    public void sendEmailCode(String email, String purpose) {
        String p = purpose == null ? "register" : purpose.toLowerCase(Locale.ROOT);
        if ("register".equals(p)) {
            String normalizedEmail = email != null ? email.strip().toLowerCase(Locale.ROOT) : "";
            if (sysUserMapper.existsByEmail(normalizedEmail)) {
                throw new IllegalArgumentException("邮箱已被注册：" + normalizedEmail);
            }
            emailVerificationService.sendRegisterCode(email);
        } else if ("reset_password".equals(p)) {
            emailVerificationService.sendResetPasswordCode(email);
        } else if ("change_password".equals(p)) {
            emailVerificationService.sendChangePasswordCode(email);
        } else {
            emailVerificationService.sendRegisterCode(email);
        }
    }

    /**
     * 忘记密码：发送重置验证码。
     *
     * <p>为防止邮箱枚举，无论邮箱是否存在都返回相同提示，
     * 只有数据库中存在对应用户时才会真正发送邮件。
     */
    public void forgotPassword(String email) {
        String normalizedEmail = email != null ? email.strip().toLowerCase(Locale.ROOT) : "";
        if (!normalizedEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        sysUserMapper.findByEmail(normalizedEmail)
                .ifPresentOrElse(
                        user -> emailVerificationService.sendResetPasswordCode(normalizedEmail),
                        () -> log.warn("忘记密码：邮箱不存在，不发送验证码 email={}", maskEmail(normalizedEmail))
                );
    }

    /**
     * 重置密码：使用邮箱验证码设置新密码。
     */
    @Transactional
    public void resetPassword(String email, String emailCode, String newPassword) {
        String normalizedEmail = email != null ? email.strip().toLowerCase(Locale.ROOT) : "";
        emailVerificationService.verifyResetPasswordCode(normalizedEmail, emailCode);

        SysUser user = sysUserMapper.findByEmail(normalizedEmail)
                .orElseThrow(() -> new IllegalArgumentException("该邮箱未注册账号"));

        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("新密码长度不能少于 6 位");
        }

        sysUserMapper.updatePassword(user.getUserId(), passwordEncoder.encode(newPassword));
        log.info("用户重置密码成功 userId={} email={}", user.getUserId(), maskEmail(normalizedEmail));
    }

    /**
     * 修改密码
     *
     * @param userId      当前用户 ID
     * @param newPassword 新密码（明文，将被 BCrypt 加密）
     * @param emailCode   邮箱验证码
     * @throws IllegalArgumentException 验证码错误或新密码不符合规则
     */
    @Transactional
    public void changePassword(String userId, String newPassword, String emailCode) {
        SysUser user = sysUserMapper.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new IllegalArgumentException("当前账号未绑定邮箱，无法通过邮箱验证修改密码");
        }

        emailVerificationService.verifyChangePasswordCode(user.getEmail(), emailCode);

        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("新密码长度不能少于 6 位");
        }

        String newHash = passwordEncoder.encode(newPassword);
        sysUserMapper.updatePassword(userId, newHash);
        log.info("用户修改密码成功 userId={}", userId);
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + email.substring(Math.max(at, 0));
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    /**
     * 获取当前用户个人资料（含 nickname/email）
     */
    public java.util.Map<String, Object> getProfile(String userId) {
        SysUser user = sysUserMapper.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("userId",    user.getUserId());
        map.put("username",  user.getUsername());
        map.put("roles",     user.getRoleList());
        map.put("enabled",   user.getEnabled());
        map.put("nickname",  user.getNickname() != null ? user.getNickname() : "");
        map.put("email",     user.getEmail()    != null ? user.getEmail()    : "");
        return map;
    }

    /**
     * 更新用户 Profile（仅昵称可修改，邮箱注册后不可变更）
     *
     * @throws IllegalArgumentException 用户不存在
     */
    public void updateProfile(String userId, String nickname) {
        sysUserMapper.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        sysUserMapper.updateProfile(userId,
                nickname != null ? nickname.strip() : null,
                null);
        log.info("用户更新 Profile userId={}", userId);
    }
}

