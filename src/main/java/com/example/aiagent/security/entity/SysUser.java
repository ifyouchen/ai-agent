package com.example.aiagent.security.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 系统用户认证实体（对应 biz_user_account 表的认证字段）
 *
 * 与业务层 UserAccount 分离，只包含认证所需字段，
 * 避免将密码哈希暴露到业务对象中。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysUser {

    private Long id;

    /** 用户 ID（唯一键，JWT subject） */
    private String userId;

    /** 登录用户名 */
    private String username;

    /** BCrypt 哈希后的密码 */
    private String passwordHash;

    /**
     * 角色列表（逗号分隔存储在 DB，读取时转换为 List）
     * 例如："ROLE_USER" 或 "ROLE_USER,ROLE_ADMIN"
     */
    private String roles;

    /** 账号是否启用（0=禁用，1=启用） */
    private Integer enabled;

    private Instant createdAt;
    private Instant updatedAt;

    /** 将 roles 字符串拆分为 List（供 Spring Security 使用） */
    public List<String> getRoleList() {
        if (roles == null || roles.isBlank()) {
            return List.of("ROLE_USER");
        }
        return List.of(roles.split(","));
    }
}

