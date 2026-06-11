package com.example.aiagent.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置
 *
 * 使用 JWT 无状态认证模式：
 * - 不使用 Session
 * - 每次请求通过 Token 验证
 * - 适合前后端分离和微服务场景
 *
 * 注意：当前为简化配置，生产环境需要：
 * 1. 接入公司 SSO / OAuth2
 * 2. 配置 CORS（跨域）
 * 3. 开启 HTTPS（通过 Nginx 或 Spring）
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 关闭 CSRF（JWT 天然防 CSRF，且 API 接口不需要）
            .csrf(csrf -> csrf.disable())

            // 无状态 Session（不用 HttpSession）
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // 接口权限配置
            .authorizeHttpRequests(auth -> auth
                // 公开接口：登录、健康检查
                .requestMatchers(
                    "/api/v1/auth/**",
                    "/actuator/health",
                    "/actuator/prometheus",
                    "/index.html",
                    "/"
                ).permitAll()
                // 管理接口：需要 ADMIN 角色
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                // 其他所有接口需要认证
                .anyRequest().authenticated()
            )

            // 禁用默认登录表单
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable());

        return http.build();
    }
}
