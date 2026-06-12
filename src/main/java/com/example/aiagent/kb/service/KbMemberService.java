package com.example.aiagent.kb.service;

import com.example.aiagent.kb.entity.KbMember;
import com.example.aiagent.kb.mapper.KbMemberMapper;
import com.example.aiagent.kb.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 知识库成员授权服务
 *
 * <p>细粒度的知识库访问控制。与 tenantId（组织级隔离）互补：
 * <ul>
 *   <li>tenantId 确保不同组织的数据完全隔离（向量检索 + BM25 + 业务表）</li>
 *   <li>kb_member 确保同一组织内不同用户对知识库的差异化权限</li>
 * </ul>
 *
 * <p>权限矩阵：
 * <pre>
 *   角色      │ 检索/问答 │ 上传文档 │ 删除文档 │ 管理成员 │ 删除知识库
 *   ─────────┼──────────┼─────────┼─────────┼─────────┼──────────
 *   OWNER    │    ✓     │    ✓    │    ✓    │    ✓    │    ✓
 *   EDITOR   │    ✓     │    ✓    │    ✓    │    ✗    │    ✗
 *   VIEWER   │    ✓     │    ✗    │    ✗    │    ✗    │    ✗
 *   组织成员(无显式授权) │    ✓     │    ✗    │    ✗    │    ✗    │    ✗
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbMemberService {

    private final KbMemberMapper kbMemberMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    // ── 权限检查 ──────────────────────────────────────────────

    /**
     * 检查用户是否有权访问知识库（任何角色均可）
     *
     * <p>访问逻辑：
     * 1. 检查 kb_member 表中是否有显式授权 → 有则按 role 授权
     * 2. 无显式授权 → 检查用户是否属于知识库所在组织的成员
     *    - 组织成员 → 隐式 VIEWER 权限
     *    - 非组织成员 → 拒绝访问
     *
     * @return 用户角色，如果无权访问则返回 null
     */
    public String checkAccess(Long kbId, String userId, String orgId) {
        // 1. 检查显式授权
        var explicitRole = kbMemberMapper.findRoleByKbIdAndUserId(kbId, userId);
        if (explicitRole.isPresent()) {
            return explicitRole.get();
        }

        // 2. 无显式授权 → 如果知识库属于该组织，组织成员隐式获得 VIEWER
        //    注意：orgId 由 Controller 层传入（从用户的组织信息中获取）
        var kb = knowledgeBaseMapper.findById(kbId);
        if (kb.isPresent() && kb.get().getTenantId().equals(orgId)) {
            return "VIEWER";  // 组织成员默认只读
        }

        return null;  // 无权访问
    }

    /**
     * 检查用户是否拥有指定级别或更高的权限
     *
     * @param requiredRole 需要的最低角色（VIEWER / EDITOR / OWNER）
     * @return 实际角色，如果权限不足则返回 null
     */
    public String checkPermission(Long kbId, String userId, String orgId, String requiredRole) {
        String role = checkAccess(kbId, userId, orgId);
        if (role == null) {
            return null;
        }

        // 角色优先级：OWNER > EDITOR > VIEWER
        int required = rolePriority(requiredRole);
        int actual = rolePriority(role);

        return actual >= required ? role : null;
    }

    /**
     * 检查用户是否可编辑（上传/删除文档）
     */
    public boolean canEdit(Long kbId, String userId, String orgId) {
        return checkPermission(kbId, userId, orgId, "EDITOR") != null;
    }

    /**
     * 检查用户是否可管理成员
     */
    public boolean canManageMembers(Long kbId, String userId, String orgId) {
        return checkPermission(kbId, userId, orgId, "OWNER") != null;
    }

    // ── 成员管理 ──────────────────────────────────────────────

    /**
     * 添加知识库成员
     *
     * @param kbId      知识库 ID
     * @param userId    用户 ID
     * @param role      角色（OWNER / EDITOR / VIEWER）
     * @param grantedBy 授权人 userId
     */
    @Transactional
    public void addMember(Long kbId, String userId, String role, String grantedBy) {
        // 校验授权人权限
        if (!canManageMembers(kbId, grantedBy, null)) {
            // 对于创建知识库的场景，grantedBy 是创建者本人，此时 kb_member 还没有记录
            // 需要检查知识库是否属于该用户所在的组织
            var kb = knowledgeBaseMapper.findById(kbId);
            if (kb.isEmpty() || !grantedBy.equals(kb.get().getCreatedBy())) {
                throw new IllegalArgumentException("只有知识库拥有者才能管理成员");
            }
        }

        KbMember member = KbMember.builder()
                .kbId(kbId)
                .userId(userId)
                .role(role)
                .grantedBy(grantedBy)
                .build();
        kbMemberMapper.insert(member);
        log.info("知识库成员添加成功：kbId={}, userId={}, role={}, grantedBy={}",
                kbId, userId, role, grantedBy);
    }

    /**
     * 更新成员角色
     */
    @Transactional
    public void updateMemberRole(Long kbId, String userId, String newRole, String operatorId) {
        if (!canManageMembers(kbId, operatorId, null)) {
            throw new IllegalArgumentException("只有知识库拥有者才能修改成员角色");
        }
        kbMemberMapper.updateRole(kbId, userId, newRole);
        log.info("知识库成员角色更新：kbId={}, userId={}, newRole={}, operatorId={}",
                kbId, userId, newRole, operatorId);
    }

    /**
     * 移除知识库成员
     */
    @Transactional
    public void removeMember(Long kbId, String userId, String operatorId) {
        if (!canManageMembers(kbId, operatorId, null)) {
            throw new IllegalArgumentException("只有知识库拥有者才能移除成员");
        }
        kbMemberMapper.deleteByKbIdAndUserId(kbId, userId);
        log.info("知识库成员移除：kbId={}, userId={}, operatorId={}", kbId, userId, operatorId);
    }

    /**
     * 获取知识库的所有成员
     */
    public List<KbMember> getMembers(Long kbId) {
        return kbMemberMapper.findByKbId(kbId);
    }

    /**
     * 获取用户有权访问的所有知识库 ID
     */
    public List<Long> getAccessibleKbIds(String userId) {
        return kbMemberMapper.findKbIdsByUserId(userId);
    }

    /**
     * 删除知识库的所有成员记录（级联删除时调用）
     */
    @Transactional
    public void deleteAllMembers(Long kbId) {
        kbMemberMapper.deleteByKbId(kbId);
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    private int rolePriority(String role) {
        return switch (role) {
            case "OWNER"  -> 3;
            case "EDITOR" -> 2;
            case "VIEWER" -> 1;
            default -> 0;
        };
    }
}

