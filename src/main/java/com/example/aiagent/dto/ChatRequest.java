package com.example.aiagent.dto;

import lombok.Data;

@Data
public class ChatRequest {
    /**
     * 会话 ID（用于记忆隔离）
     * 同一用户同一会话使用相同的 sessionId
     */
    private String sessionId;

    /**
     * 用户输入的消息
     */
    private String message;
}
