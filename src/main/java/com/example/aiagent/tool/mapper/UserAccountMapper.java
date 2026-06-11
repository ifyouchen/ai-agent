package com.example.aiagent.tool.mapper;

import com.example.aiagent.tool.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface UserAccountMapper {

    /** 按用户 ID 查询 */
    Optional<UserAccount> findByUserId(@Param("userId") String userId);

    /** 按用户名查询（支持用户名登录场景） */
    Optional<UserAccount> findByUsername(@Param("username") String username);

    /** 更新积分 */
    void updatePoints(@Param("userId") String userId, @Param("pointsDelta") int pointsDelta);

    void insert(UserAccount account);
}
