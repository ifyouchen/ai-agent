package com.example.aiagent.chat.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天会话实体（对应 chat_session 表）
 */
@Data
public class ChatSession {

    private Long id;

    /** 前端生成的会话唯一标识 */
    private String sessionId;

    /** 所属用户 */
    private String userId;

    /** 会话标题（取首条消息前 20 字）*/
    private String title;

    /** 关联的知识库 ID（可选） */
    private Long kbId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
