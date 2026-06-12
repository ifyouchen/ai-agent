package com.example.aiagent.security.service;

import com.example.aiagent.security.entity.OrgMember;
import com.example.aiagent.security.entity.Organization;
import com.example.aiagent.security.mapper.OrgMemberMapper;
import com.example.aiagent.security.mapper.OrganizationMapper;
import com.example.aiagent.security.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 组织管理服务
 *
 * <p>核心职责：
 * <ul>
 *   <li>创建组织（个人/企业）</li>
 *   <li>管理组织成员（邀请/移除/变更角色）</li>
 *   <li>查询用户所属组织（用于确定 tenantId）</li>
 * </ul>
 *
 * <p>关键设计：
 * <ul>
 *   <li>每个用户注册时自动创建一个 PERSONAL 类型的组织，作为默认 tenantId</li>
 *   <li>企业可以创建 ENTERPRISE 类型的组织，邀请员工加入</li>
 *   <li>tenantId = orgId，而非 userId，实现了"个人隔离"和"企业共享"的统一模型</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationMapper organizationMapper;
    private final OrgMemberMapper orgMemberMapper;
    private final SysUserMapper sysUserMapper;

    /**
     * 创建个人组织（注册时自动调用）
     *
     * <p>每个用户注册时自动创建，orgId = "org_" + userId，保证唯一性。
     * 个人组织不可邀请其他成员，等价于旧方案的 userId 隔离。
     *
     * @param userId 用户 ID
     * @return 创建的组织
     */
    @Transactional
    public Organization createPersonalOrganization(String userId) {
        String orgId = "org_" + userId;

        // 幂等检查
        if (organizationMapper.existsByOrgId(orgId)) {
            log.info("个人组织已存在，跳过创建：orgId={}", orgId);
            return organizationMapper.findByOrgId(orgId).orElseThrow();
        }

        Organization org = Organization.builder()
                .orgId(orgId)
                .name("个人组织")
                .orgType("PERSONAL")
                .ownerId(userId)
                .description("自动创建的个人组织")
                .build();
        organizationMapper.insert(org);

        // 创建者自动成为 OWNER
        addMember(orgId, userId, "OWNER");

        log.info("个人组织创建成功：orgId={}, ownerId={}", orgId, userId);
        return org;
    }

    /**
     * 创建企业组织
     *
     * <p>企业管理员调用，创建后可邀请员工加入。
     * 企业组织下的知识库对所有成员可见（按 kb_member 细粒度授权）。
     *
     * @param name        企业名称
     * @param ownerId     创建者 userId
     * @param description 企业描述
     * @return 创建的企业组织
     */
    @Transactional
    public Organization createEnterpriseOrganization(String name, String ownerId, String description) {
        String orgId = "ent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        Organization org = Organization.builder()
                .orgId(orgId)
                .name(name)
                .orgType("ENTERPRISE")
                .ownerId(ownerId)
                .description(description)
                .build();
        organizationMapper.insert(org);

        // 创建者自动成为 OWNER
        addMember(orgId, ownerId, "OWNER");

        log.info("企业组织创建成功：orgId={}, name={}, ownerId={}", orgId, name, ownerId);
        return org;
    }

    /**
     * 邀请用户加入组织
     *
     * @param orgId    组织 ID
     * @param userId   被邀请的用户 ID
     * @param role     成员角色（OWNER / ADMIN / MEMBER）
     * @param inviterId 邀请人 userId
     * @throws IllegalArgumentException 如果组织不存在、操作者无权限、或目标用户已在组织中
     */
    @Transactional
    public void inviteMember(String orgId, String userId, String role, String inviterId) {
        // 校验组织存在
        Organization org = organizationMapper.findByOrgId(orgId)
                .orElseThrow(() -> new IllegalArgumentException("组织不存在：orgId=" + orgId));

        // 校验组织类型（个人组织不可邀请成员）
        if ("PERSONAL".equals(org.getOrgType())) {
            throw new IllegalArgumentException("个人组织不支持邀请成员");
        }

        // 校验邀请人权限（OWNER 或 ADMIN 才能邀请）
        OrgMember inviter = orgMemberMapper.findByOrgIdAndUserId(orgId, inviterId)
                .orElseThrow(() -> new IllegalArgumentException("您不是该组织的成员"));
        if (!"OWNER".equals(inviter.getRole()) && !"ADMIN".equals(inviter.getRole())) {
            throw new IllegalArgumentException("只有组织拥有者或管理员才能邀请成员");
        }

        addMember(orgId, userId, role);
        log.info("成员邀请成功：orgId={}, userId={}, role={}, inviterId={}", orgId, userId, role, inviterId);
    }

    /**
     * 移除组织成员
     *
     * @throws IllegalArgumentException 如果组织不存在、操作者无权限、或试图移除 OWNER
     */
    @Transactional
    public void removeMember(String orgId, String userId, String operatorId) {
        // 校验操作者权限
        OrgMember operator = orgMemberMapper.findByOrgIdAndUserId(orgId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("您不是该组织的成员"));
        if (!"OWNER".equals(operator.getRole()) && !"ADMIN".equals(operator.getRole())) {
            throw new IllegalArgumentException("只有组织拥有者或管理员才能移除成员");
        }

        // 不能移除 OWNER
        OrgMember target = orgMemberMapper.findByOrgIdAndUserId(orgId, userId)
                .orElseThrow(() -> new IllegalArgumentException("该用户不是组织成员"));
        if ("OWNER".equals(target.getRole())) {
            throw new IllegalArgumentException("不能移除组织拥有者，请先转让拥有者身份");
        }

        orgMemberMapper.deleteByOrgIdAndUserId(orgId, userId);
        log.info("成员移除成功：orgId={}, userId={}, operatorId={}", orgId, userId, operatorId);
    }

    /**
     * 获取用户的默认组织 ID（用作 tenantId）
     *
     * <p>优先使用用户注册时创建的个人组织。
     * 如果用户加入了企业组织并设置了默认，则使用企业组织。
     *
     * @param userId 用户 ID
     * @return 默认组织 ID
     */
    public String getDefaultOrgId(String userId) {
        // 简化实现：返回个人组织 ID
        // 后续可扩展为用户可选择默认组织
        String personalOrgId = "org_" + userId;
        if (organizationMapper.existsByOrgId(personalOrgId)) {
            return personalOrgId;
        }

        // 如果个人组织不存在（理论上不应该），返回用户加入的第一个组织
        List<String> orgIds = orgMemberMapper.findOrgIdsByUserId(userId);
        if (!orgIds.isEmpty()) {
            return orgIds.get(0);
        }

        // 极端情况：自动创建个人组织
        Organization org = createPersonalOrganization(userId);
        return org.getOrgId();
    }

    /**
     * 解析并校验用户请求的组织 ID。
     *
     * <p>规则：
     * <ul>
     *   <li>requestedOrgId 为 null/空 → 返回默认个人组织（向后兼容）</li>
     *   <li>requestedOrgId 不为空 → 校验用户是否为该组织成员，通过则返回，否则抛异常</li>
     * </ul>
     *
     * @param userId          当前登录用户
     * @param requestedOrgId  前端传入的目标组织 ID（可为 null）
     * @return 最终使用的 orgId（同时作为 tenantId）
     * @throws IllegalArgumentException 组织不存在或用户不是成员
     */
    public String resolveOrgId(String userId, String requestedOrgId) {
        if (requestedOrgId == null || requestedOrgId.isBlank()) {
            return getDefaultOrgId(userId);
        }

        // 校验组织是否存在
        if (!organizationMapper.existsByOrgId(requestedOrgId)) {
            throw new IllegalArgumentException("组织不存在：" + requestedOrgId);
        }

        // 校验用户是否为该组织成员
        boolean isMember = orgMemberMapper.findByOrgIdAndUserId(requestedOrgId, userId).isPresent();
        if (!isMember) {
            throw new IllegalArgumentException("您不是该组织的成员：" + requestedOrgId);
        }

        return requestedOrgId;
    }

    /**
     * 获取用户可用的所有组织
     */
    public List<OrgMember> getUserOrganizations(String userId) {
        return orgMemberMapper.findByUserId(userId);
    }

    /**
     * 获取用户可用的所有组织（含组织名称和类型，供前端展示）
     */
    public List<Map<String, Object>> getUserOrganizationsWithDetail(String userId) {
        List<OrgMember> memberships = orgMemberMapper.findByUserId(userId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (OrgMember member : memberships) {
            Map<String, Object> item = new HashMap<>();
            item.put("orgId", member.getOrgId());
            item.put("role", member.getRole());
            // 查询组织详情获取名称和类型
            organizationMapper.findByOrgId(member.getOrgId()).ifPresent(org -> {
                item.put("name", org.getName());
                item.put("orgType", org.getOrgType());
            });
            result.add(item);
        }
        return result;
    }

    /**
     * 获取组织的所有成员
     */
    public List<OrgMember> getOrgMembers(String orgId) {
        return orgMemberMapper.findByOrgId(orgId);
    }

    /**
     * 检查用户是否属于某个组织
     */
    public boolean isMemberOf(String orgId, String userId) {
        return orgMemberMapper.findByOrgIdAndUserId(orgId, userId).isPresent();
    }

    /**
     * 按 orgId 查询组织（返回 null 而非抛异常，供 Controller 判断）
     */
    public Organization getOrganizationById(String orgId) {
        return organizationMapper.findByOrgId(orgId).orElse(null);
    }

    /**
     * 获取组织的所有成员（含 username）
     *
     * <p>通过 SysUserMapper 逐条查询 username（N+1），组织成员数通常 &lt; 50，性能可接受。
     */
    public List<Map<String, Object>> getOrgMembersWithUsername(String orgId) {
        List<OrgMember> members = orgMemberMapper.findByOrgId(orgId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (OrgMember member : members) {
            Map<String, Object> item = new HashMap<>();
            item.put("userId", member.getUserId());
            item.put("role", member.getRole());
            item.put("joinedAt", member.getJoinedAt());
            // 查询用户名
            sysUserMapper.findByUserId(member.getUserId())
                    .ifPresent(u -> item.put("username", u.getUsername()));
            result.add(item);
        }
        return result;
    }

    /**
     * 更新企业组织的名称和描述
     *
     * @throws IllegalArgumentException 若组织不存在、类型为 PERSONAL、或操作者无 OWNER 权限
     */
    @Transactional
    public Organization updateOrganization(String orgId, String name, String description,
                                            String operatorId) {
        Organization org = organizationMapper.findByOrgId(orgId)
                .orElseThrow(() -> new IllegalArgumentException("组织不存在：" + orgId));

        if ("PERSONAL".equals(org.getOrgType())) {
            throw new IllegalArgumentException("个人空间不支持编辑");
        }

        OrgMember operator = orgMemberMapper.findByOrgIdAndUserId(orgId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("您不是该组织的成员"));
        if (!"OWNER".equals(operator.getRole())) {
            throw new IllegalArgumentException("只有组织拥有者才能编辑组织信息");
        }

        org.setName(name);
        org.setDescription(description != null ? description : "");
        organizationMapper.update(org);
        log.info("组织信息更新：orgId={} name={} operatorId={}", orgId, name, operatorId);
        return org;
    }

    /**
     * 修改组织成员角色
     *
     * @throws IllegalArgumentException 若无权限、目标成员不存在或试图修改 OWNER 角色
     */
    @Transactional
    public void updateMemberRole(String orgId, String targetUserId, String newRole,
                                  String operatorId) {
        OrgMember operator = orgMemberMapper.findByOrgIdAndUserId(orgId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("您不是该组织的成员"));
        if (!"OWNER".equals(operator.getRole()) && !"ADMIN".equals(operator.getRole())) {
            throw new IllegalArgumentException("只有组织拥有者或管理员才能修改成员角色");
        }

        OrgMember target = orgMemberMapper.findByOrgIdAndUserId(orgId, targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("该用户不是组织成员"));
        if ("OWNER".equals(target.getRole())) {
            throw new IllegalArgumentException("不能修改组织拥有者的角色，请先转让拥有者身份");
        }
        // ADMIN 不能将他人提升为 OWNER
        if ("OWNER".equals(newRole) && !"OWNER".equals(operator.getRole())) {
            throw new IllegalArgumentException("只有拥有者才能指定新的拥有者");
        }

        orgMemberMapper.updateRole(orgId, targetUserId, newRole);
        log.info("成员角色已修改：orgId={} userId={} newRole={} operatorId={}",
                orgId, targetUserId, newRole, operatorId);
    }

    /**
     * 删除企业组织
     *
     * <p>规则：仅 OWNER 可删，PERSONAL 组织不可删。
     * 删除后移除所有成员记录，知识库数据本身不自动删除（由调用方决定）。
     *
     * @throws IllegalArgumentException 若组织不存在、是 PERSONAL 组织、或操作者非 OWNER
     */
    @Transactional
    public void deleteOrganization(String orgId, String operatorId) {
        Organization org = organizationMapper.findByOrgId(orgId)
                .orElseThrow(() -> new IllegalArgumentException("组织不存在：" + orgId));

        if ("PERSONAL".equals(org.getOrgType())) {
            throw new IllegalArgumentException("个人空间不能删除");
        }

        OrgMember operator = orgMemberMapper.findByOrgIdAndUserId(orgId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("您不是该组织的成员"));
        if (!"OWNER".equals(operator.getRole())) {
            throw new IllegalArgumentException("只有组织拥有者才能删除组织");
        }

        // 删除所有成员记录
        List<OrgMember> members = orgMemberMapper.findByOrgId(orgId);
        for (OrgMember m : members) {
            orgMemberMapper.deleteByOrgIdAndUserId(orgId, m.getUserId());
        }

        // 删除组织
        organizationMapper.deleteByOrgId(orgId);
        log.info("企业组织已删除：orgId={} operatorId={}", orgId, operatorId);
    }

    /**
     * 退出组织（非 OWNER 成员主动离开）
     *
     * @throws IllegalArgumentException 若用户非成员、或用户是 OWNER（须先转让）
     */
    @Transactional
    public void leaveOrganization(String orgId, String userId) {
        OrgMember member = orgMemberMapper.findByOrgIdAndUserId(orgId, userId)
                .orElseThrow(() -> new IllegalArgumentException("您不是该组织的成员"));

        if ("OWNER".equals(member.getRole())) {
            throw new IllegalArgumentException("组织拥有者不能直接退出，请先将拥有者身份转让给其他成员");
        }

        Organization org = organizationMapper.findByOrgId(orgId)
                .orElseThrow(() -> new IllegalArgumentException("组织不存在：" + orgId));
        if ("PERSONAL".equals(org.getOrgType())) {
            throw new IllegalArgumentException("个人空间不支持退出");
        }

        orgMemberMapper.deleteByOrgIdAndUserId(orgId, userId);
        log.info("成员退出组织：orgId={} userId={}", orgId, userId);
    }

    // ── 私有辅助 ─────────────────────────────────────────────

    private void addMember(String orgId, String userId, String role) {
        OrgMember member = OrgMember.builder()
                .orgId(orgId)
                .userId(userId)
                .role(role)
                .build();
        orgMemberMapper.insert(member);
    }
}

