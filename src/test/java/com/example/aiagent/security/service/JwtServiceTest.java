package com.example.aiagent.security.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtService 单元测试
 *
 * 覆盖：Token 生成、解析、验证、过期、签名篡改
 */
@DisplayName("JwtService - JWT 令牌生成/验证")
class JwtServiceTest {

    private JwtService jwtService;

    // 测试用密钥（32字符，满足 HMAC-SHA256 最低要求）
    private static final String TEST_SECRET = "test-secret-key-at-least-32-chars!";

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKeyStr", TEST_SECRET);
        ReflectionTestUtils.setField(jwtService, "expirationSeconds", 3600L);
    }

    @Test
    @DisplayName("生成 Token 后能正确解析 userId")
    void shouldExtractUserIdFromToken() {
        String token = jwtService.generateToken("user-123", "alice", List.of("ROLE_USER"));
        assertThat(jwtService.extractUserId(token)).isEqualTo("user-123");
    }

    @Test
    @DisplayName("生成 Token 后能正确解析 username")
    void shouldExtractUsernameFromToken() {
        String token = jwtService.generateToken("user-123", "alice", List.of("ROLE_USER"));
        assertThat(jwtService.extractUsername(token)).isEqualTo("alice");
    }

    @Test
    @DisplayName("生成 Token 后能正确解析角色列表")
    void shouldExtractRolesFromToken() {
        List<String> roles = List.of("ROLE_USER", "ROLE_ADMIN");
        String token = jwtService.generateToken("user-123", "alice", roles);
        assertThat(jwtService.extractRoles(token)).containsExactlyInAnyOrderElementsOf(roles);
    }

    @Test
    @DisplayName("有效 Token 应通过验证")
    void shouldValidateValidToken() {
        String token = jwtService.generateToken("user-123", "alice", List.of("ROLE_USER"));
        assertThat(jwtService.isTokenValid(token)).isTrue();
    }

    @Test
    @DisplayName("Token 被篡改后验证应失败")
    void shouldRejectTamperedToken() {
        String token = jwtService.generateToken("user-123", "alice", List.of("ROLE_USER"));
        // 截断最后几位模拟篡改
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertThat(jwtService.isTokenValid(tampered)).isFalse();
    }

    @Test
    @DisplayName("过期 Token 验证应失败")
    void shouldRejectExpiredToken() {
        // 设置 -1 秒过期（立即过期）
        ReflectionTestUtils.setField(jwtService, "expirationSeconds", -1L);
        String token = jwtService.generateToken("user-123", "alice", List.of("ROLE_USER"));
        assertThat(jwtService.isTokenValid(token)).isFalse();
    }

    @Test
    @DisplayName("空字符串 Token 验证应失败")
    void shouldRejectEmptyToken() {
        assertThat(jwtService.isTokenValid("")).isFalse();
        assertThat(jwtService.isTokenValid("invalid.token.here")).isFalse();
    }

    @Test
    @DisplayName("getExpirationSeconds 应返回配置值")
    void shouldReturnConfiguredExpiration() {
        assertThat(jwtService.getExpirationSeconds()).isEqualTo(3600L);
    }
}

