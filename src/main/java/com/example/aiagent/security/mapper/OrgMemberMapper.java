package com.example.aiagent.security.mapper;

import com.example.aiagent.security.entity.OrgMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 组织成员 Mapper
 */
@Mapper
public interface OrgMemberMapper {

    /** 按 orgId + userId 查询成员 */
    Optional<OrgMember> findByOrgIdAndUserId(@Param("orgId") String orgId, @Param("userId") String userId);

    /** 查询组织的所有成员 */
    List<OrgMember> findByOrgId(@Param("orgId") String orgId);

    /** 查询用户加入的所有组织 */
    List<OrgMember> findByUserId(@Param("userId") String userId);

    /** 查询用户所属的组织 ID 列表（用于知识库列表查询） */
    List<String> findOrgIdsByUserId(@Param("userId") String userId);

    /** 插入成员 */
    void insert(OrgMember member);

    /** 更新成员角色 */
    void updateRole(@Param("orgId") String orgId, @Param("userId") String userId, @Param("role") String role);

    /** 移除成员 */
    void deleteByOrgIdAndUserId(@Param("orgId") String orgId, @Param("userId") String userId);

    /** 统计组织成员数 */
    int countByOrgId(@Param("orgId") String orgId);
}

