package com.example.aiagent.security.mapper;

import com.example.aiagent.security.entity.OrgJoinRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 组织加入申请 Mapper
 */
@Mapper
public interface OrgJoinRequestMapper {

    /** 按 ID 查询申请 */
    Optional<OrgJoinRequest> findById(@Param("id") Long id);

    /** 查询组织的所有申请 */
    List<OrgJoinRequest> findByOrgId(@Param("orgId") String orgId);

    /** 查询组织的待处理申请 */
    List<OrgJoinRequest> findPendingByOrgId(@Param("orgId") String orgId);

    /** 查询用户的所有申请 */
    List<OrgJoinRequest> findByUserId(@Param("userId") String userId);

    /** 查询用户对某个组织的历史申请 */
    Optional<OrgJoinRequest> findByUserIdAndOrgId(@Param("userId") String userId,
                                                  @Param("orgId") String orgId);

    /** 查询用户的待处理申请 */
    List<OrgJoinRequest> findPendingByUserIdAndOrgId(@Param("userId") String userId,
                                                     @Param("orgId") String orgId);

    /** 插入申请 */
    void insert(OrgJoinRequest request);

    /** 更新申请状态 */
    void updateStatus(@Param("id") Long id, @Param("status") String status);

    /** 将历史申请重新提交为待处理 */
    void reopenAsPending(@Param("id") Long id, @Param("message") String message);

    /** 删除申请 */
    void deleteById(@Param("id") Long id);
}
