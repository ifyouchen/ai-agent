package com.example.aiagent.security.filter;

import com.example.aiagent.security.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器
 *
 * 每次请求执行一次（OncePerRequestFilter），负责：
 * 1. 从 Authorization Header 中提取 Bearer Token
 *    （SSE 场景 EventSource 不支持自定义 Header，额外支持从 ?token= URL 参数读取）
 * 2. 调用 JwtService 验证签名和过期
 * 3. 解析 userId、roles，构造 Authentication 注入 SecurityContext
 * 4. 将 userId、sessionId 写入 MDC，供日志和 AOP 切面使用
 *
 * 验证失败时不抛异常，直接放行（后续 Spring Security 会因 SecurityContext 为空而返回 401）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractToken(request);

        if (StringUtils.hasText(token) && jwtService.isTokenValid(token)) {
            try {
                String userId   = jwtService.extractUserId(token);
                String username = jwtService.extractUsername(token);
                List<String> roles = jwtService.extractRoles(token);

                // 构造 Spring Security 认证对象
                List<SimpleGrantedAuthority> authorities = roles.stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList();

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null, authorities);
                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));

                // 注入 SecurityContext
                SecurityContextHolder.getContext().setAuthentication(authentication);

                // 写入 MDC（供日志和 LlmObservabilityAspect 读取）
                MDC.put("userId",   userId);
                MDC.put("username", username);

                log.debug("JWT 认证成功 userId={} roles={}", userId, roles);

            } catch (Exception e) {
                log.warn("JWT 解析失败，跳过认证: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 请求结束后清理 MDC，防止线程池复用时信息污染
            MDC.remove("userId");
            MDC.remove("username");
        }
    }

    /**
     * 提取 Token 字符串
     *
     * 优先从 Authorization: Bearer <token> Header 读取；
     * 若 Header 不存在（如 SSE EventSource 场景），则从 ?token= URL 参数读取。
     */
    private String extractToken(HttpServletRequest request) {
        // 1. 优先读 Authorization Header
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        // 2. 降级读 URL 参数（专为 SSE/EventSource 场景设计，EventSource 无法设置自定义 Header）
        String tokenParam = request.getParameter("token");
        if (StringUtils.hasText(tokenParam)) {
            return tokenParam;
        }
        return null;
    }
}

