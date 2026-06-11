package com.example.aiagent.security.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 组织实体（对应 sys_organization 表）
 *
 * <p>组织是多租户的基本单位。每个用户注册时自动创建一个"个人组织"，
 * 企业可以创建"企业组织"并邀请员工加入。
 *
 * <p>tenantId 在知识库/文档/切片中存储的是 orgId（组织 ID），
 * 而非 userId。这实现了：
 * <ul>
 *   <li>个人用户：tenantId = 个人组织ID（等价于旧方案的 userId 隔离）</li>
 *   <li>企业用户：tenantId = 企业组织ID（同组织内用户共享知识库）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Organization {

    private Long id;

    /** 组织唯一标识（UUID，用作 tenantId） */
    private String orgId;

    /** 组织名称 */
    private String name;

    /**
     * 组织类型
     * <ul>
     *   <li>PERSONAL - 个人组织（注册时自动创建，不可邀请成员）</li>
     *   <li>ENTERPRISE - 企业组织（可邀请成员，共享知识库）</li>
     * </ul>
     */
    @Builder.Default
    private String orgType = "PERSONAL";

    /** 创建者 userId */
    private String ownerId;

    /** 组织描述 */
    private String description;

    /** 组织配置（JSON） */
    @Builder.Default
    private String settings = "{}";

    /** 状态：1=正常，0=已禁用 */
    @Builder.Default
    private Integer status = 1;

    private Instant createdAt;
    private Instant updatedAt;
}

