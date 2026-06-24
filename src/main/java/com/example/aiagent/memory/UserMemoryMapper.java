package com.example.aiagent.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户长期记忆 Mapper
 */
@Mapper
public interface UserMemoryMapper {

    /** 按用户 ID 查询长期记忆 */
    UserMemory findByUserId(@Param("userId") String userId);

    /** 新增或更新用户长期记忆（基于 user_id 唯一约束 ON CONFLICT） */
    void upsert(@Param("userId") String userId, @Param("factsText") String factsText);
}

