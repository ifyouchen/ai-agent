package com.example.aiagent.chat.entity;

import lombok.Data;

import java.time.Instant;

/**
 * 会话分享快照实体（对应 chat_share 表）。
 */
@Data
public class ChatShare {

    private Long id;
    private String shareId;
    private String sessionId;
    private String userId;
    private String title;
    private String snapshotJson;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant revokedAt;
}
