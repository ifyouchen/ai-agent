package com.example.aiagent.security.dto;

import java.util.List;

/**
 * 认证响应 DTO（登录/注册成功后返回）
 */
public record AuthResponse(
        String token,
        String tokenType,
        long expiresIn,
        String userId,
        String username,
        List<String> roles
) {
    public static AuthResponse of(String token, long expiresIn,
                                   String userId, String username, List<String> roles) {
        return new AuthResponse(token, "Bearer", expiresIn, userId, username, roles);
    }
}

