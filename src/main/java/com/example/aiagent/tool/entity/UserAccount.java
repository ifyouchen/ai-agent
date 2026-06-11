package com.example.aiagent.tool.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 用户账户实体（对应 biz_user_account 表）
 * <p>
 * membershipLevel 枚举值：NORMAL | SILVER | GOLD | PLATINUM | DIAMOND
 */
@Entity
@Table(name = "biz_user_account")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID（唯一键） */
    @Column(name = "user_id", length = 64, nullable = false, unique = true)
    private String userId;

    /** 用户名 */
    @Column(name = "username", length = 128, nullable = false)
    private String username;

    /** 账户余额 */
    @Column(name = "balance", precision = 12, scale = 2, nullable = false)
    private BigDecimal balance;

    /** 会员等级：NORMAL / SILVER / GOLD / PLATINUM / DIAMOND */
    @Column(name = "membership_level", length = 32, nullable = false)
    private String membershipLevel;

    /** 积分 */
    @Column(name = "points", nullable = false)
    private Integer points;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
