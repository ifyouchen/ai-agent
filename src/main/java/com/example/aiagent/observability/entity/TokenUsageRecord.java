package com.example.aiagent.observability.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Token 用量记录表
 *
 * 建表 SQL：
 * CREATE TABLE llm_token_usage (
 *   id            BIGINT AUTO_INCREMENT PRIMARY KEY,
 *   trace_id      VARCHAR(64),
 *   session_id    VARCHAR(64),
 *   user_id       VARCHAR(64),
 *   model_name    VARCHAR(64)  NOT NULL,
 *   scenario      VARCHAR(32),
 *   input_tokens  INT          NOT NULL DEFAULT 0,
 *   output_tokens INT          NOT NULL DEFAULT 0,
 *   total_tokens  INT          NOT NULL DEFAULT 0,
 *   cost_usd      DECIMAL(10,8) NOT NULL DEFAULT 0,
 *   duration_ms   BIGINT,
 *   success       TINYINT(1)   NOT NULL DEFAULT 1,
 *   error_message VARCHAR(512),
 *   input_snippet VARCHAR(512),
 *   output_snippet VARCHAR(512),
 *   called_at     DATETIME(3)  NOT NULL,
 *   INDEX idx_user_id (user_id),
 *   INDEX idx_session_id (session_id),
 *   INDEX idx_called_at (called_at),
 *   INDEX idx_model_name (model_name)
 * ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
 */
@Entity
@Table(name = "llm_token_usage")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "model_name", length = 64, nullable = false)
    private String modelName;

    /** 业务场景：chat / rag / agent */
    @Column(name = "scenario", length = 32)
    private String scenario;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "total_tokens", nullable = false)
    private int totalTokens;

    /** 精度到 0.00000001 USD，满足 Haiku 等低价模型的精度需求 */
    @Column(name = "cost_usd", precision = 10, scale = 8, nullable = false)
    private BigDecimal costUsd;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    /** 输入内容摘要（截断至 512 字符，便于排查问题） */
    @Column(name = "input_snippet", length = 512)
    private String inputSnippet;

    @Column(name = "output_snippet", length = 512)
    private String outputSnippet;

    @Column(name = "called_at", nullable = false)
    private Instant calledAt;
}
