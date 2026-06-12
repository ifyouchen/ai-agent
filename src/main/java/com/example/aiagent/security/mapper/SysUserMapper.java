package com.example.aiagent.security.mapper;

import com.example.aiagent.security.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
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

    /** 修改密码 */
    void updatePassword(@Param("userId") String userId, @Param("passwordHash") String passwordHash);

    /** 启用/禁用账号（enabled: 1=启用 0=禁用） */
    void updateEnabled(@Param("userId") String userId, @Param("enabled") int enabled);

    /** 查询全部用户（管理员用，分页） */
    List<SysUser> findAll(@Param("offset") int offset, @Param("limit") int limit);

    /** 用户总数 */
    long countAll();
}

