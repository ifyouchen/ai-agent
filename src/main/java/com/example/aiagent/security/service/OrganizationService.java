package com.example.aiagent.security.service;

import com.example.aiagent.security.entity.OrgInvitation;
import com.example.aiagent.security.entity.OrgJoinRequest;
import com.example.aiagent.security.entity.OrgMember;
import com.example.aiagent.security.entity.Organization;
import com.example.aiagent.security.entity.SysUser;
import com.example.aiagent.security.mail.MailMessage;
import com.example.aiagent.security.mail.MailSender;
import com.example.aiagent.security.mapper.OrgInvitationMapper;
import com.example.aiagent.security.mapper.OrgJoinRequestMapper;
import com.example.aiagent.security.mapper.OrgMemberMapper;
import com.example.aiagent.security.mapper.OrganizationMapper;
import com.example.aiagent.security.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    private final OrgInvitationMapper orgInvitationMapper;
    private final OrgJoinRequestMapper orgJoinRequestMapper;
    private final SysUserMapper sysUserMapper;
    private final MailSender mailSender;

    @Value("${app.frontend-url:}")
    private String frontendUrl;

    @Value("${app.org.invitation.ttl-hours:72}")
    private long invitationTtlHours;

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

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
     * 邀请用户加入组织（通过邮箱或用户名）。
     *
     * <p>被邀请人必须已注册。创建一条 PENDING 状态的邀请记录后异步发送邀请邮件，
     * 被邀请人点击邮件链接接受后才真正加入组织。
     *
     * @param orgId         组织 ID
     * @param emailOrUsername 被邀请的邮箱或用户名
     * @param role          成员角色（OWNER / ADMIN / MEMBER）
     * @param inviterId     邀请人 userId
     * @return 邀请 token
     * @throws IllegalArgumentException 如果组织不存在、操作者无权限、用户未注册或目标已是成员
     */
    @Transactional
    public String inviteByEmailOrUsername(String orgId, String emailOrUsername, String role, String inviterId) {
        Organization org = validateInvitationPermission(orgId, inviterId);
        String input = emailOrUsername != null ? emailOrUsername.strip() : "";
        if (input.isBlank()) {
            throw new IllegalArgumentException("请输入邮箱或用户名");
        }

        boolean isEmail = EMAIL_PATTERN.matcher(input).matches();
        SysUser targetUser = isEmail
                ? sysUserMapper.findByEmail(input.toLowerCase(Locale.ROOT))
                        .orElseThrow(() -> new IllegalArgumentException("该邮箱未注册，无法邀请"))
                : sysUserMapper.findByUsername(input)
                        .orElseThrow(() -> new IllegalArgumentException("该用户名不存在，无法邀请"));

        String email = targetUser.getEmail();
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("被邀请人未绑定邮箱，无法发送邀请邮件");
        }

        if (orgMemberMapper.findByOrgIdAndUserId(orgId, targetUser.getUserId()).isPresent()) {
            throw new IllegalArgumentException("该用户已经是组织成员");
        }

        // 撤销同一邮箱的待处理旧邀请
        orgInvitationMapper.findLatestByOrgAndEmail(orgId, email)
                .filter(inv -> "PENDING".equals(inv.getStatus()))
                .ifPresent(inv -> orgInvitationMapper.updateStatus(inv.getId(), "REVOKED"));

        String token = UUID.randomUUID().toString().replace("-", "");
        OrgInvitation invitation = OrgInvitation.builder()
                .orgId(orgId)
                .invitedEmail(email)
                .invitedUserId(targetUser.getUserId())
                .inviterId(inviterId)
                .role(normalizeRole(role))
                .token(token)
                .status("PENDING")
                .expiresAt(Instant.now().plusSeconds(Math.max(invitationTtlHours, 1) * 3600))
                .build();
        orgInvitationMapper.insert(invitation);

        sendInvitationEmail(org, invitation, inviterId);
        log.info("组织邀请已创建：orgId={}, email={}, role={}, inviterId={}", orgId, maskEmail(email), role, inviterId);
        return token;
    }

    /**
     * 接受组织邀请。
     *
     * @param token  邀请令牌
     * @param userId 当前登录用户 ID
     */
    @Transactional
    public void acceptInvitation(String token, String userId) {
        OrgInvitation invitation = orgInvitationMapper.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("邀请链接无效或已过期"));

        if (!"PENDING".equals(invitation.getStatus())) {
            throw new IllegalArgumentException("该邀请已被处理");
        }
        if (invitation.getExpiresAt().isBefore(Instant.now())) {
            orgInvitationMapper.updateStatus(invitation.getId(), "EXPIRED");
            throw new IllegalArgumentException("邀请链接已过期");
        }

        SysUser user = sysUserMapper.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        if (!invitation.getInvitedEmail().equalsIgnoreCase(user.getEmail())) {
            throw new IllegalArgumentException("邀请邮箱与当前账号邮箱不一致");
        }

        if (orgMemberMapper.findByOrgIdAndUserId(invitation.getOrgId(), userId).isPresent()) {
            orgInvitationMapper.updateStatus(invitation.getId(), "ACCEPTED");
            throw new IllegalArgumentException("您已经是该组织成员");
        }

        addMember(invitation.getOrgId(), userId, invitation.getRole());
        orgInvitationMapper.updateStatus(invitation.getId(), "ACCEPTED");
        log.info("用户接受组织邀请：orgId={}, userId={}", invitation.getOrgId(), userId);
    }

    /**
     * 拒绝组织邀请。
     */
    @Transactional
    public void rejectInvitation(String token, String userId) {
        OrgInvitation invitation = orgInvitationMapper.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("邀请链接无效或已过期"));

        if (!"PENDING".equals(invitation.getStatus())) {
            throw new IllegalArgumentException("该邀请已被处理");
        }

        SysUser user = sysUserMapper.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!invitation.getInvitedEmail().equalsIgnoreCase(user.getEmail())) {
            throw new IllegalArgumentException("邀请邮箱与当前账号邮箱不一致");
        }

        orgInvitationMapper.updateStatus(invitation.getId(), "REJECTED");
        log.info("用户拒绝组织邀请：orgId={}, userId={}", invitation.getOrgId(), userId);
    }

    /**
     * 撤销组织邀请（仅 OWNER/ADMIN）。
     */
    @Transactional
    public void cancelInvitation(String orgId, Long invitationId, String operatorId) {
        validateInvitationPermission(orgId, operatorId);
        OrgInvitation invitation = orgInvitationMapper.findById(invitationId)
                .orElseThrow(() -> new IllegalArgumentException("邀请不存在"));
        if (!orgId.equals(invitation.getOrgId())) {
            throw new IllegalArgumentException("邀请不属于该组织");
        }
        orgInvitationMapper.updateStatus(invitationId, "REVOKED");
        log.info("组织邀请已撤销：orgId={}, invitationId={}, operatorId={}", orgId, invitationId, operatorId);
    }

    /**
     * 获取组织的待处理邀请列表（含用户名）。
     */
    public List<Map<String, Object>> getPendingInvitations(String orgId) {
        List<OrgInvitation> invitations = orgInvitationMapper.findPendingByOrgId(orgId);
        if (invitations.isEmpty()) {
            return new ArrayList<>();
        }

        // 批量查用户名（已注册用户）
        List<String> userIds = invitations.stream()
                .map(OrgInvitation::getInvitedUserId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        Map<String, String> userIdToName = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (SysUser u : sysUserMapper.findByUserIds(userIds)) {
                if (u.getUserId() != null) {
                    userIdToName.put(u.getUserId(), u.getUsername());
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (OrgInvitation inv : invitations) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", inv.getId());
            item.put("email", inv.getInvitedEmail());
            String invitedUserId = inv.getInvitedUserId();
            item.put("username", invitedUserId != null ? userIdToName.getOrDefault(invitedUserId, "") : "");
            item.put("role", inv.getRole());
            item.put("status", inv.getStatus());
            item.put("token", inv.getToken());
            item.put("expiresAt", inv.getExpiresAt());
            item.put("createdAt", inv.getCreatedAt());
            result.add(item);
        }
        return result;
    }

    /**
     * 获取当前用户收到的待处理邀请列表（含组织名称）。
     */
    public List<Map<String, Object>> getMyPendingInvitations(String userId) {
        SysUser user = sysUserMapper.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return List.of();
        }

        List<OrgInvitation> invitations = orgInvitationMapper.findPendingByEmail(user.getEmail().toLowerCase(Locale.ROOT));
        List<Map<String, Object>> result = new ArrayList<>();
        for (OrgInvitation inv : invitations) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", inv.getId());
            item.put("orgId", inv.getOrgId());
            item.put("role", inv.getRole());
            item.put("token", inv.getToken());
            item.put("expiresAt", inv.getExpiresAt());
            item.put("createdAt", inv.getCreatedAt());
            organizationMapper.findByOrgId(inv.getOrgId()).ifPresent(org ->
                    item.put("orgName", org.getName()));
            result.add(item);
        }
        return result;
    }

    // ── 组织加入申请 ─────────────────────────────────────────

    /**
     * 提交加入组织申请。
     *
     * @param orgId   目标组织 ID
     * @param userId  申请人 userId
     * @param message 申请留言（可选）
     * @throws IllegalArgumentException 如果组织不存在、是 PERSONAL 组织、已是成员或已有待处理申请
     */
    @Transactional
    public void applyJoin(String orgId, String userId, String message) {
        Organization org = organizationMapper.findByOrgId(orgId)
                .orElseThrow(() -> new IllegalArgumentException("组织不存在：" + orgId));
        if ("PERSONAL".equals(org.getOrgType())) {
            throw new IllegalArgumentException("个人空间不支持申请加入");
        }
        if (orgMemberMapper.findByOrgIdAndUserId(orgId, userId).isPresent()) {
            throw new IllegalArgumentException("您已经是该组织成员");
        }
        String normalizedMessage = message != null ? message.strip() : null;
        Optional<OrgJoinRequest> existingRequest = orgJoinRequestMapper.findByUserIdAndOrgId(userId, orgId);
        if (existingRequest.isPresent()) {
            OrgJoinRequest request = existingRequest.get();
            if ("PENDING".equals(request.getStatus())) {
                throw new IllegalArgumentException("您已提交过加入申请，请等待审批");
            }
            orgJoinRequestMapper.reopenAsPending(request.getId(), normalizedMessage);
        } else {
            OrgJoinRequest request = OrgJoinRequest.builder()
                    .orgId(orgId)
                    .userId(userId)
                    .message(normalizedMessage)
                    .status("PENDING")
                    .build();
            orgJoinRequestMapper.insert(request);
        }

        sendJoinRequestNotification(org, userId, message);
        log.info("用户提交加入组织申请：orgId={}, userId={}", orgId, userId);
    }

    /**
     * 审批通过加入申请。
     */
    @Transactional
    public void approveJoinRequest(Long requestId, String operatorId) {
        OrgJoinRequest request = orgJoinRequestMapper.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("申请不存在"));
        if (!"PENDING".equals(request.getStatus())) {
            throw new IllegalArgumentException("该申请已被处理");
        }
        validateAdminPermission(request.getOrgId(), operatorId);

        if (orgMemberMapper.findByOrgIdAndUserId(request.getOrgId(), request.getUserId()).isPresent()) {
            orgJoinRequestMapper.updateStatus(requestId, "APPROVED");
            throw new IllegalArgumentException("该用户已经是组织成员");
        }

        addMember(request.getOrgId(), request.getUserId(), "MEMBER");
        orgJoinRequestMapper.updateStatus(requestId, "APPROVED");
        log.info("加入申请已批准：orgId={}, userId={}", request.getOrgId(), request.getUserId());
    }

    /**
     * 拒绝加入申请。
     */
    @Transactional
    public void rejectJoinRequest(Long requestId, String operatorId) {
        OrgJoinRequest request = orgJoinRequestMapper.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("申请不存在"));
        if (!"PENDING".equals(request.getStatus())) {
            throw new IllegalArgumentException("该申请已被处理");
        }
        validateAdminPermission(request.getOrgId(), operatorId);
        orgJoinRequestMapper.updateStatus(requestId, "REJECTED");
        log.info("加入申请已拒绝：orgId={}, userId={}", request.getOrgId(), request.getUserId());
    }

    /**
     * 获取组织的待处理加入申请列表（含申请人用户名）。
     */
    public List<Map<String, Object>> getPendingJoinRequests(String orgId) {
        List<OrgJoinRequest> requests = orgJoinRequestMapper.findPendingByOrgId(orgId);
        if (requests.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> userIds = requests.stream()
                .map(OrgJoinRequest::getUserId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        Map<String, String> userIdToName = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (SysUser u : sysUserMapper.findByUserIds(userIds)) {
                if (u.getUserId() != null) {
                    userIdToName.put(u.getUserId(), u.getUsername());
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (OrgJoinRequest req : requests) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", req.getId());
            item.put("orgId", req.getOrgId());
            String applicantUserId = req.getUserId();
            item.put("userId", applicantUserId);
            item.put("username", applicantUserId != null ? userIdToName.getOrDefault(applicantUserId, applicantUserId) : "");
            item.put("message", req.getMessage());
            item.put("status", req.getStatus());
            item.put("createdAt", req.getCreatedAt());
            result.add(item);
        }
        return result;
    }

    /**
     * 获取当前用户提交的加入申请列表（含组织名称）。
     */
    public List<Map<String, Object>> getMyJoinRequests(String userId) {
        List<OrgJoinRequest> requests = orgJoinRequestMapper.findByUserId(userId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (OrgJoinRequest req : requests) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", req.getId());
            item.put("orgId", req.getOrgId());
            item.put("message", req.getMessage());
            item.put("status", req.getStatus());
            item.put("createdAt", req.getCreatedAt());
            organizationMapper.findByOrgId(req.getOrgId()).ifPresent(org -> {
                item.put("orgName", org.getName());
                item.put("orgType", org.getOrgType());
            });
            result.add(item);
        }
        return result;
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
     * <p>使用 IN 批量查询替代逐条 N+1 查询：
     * <ol>
     *   <li>查出所有 OrgMember（1 次 SQL）</li>
     *   <li>收集 userId 列表，一次性批量查 SysUser（1 次 SQL）</li>
     *   <li>在内存中按 userId 关联 username</li>
     * </ol>
     */
    public List<Map<String, Object>> getOrgMembersWithUsername(String orgId) {
        List<OrgMember> members = orgMemberMapper.findByOrgId(orgId);
        if (members.isEmpty()) return new ArrayList<>();

        // 批量查 username（1 次 SQL，替代 N 次单条查询）
        List<String> userIds = members.stream()
                .map(OrgMember::getUserId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        Map<String, String> userIdToName = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (SysUser u : sysUserMapper.findByUserIds(userIds)) {
                if (u.getUserId() != null) {
                    userIdToName.put(u.getUserId(), u.getUsername());
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (OrgMember member : members) {
            Map<String, Object> item = new HashMap<>();
            String memberUserId = member.getUserId();
            item.put("userId",   memberUserId);
            item.put("username", memberUserId != null ? userIdToName.getOrDefault(memberUserId, memberUserId) : "");
            item.put("role",     member.getRole());
            item.put("joinedAt", member.getJoinedAt());
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
     * <p>仅 OWNER 可修改成员角色，ADMIN 只能移除成员不能改角色。
     *
     * @throws IllegalArgumentException 若无权限、目标成员不存在或试图修改 OWNER 角色
     */
    @Transactional
    public void updateMemberRole(String orgId, String targetUserId, String newRole,
                                  String operatorId) {
        OrgMember operator = orgMemberMapper.findByOrgIdAndUserId(orgId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("您不是该组织的成员"));
        if (!"OWNER".equals(operator.getRole())) {
            throw new IllegalArgumentException("只有组织拥有者才能修改成员角色");
        }

        OrgMember target = orgMemberMapper.findByOrgIdAndUserId(orgId, targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("该用户不是组织成员"));
        if ("OWNER".equals(target.getRole())) {
            throw new IllegalArgumentException("不能修改组织拥有者的角色，请先转让拥有者身份");
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

    private Organization validateInvitationPermission(String orgId, String inviterId) {
        return validateAdminPermission(orgId, inviterId, "邀请成员");
    }

    private Organization validateAdminPermission(String orgId, String operatorId) {
        return validateAdminPermission(orgId, operatorId, "管理");
    }

    private Organization validateAdminPermission(String orgId, String operatorId, String action) {
        Organization org = organizationMapper.findByOrgId(orgId)
                .orElseThrow(() -> new IllegalArgumentException("组织不存在：orgId=" + orgId));

        if ("PERSONAL".equals(org.getOrgType())) {
            throw new IllegalArgumentException("个人空间不支持" + action);
        }

        OrgMember operator = orgMemberMapper.findByOrgIdAndUserId(orgId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("您不是该组织的成员"));
        if (!"OWNER".equals(operator.getRole()) && !"ADMIN".equals(operator.getRole())) {
            throw new IllegalArgumentException("只有组织拥有者或管理员才能" + action);
        }
        return org;
    }

    private void sendJoinRequestNotification(Organization org, String applicantUserId, String message) {
        SysUser applicant = sysUserMapper.findByUserId(applicantUserId).orElse(null);
        String applicantName = applicant != null ? applicant.getUsername() : "未知用户";
        String manageUrl = buildFrontendUrl("/org");

        // 查找组织 OWNER 和 ADMIN 的邮箱
        List<OrgMember> admins = orgMemberMapper.findByOrgId(org.getOrgId()).stream()
                .filter(m -> "OWNER".equals(m.getRole()) || "ADMIN".equals(m.getRole()))
                .toList();
        if (admins.isEmpty()) {
            return;
        }

        List<String> adminEmails = admins.stream()
                .map(m -> sysUserMapper.findByUserId(m.getUserId()).map(SysUser::getEmail).orElse(null))
                .filter(e -> e != null && !e.isBlank())
                .distinct()
                .toList();
        if (adminEmails.isEmpty()) {
            return;
        }

        String subject = "【AI Agent】用户申请加入组织「" + org.getName() + "」";
        String reason = message != null && !message.isBlank() ? "\n申请理由：" + message : "";
        String text = """
                用户 %s 申请加入组织「%s」。
                %s

                请登录系统前往组织管理页面审批：
                %s
                """.formatted(applicantName, org.getName(), reason, manageUrl);

        String html = "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"/><style>"
                + "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#f5f5f5;margin:0;padding:20px}"
                + ".container{max-width:520px;margin:0 auto;background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.08)}"
                + ".header{background:#4D6BFE;color:#fff;padding:24px 28px}"
                + ".header h2{margin:0;font-size:18px;font-weight:600}"
                + ".content{padding:28px;font-size:14px;color:#333;line-height:1.6}"
                + ".btn{display:inline-block;margin:18px 0;padding:12px 28px;background:#4D6BFE;color:#fff;text-decoration:none;border-radius:8px;font-weight:500}"
                + ".tip{color:#999;font-size:13px;line-height:1.6}"
                + ".message{background:#f9f9f9;border-left:4px solid #4D6BFE;padding:12px 16px;border-radius:4px}"
                + ".footer{padding:16px 28px;background:#fafafa;border-top:1px solid #eee;font-size:12px;color:#aaa}"
                + "</style></head><body><div class=\"container\">"
                + "<div class=\"header\"><h2>加入申请通知</h2></div>"
                + "<div class=\"content\"><p>用户 <strong>" + applicantName + "</strong> 申请加入组织 <strong>" + org.getName() + "</strong>。</p>"
                + (message != null && !message.isBlank() ? "<div class=\"message\">申请理由：" + message + "</div>" : "")
                + "<a class=\"btn\" href=\"" + manageUrl + "\">前往审批</a>"
                + "<p class=\"tip\">此邮件由 AI Agent 自动发送，请勿直接回复。</p></div>"
                + "<div class=\"footer\">AI Agent 组织管理</div>"
                + "</div></body></html>";

        for (String email : adminEmails) {
            mailSender.send(MailMessage.builder()
                    .to(email)
                    .subject(subject)
                    .text(text)
                    .html(html)
                    .build());
        }
    }

    private void sendInvitationEmail(Organization org, OrgInvitation invitation, String inviterId) {
        SysUser inviter = sysUserMapper.findByUserId(inviterId).orElse(null);
        String inviterName = inviter != null ? inviter.getUsername() : "未知用户";
        String acceptUrl = buildFrontendUrl("/invite/" + invitation.getToken());

        String subject = "【AI Agent】您被邀请加入组织「" + org.getName() + "」";
        String text = """
                %s 邀请您加入 AI Agent 组织「%s」，角色：%s。

                请点击以下链接接受邀请：
                %s

                该链接 %d 小时内有效。如非本人操作，请忽略此邮件。
                """.formatted(inviterName, org.getName(), invitation.getRole(), acceptUrl, invitationTtlHours);

        String html = "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"/><style>"
                + "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#f5f5f5;margin:0;padding:20px}"
                + ".container{max-width:520px;margin:0 auto;background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.08)}"
                + ".header{background:#4D6BFE;color:#fff;padding:24px 28px}"
                + ".header h2{margin:0;font-size:18px;font-weight:600}"
                + ".content{padding:28px;font-size:14px;color:#333;line-height:1.6}"
                + ".link{word-break:break-all;color:#4D6BFE}"
                + ".tip{color:#999;font-size:13px;line-height:1.6}"
                + ".footer{padding:16px 28px;background:#fafafa;border-top:1px solid #eee;font-size:12px;color:#aaa}"
                + "</style></head><body><div class=\"container\">"
                + "<div class=\"header\"><h2>组织邀请</h2></div>"
                + "<div class=\"content\"><p><strong>" + inviterName + "</strong> 邀请您加入组织 <strong>" + org.getName() + "</strong>，角色为 <strong>" + invitation.getRole() + "</strong>。</p>"
                + "<p><a href=\"" + acceptUrl + "\" style=\"display:inline-block;margin:18px 0;padding:12px 28px;background:#4D6BFE;color:#ffffff;text-decoration:none;border-radius:8px;font-weight:600;\">接受邀请</a></p>"
                + "<p class=\"tip\">如果按钮无法打开，请复制此链接到浏览器：</p>"
                + "<p><a class=\"link\" href=\"" + acceptUrl + "\">" + acceptUrl + "</a></p>"
                + "<p class=\"tip\">链接 " + invitationTtlHours + " 小时内有效。如非本人操作，请忽略此邮件。</p></div>"
                + "<div class=\"footer\">此邮件由 AI Agent 自动发送，请勿直接回复。</div>"
                + "</div></body></html>";

        mailSender.send(MailMessage.builder()
                .to(invitation.getInvitedEmail())
                .subject(subject)
                .text(text)
                .html(html)
                .build());
    }

    private String buildFrontendUrl(String path) {
        String base = frontendUrl != null ? frontendUrl.strip() : "";
        if (base.isEmpty()) {
            base = "http://localhost:5173";
        }
        String normalizedPath = path != null && path.startsWith("/") ? path : "/" + path;
        return base.replaceAll("/+$", "") + "/index.html#" + normalizedPath;
    }

    private String normalizeRole(String role) {
        String r = role != null ? role.strip().toUpperCase(Locale.ROOT) : "MEMBER";
        return switch (r) {
            case "OWNER", "ADMIN", "MEMBER" -> r;
            default -> "MEMBER";
        };
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + email.substring(Math.max(at, 0));
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private void addMember(String orgId, String userId, String role) {
        OrgMember member = OrgMember.builder()
                .orgId(orgId)
                .userId(userId)
                .role(role)
                .build();
        orgMemberMapper.insert(member);
    }
}

