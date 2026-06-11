package com.example.aiagent.security.mapper;

import com.example.aiagent.security.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface SysUserMapper {

    /** 按用户名查询（登录时使用） */
    Optional<SysUser> findByUsername(@Param("username") String username);

    /** 按 userId 查询（Token 验证时使用） */
    Optional<SysUser> findByUserId(@Param("userId") String userId);

    /** 检查用户名是否已存在 */
    boolean existsByUsername(@Param("username") String username);

    /** 注册新用户 */
    void insert(SysUser user);
}

