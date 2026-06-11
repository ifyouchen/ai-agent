package com.example.aiagent.controller;

import com.example.aiagent.agent.AgentFactory.StreamingChatAssistant;
import com.example.aiagent.security.filter.OutputContentFilter;
import com.example.aiagent.security.filter.PromptInjectionFilter;
import com.example.aiagent.security.service.AuditLogService;
import com.example.aiagent.security.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式对话接口（SSE，适合实时展示场景）
 * 前端用 EventSource 或 fetch + ReadableStream 接收
 *
 * 完整安全链路（与 ChatController 对齐）：
 *   1. JWT 认证（JwtAuthFilter 完成）
 *   2. Prompt 注入检测（PromptInjectionFilter）
 *   3. 限流校验（RateLimitService）
 *   4. LLM 流式调用
 *   5. 输出内容脱敏（OutputContentFilter，在 onComplete 对全文统一处理）
 *   6. 审计日志（AuditLogService）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class StreamingChatController {

    private final StreamingChatAssistant streamingChatAssistant;
    private final PromptInjectionFilter promptInjectionFilter;
    private final RateLimitService rateLimitService;
    private final OutputContentFilter outputContentFilter;
    private final AuditLogService auditLogService;

    /**
     * 流式对话（SSE 推送，字符逐步出现）
     *
     * GET /api/v1/chat/stream?sessionId=user-123&message=你好
     * Header: Authorization: Bearer <token>
     *
     * 前端接收示例（JavaScript）：
     * const es = new EventSource(`/api/v1/chat/stream?...&token=<jwt>`);
     * es.onmessage = e => output.textContent += e.data;
     * es.addEventListener('done', () => es.close());
     *
     * 注意：SSE（EventSource）不支持自定义 Header，因此 Token 通过 URL 参数传递。
     *       JwtAuthFilter 已支持从 ?token= 参数中读取。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam String sessionId,
            @RequestParam String message,
            @AuthenticationPrincipal String userId,
            HttpServletRequest httpRequest) {

        SseEmitter emitter = new SseEmitter(120_000L);
        String clientIp = getClientIp(httpRequest);
        MDC.put("scenario", "stream_chat");

        try {
            // ── Step 1：Prompt 注入检测 ────────────────────
            PromptInjectionFilter.FilterResult injectionCheck = promptInjectionFilter.check(message);
            if (injectionCheck.blocked()) {
                log.warn("流式对话 Prompt 注入被拦截 userId={}", userId);
                auditLogService.logSecurityBlock(
                        AuditLogService.EventType.PROMPT_INJECTION_DETECTED,
                        userId, clientIp, injectionCheck.reason());
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(injectionCheck.reason()));
                emitter.complete();
                return emitter;
            }

            // ── Step 2：限流校验 ──────────────────────────
            RateLimitService.RateLimitResult rateLimit = rateLimitService.tryAcquire(userId);
            if (!rateLimit.allowed()) {
                log.warn("流式对话限流触发 userId={}", userId);
                auditLogService.logSecurityBlock(
                        AuditLogService.EventType.RATE_LIMIT_TRIGGERED,
                        userId, clientIp, rateLimit.reason());
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(rateLimit.reason()));
                emitter.complete();
                return emitter;
            }

            // ── Step 3：审计日志（请求开始）──────────────
            auditLogService.log(AuditLogService.EventType.AI_CHAT_REQUEST,
                    userId, sessionId, clientIp, true,
                    Map.of("messageLength", injectionCheck.sanitizedInput().length(), "mode", "stream"));

        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        } finally {
            MDC.remove("scenario");
        }

        // ── Step 4：LLM 流式调用 ──────────────────────────
        log.info("开始流式对话 userId={} sessionId={}", userId, sessionId);
        String sanitizedMessage = promptInjectionFilter.check(message).sanitizedInput();

        // 用 AtomicReference 累积全部 token，供 onComplete 时统一脱敏
        AtomicReference<StringBuilder> fullTextRef = new AtomicReference<>(new StringBuilder());
        long startMs = System.currentTimeMillis();

        streamingChatAssistant.streamChat(sessionId, sanitizedMessage)
                .onNext(token -> {
                    try {
                        // 累积 token 用于后续脱敏
                        fullTextRef.get().append(token);
                        emitter.send(SseEmitter.event().data(token));
                    } catch (IOException e) {
                        log.warn("SSE 推送失败，客户端可能已断开: {}", e.getMessage());
                        emitter.completeWithError(e);
                    }
                })
                .onComplete(response -> {
                    try {
                        // ── Step 5：输出内容脱敏 ──────────────────
                        // 对完整回复做一次脱敏检查；若检测到敏感内容，推送一条警告事件
                        String fullText = fullTextRef.get().toString();
                        OutputContentFilter.FilterResult outputCheck = outputContentFilter.filter(fullText);
                        if (!outputCheck.detectedTypes().isEmpty()) {
                            auditLogService.logSecurityBlock(
                                    AuditLogService.EventType.OUTPUT_SENSITIVE_FILTERED,
                                    userId, clientIp,
                                    "流式输出脱敏，检测到：" + outputCheck.detectedTypes());
                            // 向前端推送一条标记事件，通知输出已被脱敏（前端可选处理）
                            emitter.send(SseEmitter.event()
                                    .name("desensitized")
                                    .data(String.join(",", outputCheck.detectedTypes())));
                        }

                        // ── Step 6：审计日志（对话完成）──────────
                        long duration = System.currentTimeMillis() - startMs;
                        auditLogService.logAiChat(userId, sessionId, clientIp, 0, 0);
                        log.info("流式对话完成 userId={} sessionId={} 耗时={}ms", userId, sessionId, duration);

                        emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                })
                .onError(error -> {
                    log.error("流式对话出错 userId={} sessionId={}: {}", userId, sessionId, error.getMessage());
                    auditLogService.log(AuditLogService.EventType.AI_CHAT_BLOCKED,
                            userId, sessionId, clientIp, false,
                            Map.of("error", error.getMessage(), "mode", "stream"));
                    emitter.completeWithError(error);
                })
                .start();

        return emitter;
    }

    /** 提取客户端真实 IP（兼容 Nginx 反向代理） */
    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
