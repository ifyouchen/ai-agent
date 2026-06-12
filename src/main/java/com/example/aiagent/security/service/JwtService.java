package com.example.aiagent.security.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
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

    /** JWT 签名密钥，从配置读取，生产环境必须通过环境变量 JWT_SECRET 注入 */
    @Value("${security.jwt.secret}")
    private String secretKeyStr;

    /** Token 过期时间（秒），默认 24 小时 */
    @Value("${security.jwt.expiration-seconds:86400}")
    private long expirationSeconds;

    /**
     * 应用启动时校验 JWT Secret 强度
     *
     * <p>规则：
     * <ul>
     *   <li>Secret 不能为空</li>
     *   <li>长度不能少于 32 字符（HMAC-SHA256 最低要求）</li>
     *   <li>不能使用默认值（防止遗忘修改直接上生产）</li>
     * </ul>
     *
     * <p>校验失败时抛出 {@link IllegalStateException}，阻止应用正常启动，
     * 确保安全问题在部署时就被发现，而不是在运行时才暴露。
     */
    @PostConstruct
    public void validateJwtSecret() {
        if (secretKeyStr == null || secretKeyStr.isBlank()) {
            throw new IllegalStateException(
                    "[安全检查] JWT Secret 不能为空！" +
                    "请在配置文件或环境变量 JWT_SECRET 中设置一个随机字符串（至少 32 位）。" +
                    "生成命令：openssl rand -base64 32");
        }
        if (secretKeyStr.length() < 32) {
            throw new IllegalStateException(
                    "[安全检查] JWT Secret 长度不足！当前长度：" + secretKeyStr.length() +
                    "，要求至少 32 字符。请使用更长的随机字符串。");
        }
        // 常见的默认/弱密钥检测
        String lower = secretKeyStr.toLowerCase();
        if (lower.contains("change-this") || lower.contains("your-secret") ||
            lower.contains("please-change") || lower.contains("default-secret") ||
            lower.equals("secret") || lower.equals("password")) {
            throw new IllegalStateException(
                    "[安全检查] 检测到 JWT Secret 使用了默认值或弱密钥，" +
                    "这在生产环境中极其危险！请立即修改。" +
                    "生成强随机密钥：openssl rand -base64 32");
        }
        log.info("[安全检查] JWT Secret 验证通过，长度={}，过期时间={}s",
                secretKeyStr.length(), expirationSeconds);
    }

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

