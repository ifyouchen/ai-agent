package com.example.aiagent.security.config;

import com.example.aiagent.security.filter.JwtAuthFilter;
import com.example.aiagent.security.service.UserDetailsServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import jakarta.servlet.DispatcherType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Map;

/**
 * Spring Security 完整配置
 *
 * 认证链路：
 *   请求
 *     → JwtAuthFilter（解析 Token，注入 SecurityContext）
 *     → Spring Security 权限校验
 *     → Controller
 *
 * 说明：
 * - 使用 JWT 无状态认证，不使用 Session
 * - DaoAuthenticationProvider 负责登录时的密码校验
 * - BCrypt 强度默认 10，生产环境不建议降低
 * - @EnableMethodSecurity 支持在 Controller 上用 @PreAuthorize 做方法级权限控制
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final UserDetailsServiceImpl userDetailsService;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 关闭 CSRF（JWT 天然防 CSRF，API 接口无需）
            .csrf(csrf -> csrf.disable())

            // 配置跨域
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // 无状态 Session（不使用 HttpSession）
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // 接口权限配置
            .authorizeHttpRequests(auth -> auth
                // ASYNC 分发（SseEmitter 等异步请求）跳过认证，原始请求已校验过
                .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                // 公开接口：认证、健康检查、跨域预检
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/chat/share/**").permitAll()
                .requestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/auth/register",
                    "/api/v1/auth/email-code",
                    "/actuator/health",
                    "/actuator/prometheus",
                    // Spring Boot 内部错误转发端点必须放行，否则异常处理时会触发二次
                    // Security 拦截，导致 "response is already committed" 循环
                    "/error"
                ).permitAll()
                // 管理接口：需要 ADMIN 角色
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                // 其他所有接口需要认证
                .anyRequest().authenticated()
            )

            // 禁用默认登录表单（使用 JWT，不需要表单登录）
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())

            // 统一异常响应：401 / 403 返回 JSON，避免 response 被提前 commit
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(jwtAuthenticationEntryPoint())
                .accessDeniedHandler(jwtAccessDeniedHandler())
            )

            // 注册 JWT 过滤器：在 UsernamePasswordAuthenticationFilter 之前执行
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

            // 注册自定义 AuthenticationProvider（密码校验用）
            .authenticationProvider(authenticationProvider());

        return http.build();
    }

    /**
     * 401 Unauthorized：Token 缺失或无效时直接返回 JSON，不重定向登录页
     */
    @Bean
    public AuthenticationEntryPoint jwtAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            String body = new ObjectMapper().writeValueAsString(
                    Map.of("error", "未认证", "message", "请先登录或检查 Token 是否有效"));
            response.getWriter().write(body);
        };
    }

    /**
     * 403 Forbidden：已认证但权限不足时返回 JSON
     */
    @Bean
    public AccessDeniedHandler jwtAccessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            String body = new ObjectMapper().writeValueAsString(
                    Map.of("error", "权限不足", "message", "您没有权限访问该资源"));
            response.getWriter().write(body);
        };
    }

    /**
     * 密码编码器（BCrypt，不可逆哈希）
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * DaoAuthenticationProvider：登录时从 DB 加载用户 + BCrypt 密码校验
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    /**
     * AuthenticationManager Bean：AuthService 登录时注入使用
     */
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * CORS 配置
     *
     * 生产环境将 allowedOrigins 替换为实际前端域名，禁止使用 "*"
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));  // 生产环境改为具体域名
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
