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

    /**
     * 知识库 ID（可选）
     * 指定后，对话将在该知识库内执行混合 RAG 检索，答案基于知识库内容生成。
     * 不传或传 null 则不限定知识库（全局检索）。
     */
    private Long kbId;
}
