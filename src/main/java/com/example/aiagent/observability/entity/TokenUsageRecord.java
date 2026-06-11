package com.example.aiagent.observability.entity;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsageRecord {

    private Long id;

    private String traceId;

    private String sessionId;

    private String userId;

    private String modelName;

    /** 业务场景：chat / rag / agent */
    private String scenario;

    private int inputTokens;

    private int outputTokens;

    private int totalTokens;

    /** 精度到 0.00000001 USD，满足 Haiku 等低价模型的精度需求 */
    private BigDecimal costUsd;

    private Long durationMs;

    private boolean success;

    private String errorMessage;

    /** 输入内容摘要（截断至 512 字符，便于排查问题） */
    private String inputSnippet;

    private String outputSnippet;

    private Instant calledAt;
}
