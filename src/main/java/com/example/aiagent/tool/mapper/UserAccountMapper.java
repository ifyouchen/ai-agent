package com.example.aiagent.tool.mapper;

import com.example.aiagent.tool.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface UserAccountMapper {

    Optional<UserAccount> findByUserId(@Param("userId") String userId);

    void insert(UserAccount account);
}
