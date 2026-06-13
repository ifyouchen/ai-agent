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

    /**
     * 修改密码
     *
     * @param userId      当前用户 ID
     * @param oldPassword 旧密码（用于校验）
     * @param newPassword 新密码（明文，将被 BCrypt 加密）
     * @throws IllegalArgumentException 旧密码错误或新密码不符合规则
     */
    public void changePassword(String userId, String oldPassword, String newPassword) {
        SysUser user = sysUserMapper.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("旧密码不正确");
        }

        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("新密码长度不能少于 6 位");
        }

        String newHash = passwordEncoder.encode(newPassword);
        sysUserMapper.updatePassword(userId, newHash);
        log.info("用户修改密码成功 userId={}", userId);
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
     * 更新用户 Profile（昵称、邮箱）
     *
     * @throws IllegalArgumentException 用户不存在 / 邮箱格式无效
     */
    public void updateProfile(String userId, String nickname, String email) {
        sysUserMapper.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        if (email != null && !email.isBlank()
                && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        sysUserMapper.updateProfile(userId,
                nickname != null ? nickname.strip() : null,
                email    != null ? email.strip()    : null);
        log.info("用户更新 Profile userId={}", userId);
    }
}

