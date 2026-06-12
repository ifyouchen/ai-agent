package com.example.aiagent.chat.entity;

import lombok.Data;

import java.time.Instant;

/**
 * 聊天消息实体（对应 chat_message 表）
 */
@Data
public class ChatMessage {

    private Long id;

    /** 会话标识（与 chat_session.session_id 关联） */
    private String sessionId;

    /** 所属用户 */
    private String userId;

    /** 消息角色：user | ai */
    private String role;

    /** 消息纯文本内容（存储原始文本，不含 HTML） */
    private String content;

    private Instant createdAt;

    /** 用户反馈：'up'（有帮助）| 'down'（没帮助）| null（未反馈） */
    private String feedback;
}
