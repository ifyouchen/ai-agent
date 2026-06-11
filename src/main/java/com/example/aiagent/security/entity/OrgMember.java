package com.example.aiagent.security.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 组织成员实体（对应 sys_org_member 表）
 *
 * <p>记录用户与组织的关联关系及角色：
 * <ul>
 *   <li>OWNER - 组织拥有者（可管理成员、删除组织）</li>
 *   <li>ADMIN - 管理员（可邀请/移除成员）</li>
 *   <li>MEMBER - 普通成员（可访问组织下的知识库）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgMember {

    private Long id;

    /** 组织 ID */
    private String orgId;

    /** 用户 ID */
    private String userId;

    /** 成员角色：OWNER / ADMIN / MEMBER */
    @Builder.Default
    private String role = "MEMBER";

    private Instant joinedAt;
}

