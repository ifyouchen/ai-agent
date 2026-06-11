package com.example.aiagent.observability.model;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;

/**
 * 单次 LLM 调用的完整上下文快照
 * 由 AOP 切面在调用前后填充，传递给指标记录和持久化服务
 */
@Data
@Builder
public class LlmCallContext {

    /** 链路追踪 ID（从 MDC 获取） */
    private String traceId;

    /** 会话 ID */
    private String sessionId;

    /** 用户 ID */
    private String userId;

    /** 模型名称，如 deepseek-chat / claude-opus-4-8 */
    private String modelName;

    /** 业务场景标签，如 chat / rag / agent */
    private String scenario;

    /** 输入 Token 数 */
    private int inputTokens;

    /** 输出 Token 数 */
    private int outputTokens;

    /** 调用开始时间 */
    private Instant startTime;

    /** 调用总耗时（毫秒） */
    private long durationMs;

    /** 是否成功 */
    private boolean success;

    /** 失败原因（成功时为 null） */
    private String errorMessage;

    /** 输入内容摘要（截断，用于问题排查） */
    private String inputSnippet;

    /** 输出内容摘要（截断） */
    private String outputSnippet;

    /** 本次调用费用（USD） */
    private double costUsd;
}
