package com.example.aiagent.security.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 组织邀请实体（对应 sys_org_invitation 表）
 *
 * <p>记录通过邮箱或用户名发起的组织成员邀请，包含待接受/已接受/已拒绝/已撤销状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgInvitation {

    private Long id;

    /** 组织 ID */
    private String orgId;

    /** 被邀请人邮箱 */
    private String invitedEmail;

    /** 被邀请人用户 ID（仅当通过用户名邀请且用户已存在时填写） */
    private String invitedUserId;

    /** 邀请人用户 ID */
    private String inviterId;

    /** 邀请角色：OWNER / ADMIN / MEMBER */
    @Builder.Default
    private String role = "MEMBER";

    /** 邀请令牌（用于接受/拒绝链接） */
    private String token;

    /**
     * 邀请状态
     * <ul>
     *   <li>PENDING  - 待接受</li>
     *   <li>ACCEPTED - 已接受</li>
     *   <li>REJECTED - 已拒绝</li>
     *   <li>REVOKED  - 已撤销</li>
     *   <li>EXPIRED  - 已过期</li>
     * </ul>
     */
    @Builder.Default
    private String status = "PENDING";

    /** 邀请过期时间 */
    private Instant expiresAt;

    private Instant createdAt;
}
