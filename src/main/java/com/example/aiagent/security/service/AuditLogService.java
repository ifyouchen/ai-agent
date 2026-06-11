package com.example.aiagent.security.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * 操作审计日志服务
 *
 * 记录所有安全相关事件，满足合规要求：
 * - 谁（userId）
 * - 什么时间（timestamp）
 * - 做了什么（eventType）
 * - 是否成功（result）
 * - 来自哪里（clientIp）
 *
 * 生产环境建议写入专用审计数据库或发送到 ELK，
 * 此处用结构化日志输出，方便接入 Loki/ELK。
 */
@Slf4j
@Service
public class AuditLogService {

    public enum EventType {
        // 认证相关
        LOGIN_SUCCESS, LOGIN_FAILED, TOKEN_INVALID,
        // AI 请求相关
        AI_CHAT_REQUEST, AI_CHAT_SUCCESS, AI_CHAT_BLOCKED,
        // 安全事件
        PROMPT_INJECTION_DETECTED, RATE_LIMIT_TRIGGERED,
        OUTPUT_SENSITIVE_FILTERED,
        // 知识库
        KB_DOCUMENT_UPLOAD, KB_QUERY
    }

    @Async("observabilityExecutor")
    public void log(EventType eventType, String userId, String sessionId,
                    String clientIp, boolean success, Map<String, Object> extra) {
        // 结构化 JSON 日志（Loki/ELK 可直接解析）
        log.info("[AUDIT] event={} userId={} sessionId={} clientIp={} success={} time={} extra={}",
                eventType, userId, sessionId, clientIp, success,
                Instant.now(), extra);
    }

    /** 快捷方法：记录安全拦截事件 */
    public void logSecurityBlock(EventType eventType, String userId,
                                  String clientIp, String reason) {
        log(eventType, userId, null, clientIp, false,
                Map.of("reason", reason));
        log.warn("[SECURITY_AUDIT] event={} userId={} clientIp={} reason={}",
                eventType, userId, clientIp, reason);
    }

    /** 快捷方法：记录成功的 AI 对话 */
    public void logAiChat(String userId, String sessionId, String clientIp,
                           int inputTokens, int outputTokens) {
        log(EventType.AI_CHAT_SUCCESS, userId, sessionId, clientIp, true,
                Map.of("inputTokens", inputTokens, "outputTokens", outputTokens));
    }
}
