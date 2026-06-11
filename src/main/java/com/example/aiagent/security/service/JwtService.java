package com.example.aiagent.security.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * JWT 令牌服务
 *
 * 负责 Token 的生成、验证和解析，使用 HMAC-SHA256 对称签名。
 *
 * Payload 设计：
 *   sub       → userId（唯一标识，非数据库 id）
 *   username  → 用户名（展示用）
 *   roles     → 角色列表，如 ["ROLE_USER", "ROLE_ADMIN"]
 *   iat       → 签发时间
 *   exp       → 过期时间
 */
@Slf4j
@Service
public class JwtService {

    /** JWT 签名密钥，从配置读取，生产环境通过环境变量注入 */
    @Value("${security.jwt.secret:change-this-secret-key-in-production-min-32-chars}")
    private String secretKeyStr;

    /** Token 过期时间（秒），默认 24 小时 */
    @Value("${security.jwt.expiration-seconds:86400}")
    private long expirationSeconds;

    // ──────────────────────────────────────────────────
    // 生成 Token
    // ──────────────────────────────────────────────────

    /**
     * 生成访问令牌
     *
     * @param userId   用户唯一 ID
     * @param username 用户名
     * @param roles    角色列表（如 ["ROLE_USER"]）
     * @return JWT 字符串
     */
    public String generateToken(String userId, String username, List<String> roles) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(expirationSeconds);

        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(getSigningKey())
                .compact();
    }

    // ──────────────────────────────────────────────────
    // 解析 Token
    // ──────────────────────────────────────────────────

    /**
     * 从 Token 中提取 userId（subject）
     *
     * @throws JwtException Token 无效或已过期
     */
    public String extractUserId(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * 从 Token 中提取用户名
     */
    public String extractUsername(String token) {
        return parseClaims(token).get("username", String.class);
    }

    /**
     * 从 Token 中提取角色列表
     */
    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        Object roles = parseClaims(token).get("roles");
        if (roles instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    // ──────────────────────────────────────────────────
    // 验证 Token
    // ──────────────────────────────────────────────────

    /**
     * 验证 Token 是否有效（签名正确且未过期）
     *
     * @return true = 有效
     */
    public boolean isTokenValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("JWT 已过期: {}", e.getMessage());
            return false;
        } catch (JwtException e) {
            log.warn("JWT 验证失败: {}", e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            log.debug("JWT 参数非法（空/null）: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取 Token 过期时间（秒）
     */
    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    // ──────────────────────────────────────────────────
    // 内部方法
    // ──────────────────────────────────────────────────

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = secretKeyStr.getBytes(StandardCharsets.UTF_8);
        // Keys.hmacShaKeyFor 要求 >= 32 字节（256 bit），配置不足时会抛出 WeakKeyException
        return Keys.hmacShaKeyFor(keyBytes);
    }
}

