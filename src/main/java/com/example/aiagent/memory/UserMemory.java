package com.example.aiagent.memory;

import lombok.Data;

import java.time.Instant;

/**
 * 用户长期记忆实体（对应 user_memory 表）
 *
 * <p>存储从对话中 LLM 提取的持久事实/偏好（如职业、技术栈、语言偏好），
 * 跨会话共享，注入 system prompt 实现个性化。
 */
@Data
public class UserMemory {

    private Long id;

    /** 用户 ID */
    private String userId;

    /** 提取的事实/偏好文本 */
    private String factsText;

    private Instant updatedAt;
}

