package com.example.aiagent.controller;

import com.example.aiagent.agent.AgentFactory;
import com.example.aiagent.chat.service.ChatHistoryService;
import com.example.aiagent.kb.service.ChatRagContextService;
import com.example.aiagent.rag.retrieval.HybridRagContentRetriever;
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

    private final AgentFactory agentFactory;
    private final PromptInjectionFilter promptInjectionFilter;
    private final RateLimitService rateLimitService;
    private final OutputContentFilter outputContentFilter;
    private final AuditLogService auditLogService;
    private final ChatHistoryService chatHistoryService;
    private final ChatRagContextService chatRagContextService;

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
            @RequestParam(required = false) Long kbId,
            @RequestParam(required = false) String orgId,
            @RequestParam(required = false) String model,
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
            // SSE 响应可能已开始，不能用 completeWithError（会触发 Spring 错误页渲染）
            // 直接 complete，客户端会通过 onerror 感知连接关闭
            log.warn("流式对话预检阶段 IO 失败 userId={}: {}", userId, e.getMessage());
            emitter.complete();
            return emitter;
        } finally {
            MDC.remove("scenario");
        }

        // ── Step 4：LLM 流式调用 ──────────────────────────
        log.info("开始流式对话 userId={} sessionId={} orgId={} kbId={} model={}",
                userId, sessionId, orgId, kbId, model);
        String sanitizedMessage = promptInjectionFilter.check(message).sanitizedInput();

        // 设置 RAG 检索上下文：只有显式选择知识库（kbId 非空）时生效。
        HybridRagContentRetriever.RetrievalContext ragContext;
        try {
            ragContext = chatRagContextService.resolve(userId, orgId, kbId);
        } catch (IllegalArgumentException e) {
            log.warn("流式对话知识库上下文无效 userId={} orgId={} kbId={} reason={}",
                    userId, orgId, kbId, e.getMessage());
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (IOException ignore) {
                // 客户端已断开，忽略
            }
            emitter.complete();
            return emitter;
        }
        if (ragContext != null) {
            HybridRagContentRetriever.setContext(ragContext);
        }

        // 用 AtomicReference 累积全部 token，供 onComplete 时统一脱敏
        AtomicReference<StringBuilder> fullTextRef = new AtomicReference<>(new StringBuilder());
        long startMs = System.currentTimeMillis();

        agentFactory.streamingChatAssistantForModel(model).streamChat(sessionId, sanitizedMessage)
                .onNext(token -> {
                    try {
                        // 累积 token 用于后续脱敏
                        fullTextRef.get().append(token);
                        emitter.send(SseEmitter.event().data(token));
                    } catch (IOException e) {
                        // 客户端已断开，静默关闭，不用 completeWithError（避免触发 Spring 错误页）
                        log.debug("SSE 推送失败，客户端可能已断开: {}", e.getMessage());
                        emitter.complete();
                    }
                })
                .onComplete(response -> {
                    try {
                        // ── Step 5：输出内容脱敏 ──────────────────
                        // 对完整回复做脱敏处理；若检测到敏感内容，推送脱敏后的完整文本
                        // 让前端用 replace 事件替换已渲染的原始内容，确保用户看到的是脱敏文本
                        String fullText = fullTextRef.get().toString();
                        OutputContentFilter.FilterResult outputCheck = outputContentFilter.filter(fullText);
                        if (!outputCheck.detectedTypes().isEmpty()) {
                            auditLogService.logSecurityBlock(
                                    AuditLogService.EventType.OUTPUT_SENSITIVE_FILTERED,
                                    userId, clientIp,
                                    "流式输出脱敏，检测到：" + outputCheck.detectedTypes());
                            // 推送脱敏后的完整替换文本（前端收到 replace 事件后整体替换已显示内容）
                            emitter.send(SseEmitter.event()
                                    .name("replace")
                                    .data(outputCheck.filteredContent()));
                            log.info("[SECURITY] 流式输出已脱敏，类型：{}", outputCheck.detectedTypes());
                        }

                        // ── Step 6：异步持久化聊天记录 ──────────
                        String finalText = outputCheck.filteredContent();
                        chatHistoryService.saveSession(sessionId, userId,
                                sanitizedMessage.substring(0, Math.min(sanitizedMessage.length(), 20)), kbId);
                        chatHistoryService.saveMessage(sessionId, userId, "user", sanitizedMessage);
                        chatHistoryService.saveMessage(sessionId, userId, "ai", finalText);

                        // ── Step 7：审计日志（对话完成）──────────
                        long duration = System.currentTimeMillis() - startMs;
                        // 从 LangChain4j Response 中提取 Token 用量（streaming 模式下由 onComplete 返回）
                        int inputTokens  = 0;
                        int outputTokens = 0;
                        if (response != null && response.tokenUsage() != null) {
                            inputTokens  = response.tokenUsage().inputTokenCount()  != null
                                    ? response.tokenUsage().inputTokenCount()  : 0;
                            outputTokens = response.tokenUsage().outputTokenCount() != null
                                    ? response.tokenUsage().outputTokenCount() : 0;
                        }
                        auditLogService.logAiChat(userId, sessionId, clientIp, inputTokens, outputTokens);
                        log.info("流式对话完成 userId={} sessionId={} tokens={}/{} 耗时={}ms",
                                userId, sessionId, inputTokens, outputTokens, duration);

                        emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        emitter.complete();
                    } catch (IOException e) {
                        // 发送完成事件失败（客户端断开），直接关闭，不触发 Spring 错误页
                        log.debug("SSE 发送 done 事件失败，客户端可能已断开: {}", e.getMessage());
                        emitter.complete();
                    } finally {
                        // 清除 RAG ThreadLocal 上下文，防止内存泄漏
                        HybridRagContentRetriever.clearContext();
                    }
                })
                .onError(error -> {
                    log.error("流式对话出错 userId={} sessionId={}: {}", userId, sessionId, error.getMessage());
                    auditLogService.log(AuditLogService.EventType.AI_CHAT_BLOCKED,
                            userId, sessionId, clientIp, false,
                            Map.of("error", error.getMessage(), "mode", "stream"));
                    // 不能用 completeWithError：SSE 响应已 committed，异步线程中 request 为 null，
                    // 会触发 "Cannot render error page for request [null]" 警告
                    // 改为通过 SSE error 事件通知前端，再正常 complete
                    try {
                        emitter.send(SseEmitter.event().name("error").data(
                                error.getMessage() != null ? error.getMessage() : "LLM 调用失败，请重试"));
                    } catch (IOException ignore) {
                        // 客户端已断开，忽略
                    } finally {
                        HybridRagContentRetriever.clearContext();
                    }
                    emitter.complete();
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
