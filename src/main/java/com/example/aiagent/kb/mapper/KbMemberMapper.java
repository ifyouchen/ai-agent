package com.example.aiagent.kb.mapper;

import com.example.aiagent.kb.entity.KbMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 知识库成员 Mapper
 */
@Mapper
public interface KbMemberMapper {

    /** 按 kbId + userId 查询成员 */
    Optional<KbMember> findByKbIdAndUserId(@Param("kbId") Long kbId, @Param("userId") String userId);

    /** 查询知识库的所有成员 */
    List<KbMember> findByKbId(@Param("kbId") Long kbId);

    /** 查询用户有权访问的所有知识库 ID */
    List<Long> findKbIdsByUserId(@Param("userId") String userId);

    /** 查询用户在指定知识库中的角色 */
    Optional<String> findRoleByKbIdAndUserId(@Param("kbId") Long kbId, @Param("userId") String userId);

    /** 插入成员 */
    void insert(KbMember member);

    /** 更新成员角色 */
    void updateRole(@Param("kbId") Long kbId, @Param("userId") String userId, @Param("role") String role);

    /** 移除成员 */
    void deleteByKbIdAndUserId(@Param("kbId") Long kbId, @Param("userId") String userId);

    /** 删除知识库的所有成员（级联删除时调用） */
    void deleteByKbId(@Param("kbId") Long kbId);
}

