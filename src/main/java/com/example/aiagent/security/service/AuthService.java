package com.example.aiagent.security.service;

import com.example.aiagent.security.dto.AuthResponse;
import com.example.aiagent.security.dto.LoginRequest;
import com.example.aiagent.security.dto.RegisterRequest;
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

        String userId = UUID.randomUUID().toString().replace("-", "");

        SysUser user = SysUser.builder()
                .userId(userId)
                .username(request.username())
                .passwordHash(passwordEncoder.encode(request.password()))
                .roles("ROLE_USER")
                .enabled(1)
                .build();

        sysUserMapper.insert(user);
        log.info("用户注册成功 userId={} username={}", userId, request.username());

        List<String> roles = user.getRoleList();
        String token = jwtService.generateToken(userId, request.username(), roles);

        return AuthResponse.of(token, jwtService.getExpirationSeconds(),
                userId, request.username(), roles);
    }
}

