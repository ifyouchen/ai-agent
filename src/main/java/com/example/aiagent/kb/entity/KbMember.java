package com.example.aiagent.kb.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 知识库成员授权实体（对应 kb_member 表）
 *
 * <p>细粒度的知识库访问控制。与 tenantId（组织级隔离）互补，
 * 实现同一组织内不同用户对知识库的差异化权限。
 *
 * <p>权限级别：
 * <ul>
 *   <li>OWNER - 拥有者（删除知识库、管理成员、上传/删除文档、检索/问答）</li>
 *   <li>EDITOR - 编辑者（上传/删除文档、检索/问答）</li>
 *   <li>VIEWER - 只读（检索/问答，不能上传或删除）</li>
 * </ul>
 *
 * <p>访问逻辑：
 * <pre>
 *   用户访问知识库时：
 *     1. 检查 kb_member 表中是否有该用户的记录
 *     2. 有记录 → 按 role 授权
 *     3. 无记录 → 检查用户是否属于知识库所在组织的成员
 *        - 组织成员 → 默认 VIEWER 权限
 *        - 非组织成员 → 拒绝访问
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KbMember {

    private Long id;

    /** 知识库 ID */
    private Long kbId;

    /** 用户 ID */
    private String userId;

    /** 用户名（来自 biz_user_account 联表查询，非数据库字段） */
    private String username;

    /**
     * 知识库角色
     * <ul>
     *   <li>OWNER - 拥有者（全部权限）</li>
     *   <li>EDITOR - 编辑者（上传/删除文档 + 检索）</li>
     *   <li>VIEWER - 只读（仅检索/问答）</li>
     * </ul>
     */
    @Builder.Default
    private String role = "VIEWER";

    /** 授权人 userId */
    private String grantedBy;

    private Instant grantedAt;
}

