package com.example.aiagent.security.mapper;

import com.example.aiagent.security.entity.OrgInvitation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 组织邀请 Mapper
 */
@Mapper
public interface OrgInvitationMapper {

    /** 按 ID 查询邀请 */
    Optional<OrgInvitation> findById(@Param("id") Long id);

    /** 按 token 查询邀请 */
    Optional<OrgInvitation> findByToken(@Param("token") String token);

    /** 按组织 + 邮箱查询最新邀请 */
    Optional<OrgInvitation> findLatestByOrgAndEmail(@Param("orgId") String orgId,
                                                    @Param("email") String email);

    /** 查询组织的所有邀请 */
    List<OrgInvitation> findByOrgId(@Param("orgId") String orgId);

    /** 查询组织的待处理邀请 */
    List<OrgInvitation> findPendingByOrgId(@Param("orgId") String orgId);

    /** 查询用户收到的待处理邀请 */
    List<OrgInvitation> findPendingByEmail(@Param("email") String email);

    /** 插入邀请 */
    void insert(OrgInvitation invitation);

    /** 更新邀请状态 */
    void updateStatus(@Param("id") Long id, @Param("status") String status);

    /** 删除邀请 */
    void deleteById(@Param("id") Long id);
}
