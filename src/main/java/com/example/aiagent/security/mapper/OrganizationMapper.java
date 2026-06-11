package com.example.aiagent.security.mapper;

import com.example.aiagent.security.entity.OrgMember;
import com.example.aiagent.security.entity.Organization;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 组织 Mapper
 */
@Mapper
public interface OrganizationMapper {

    /** 按 orgId 查询组织 */
    Optional<Organization> findByOrgId(@Param("orgId") String orgId);

    /** 按拥有者查询组织列表 */
    List<Organization> findByOwnerId(@Param("ownerId") String ownerId);

    /** 按类型查询组织 */
    List<Organization> findByOrgType(@Param("orgType") String orgType);

    /** 插入组织 */
    void insert(Organization org);

    /** 更新组织信息 */
    void update(Organization org);

    /** 按 orgId 删除组织 */
    void deleteByOrgId(@Param("orgId") String orgId);

    /** 检查 orgId 是否已存在 */
    boolean existsByOrgId(@Param("orgId") String orgId);
}

