package com.example.aiagent.security.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 组织加入申请实体（对应 sys_org_join_request 表）
 *
 * <p>个人用户可主动申请加入某个 ENTERPRISE 组织，
 * 组织 OWNER/ADMIN 审批通过后申请人正式成为组织成员。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgJoinRequest {

    private Long id;

    /** 组织 ID */
    private String orgId;

    /** 申请人用户 ID */
    private String userId;

    /** 申请留言 */
    private String message;

    /**
     * 申请状态
     * <ul>
     *   <li>PENDING  - 待审批</li>
     *   <li>APPROVED - 已通过</li>
     *   <li>REJECTED - 已拒绝</li>
     * </ul>
     */
    @Builder.Default
    private String status = "PENDING";

    private Instant createdAt;
}
