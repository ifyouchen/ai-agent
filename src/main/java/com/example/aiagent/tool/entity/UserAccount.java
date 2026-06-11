package com.example.aiagent.tool.entity;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 用户账户实体（对应 biz_user_account 表）
 * <p>
 * membershipLevel 枚举值：NORMAL | SILVER | GOLD | PLATINUM | DIAMOND
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAccount {

    private Long id;

    /** 用户 ID（唯一键） */
    private String userId;

    /** 用户名 */
    private String username;

    /** 账户余额 */
    private BigDecimal balance;

    /** 会员等级：NORMAL / SILVER / GOLD / PLATINUM / DIAMOND */
    private String membershipLevel;

    /** 积分 */
    private Integer points;

    private Instant createdAt;

    private Instant updatedAt;
}
