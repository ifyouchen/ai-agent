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

    /** 按邮箱查询（忘记密码/修改密码时使用） */
    Optional<SysUser> findByEmail(@Param("email") String email);

    /** 检查用户名是否已存在 */
    boolean existsByUsername(@Param("username") String username);

    /** 检查邮箱是否已存在 */
    boolean existsByEmail(@Param("email") String email);

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

    /** 用户名模糊搜索（知识库成员添加时使用，返回前 10 条） */
    List<SysUser> searchByUsername(@Param("keyword") String keyword, @Param("limit") int limit);

    /** 按 userId 列表批量查询（组织成员 username 展示时使用，N+1 → 单次 IN 查询） */
    List<SysUser> findByUserIds(@Param("userIds") List<String> userIds);

    /** 更新用户 Profile（仅昵称；邮箱注册后不可修改） */
    void updateProfile(@Param("userId") String userId,
                       @Param("nickname") String nickname,
                       @Param("email") String email);

    /** 管理员：分页查询（支持按用户名关键词过滤） */
    List<SysUser> findByKeyword(@Param("keyword") String keyword,
                                @Param("offset") int offset,
                                @Param("limit") int limit);

    /** 管理员：关键词匹配总数 */
    long countByKeyword(@Param("keyword") String keyword);

    /** 更新用户角色 */
    void updateRoles(@Param("userId") String userId, @Param("roles") String roles);
}

