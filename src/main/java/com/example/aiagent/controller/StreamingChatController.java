package com.example.aiagent.controller;

import com.example.aiagent.agent.AgentFactory;
import com.example.aiagent.chat.service.ChatHistoryService;
import com.example.aiagent.controller.sse.SseDeltaBuffer;
import com.example.aiagent.kb.service.ChatRagContextService;
import com.example.aiagent.memory.ConversationMemoryService;
import com.example.aiagent.memory.RedisChatMemoryStore;
import com.example.aiagent.memory.UserMemoryService;
import com.example.aiagent.observability.metrics.LlmMetricsRecorder;
import com.example.aiagent.observability.model.LlmCallContext;
import com.example.aiagent.observability.model.TokenPricing;
import com.example.aiagent.observability.service.TokenUsageService;
import com.example.aiagent.rag.retrieval.HybridRagContentRetriever;
import com.example.aiagent.security.filter.OutputContentFilter;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import com.example.aiagent.security.filter.PromptInjectionFilter;
import com.example.aiagent.security.service.AuditLogService;
import com.example.aiagent.security.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final RedisChatMemoryStore redisChatMemoryStore;
    private final ConversationMemoryService conversationMemoryService;
    private final UserMemoryService userMemoryService;
    private final TokenUsageService tokenUsageService;
    private final LlmMetricsRecorder llmMetricsRecorder;
    private final Map<String, PendingStreamChat> pendingStreamChats = new ConcurrentHashMap<>();
    private static final long STREAM_CHAT_TASK_TTL_MS = 120_000L;

    @Value("${chat.stream.flush-interval-ms:50}")
    private long streamFlushIntervalMs;

    @Value("${chat.stream.flush-min-chars:40}")
    private int streamFlushMinChars;

    @Value("${chat.stream.isolated-memory-threshold-chars:8000}")
    private int isolatedMemoryThresholdChars;

    /** 流式模式下 API 不返回 tokenUsage，用 tokenizer 兜底估算 */
    private static final OpenAiTokenizer STREAM_TOKENIZER = new OpenAiTokenizer();

    /**
     * 流式对话（SSE 推送，字符逐步出现）
     *
     * POST /api/v1/chat/stream
     * Body: {"sessionId":"user-123","message":"你好"}
     * Response: {"streamId":"..."}
     *
     * GET /api/v1/chat/stream/{streamId}?token=<jwt>
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
    @PostMapping("/stream")
    public ResponseEntity<Map<String, String>> createStreamChat(
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal String userId) {

        cleanupExpiredStreamChats();
        String streamId = UUID.randomUUID().toString();
        pendingStreamChats.put(streamId, new PendingStreamChat(
                userId,
                request.getOrDefault("sessionId", "unknown"),
                request.getOrDefault("message", ""),
                parseLong(request.get("kbId")),
                request.get("orgId"),
                request.get("model"),
                System.currentTimeMillis()
        ));
        return ResponseEntity.ok(Map.of("streamId", streamId));
    }

    @GetMapping(value = "/stream/{streamId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChatById(
            @PathVariable String streamId,
            @AuthenticationPrincipal String userId,
            HttpServletRequest httpRequest) {

        PendingStreamChat request = pendingStreamChats.remove(streamId);
        if (request == null) {
            return streamErrorEmitter("流式任务不存在或已过期，请重试");
        }
        if (System.currentTimeMillis() - request.createdAtMs() > STREAM_CHAT_TASK_TTL_MS) {
            return streamErrorEmitter("流式任务已过期，请重试");
        }
        if (request.userId() != null && !request.userId().equals(userId)) {
            log.warn("流式任务用户不匹配 creator={} current={}", request.userId(), userId);
            return streamErrorEmitter("无权访问该流式任务");
        }
        return openStreamChat(
                request.sessionId(),
                request.message(),
                request.kbId(),
                request.orgId(),
                request.model(),
                userId,
                httpRequest
        );
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam String sessionId,
            @RequestParam String message,
            @RequestParam(required = false) Long kbId,
            @RequestParam(required = false) String orgId,
            @RequestParam(required = false) String model,
            @AuthenticationPrincipal String userId,
            HttpServletRequest httpRequest) {

        return openStreamChat(sessionId, message, kbId, orgId, model, userId, httpRequest);
    }

    private SseEmitter openStreamChat(String sessionId,
                                      String message,
                                      Long kbId,
                                      String orgId,
                                      String model,
                                      String userId,
                                      HttpServletRequest httpRequest) {
        SseEmitter emitter = new SseEmitter(120_000L);
        AtomicBoolean completed = new AtomicBoolean(false);
        emitter.onCompletion(() -> completed.set(true));
        emitter.onError(error -> completed.set(true));
        String clientIp = getClientIp(httpRequest);
        MDC.put("scenario", "stream_chat");
        final String traceId = MDC.get("traceId");
        String sanitizedMessage;

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
            sanitizedMessage = injectionCheck.sanitizedInput();

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
                    Map.of("messageLength", sanitizedMessage.length(), "mode", "stream"));

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

        // 记忆回灌：Redis 记忆为空时从 DB 历史恢复，避免隔天/重开旧会话失忆
        String memoryKey = ConversationMemoryService.buildMemoryKey(userId, sessionId);
        conversationMemoryService.warmup(memoryKey, sessionId, userId);
        // 取滚动摘要注入 system prompt（长会话压缩后的历史上下文）
        String sessionSummary = conversationMemoryService.getSummaryForPrompt(memoryKey);
        // 取用户长期记忆注入 system prompt（跨会话个性化）
        String userMemory = userMemoryService.getMemoryText(userId);
        String memorySessionId = isolatedMemorySessionId(memoryKey, sanitizedMessage);
        boolean isolatedMemory = !memorySessionId.equals(memoryKey);
        if (isolatedMemory) {
            log.info("长文本流式对话使用隔离记忆 userId={} sessionId={} memorySessionId={} messageLength={}",
                    userId, sessionId, memorySessionId, sanitizedMessage.length());
        }

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
        SseDeltaBuffer deltaBuffer = new SseDeltaBuffer(
                null,
                streamFlushMinChars,
                streamFlushIntervalMs,
                data -> data,
                (eventName, data) -> sendSseEvent(emitter, completed, eventName, data));
        long startMs = System.currentTimeMillis();

        agentFactory.streamingChatAssistantForModel(model).streamChat(memorySessionId, sanitizedMessage, sessionSummary, userMemory)
                .onNext(token -> {
                    // 累积 token 用于后续脱敏
                    fullTextRef.get().append(token);
                    deltaBuffer.append(token);
                })
                .onComplete(response -> {
                    boolean doneSent = false;
                    try {
                        deltaBuffer.flush();
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
                            sendSseEvent(emitter, completed, "replace", outputCheck.filteredContent());
                            log.info("[SECURITY] 流式输出已脱敏，类型：{}", outputCheck.detectedTypes());
                        }

                        String finalText = outputCheck.filteredContent();

                        // ── 先落库再 complete，避免 emitter.complete() 后线程被中断导致落库丢失 ──
                        // ── Step 6：异步持久化聊天记录 ──────────
                        chatHistoryService.saveExchange(sessionId, userId,
                                sanitizedMessage.substring(0, Math.min(sanitizedMessage.length(), 20)), kbId,
                                sanitizedMessage, finalText);
                        // 异步提取用户长期记忆（跨会话事实/偏好）
                        userMemoryService.extractAsync(userId, sanitizedMessage, finalText);

                        // ── Step 7：审计日志 + token 落库（必须在 complete 之前）──────────
                        long duration = System.currentTimeMillis() - startMs;
                        int inputTokens  = 0;
                        int outputTokens = 0;
                        if (response != null && response.tokenUsage() != null) {
                            inputTokens  = response.tokenUsage().inputTokenCount()  != null
                                    ? response.tokenUsage().inputTokenCount()  : 0;
                            outputTokens = response.tokenUsage().outputTokenCount() != null
                                    ? response.tokenUsage().outputTokenCount() : 0;
                        }
                        // 流式 API 默认不返回 tokenUsage，用 tokenizer 兜底估算
                        if (inputTokens == 0 && outputTokens == 0) {
                            String fullTextForEst = fullTextRef.get().toString();
                            outputTokens = STREAM_TOKENIZER.estimateTokenCountInText(fullTextForEst);
                            inputTokens  = STREAM_TOKENIZER.estimateTokenCountInText(sanitizedMessage)
                                    + STREAM_TOKENIZER.estimateTokenCountInText(sessionSummary == null ? "" : sessionSummary)
                                    + STREAM_TOKENIZER.estimateTokenCountInText(userMemory == null ? "" : userMemory)
                                    + 600; // system prompt 粗估
                            log.info("流式 tokenUsage 为 null，兜底估算 input={} output={}", inputTokens, outputTokens);
                        }
                        auditLogService.logAiChat(userId, sessionId, clientIp, inputTokens, outputTokens);
                        // ── Step 8：流式 token 用量落库（流式不走 AOP，手动记录）──
                        recordStreamTokenUsage(traceId, sessionId, userId, model,
                                inputTokens, outputTokens, duration);
                        log.info("流式对话完成 userId={} sessionId={} tokens={}/{} 耗时={}ms",
                                userId, sessionId, inputTokens, outputTokens, duration);

                        sendSseEvent(emitter, completed, "done", "[DONE]");
                        doneSent = true;
                        completeOnce(emitter, completed);
                    } catch (Exception e) {
                        log.debug("SSE 完成阶段失败，客户端可能已断开: {}", e.getMessage());
                        if (!doneSent) {
                            completeOnce(emitter, completed);
                        }
                    } finally {
                        // 清除 RAG ThreadLocal 上下文，防止内存泄漏
                        HybridRagContentRetriever.clearContext();
                        cleanupIsolatedMemory(isolatedMemory, memorySessionId);
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
                    deltaBuffer.flush();
                    try {
                        sendSseEvent(emitter, completed, "error",
                                error.getMessage() != null ? error.getMessage() : "LLM 调用失败，请重试");
                    } finally {
                        HybridRagContentRetriever.clearContext();
                        cleanupIsolatedMemory(isolatedMemory, memorySessionId);
                    }
                    completeOnce(emitter, completed);
                })
                .start();

        return emitter;
    }

    private void cleanupExpiredStreamChats() {
        long now = System.currentTimeMillis();
        pendingStreamChats.entrySet().removeIf(entry ->
                now - entry.getValue().createdAtMs() > STREAM_CHAT_TASK_TTL_MS);
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    private String isolatedMemorySessionId(String sessionId, String message) {
        if (message == null || message.length() <= isolatedMemoryThresholdChars) {
            return sessionId;
        }
        return sessionId + ":long:" + UUID.randomUUID();
    }

    private void cleanupIsolatedMemory(boolean isolatedMemory, String memorySessionId) {
        if (!isolatedMemory) return;
        try {
            redisChatMemoryStore.deleteMessages(memorySessionId);
        } catch (Exception e) {
            log.debug("清理长文本隔离记忆失败 memorySessionId={} reason={}", memorySessionId, e.getMessage());
        }
    }

    private SseEmitter streamErrorEmitter(String message) {
        SseEmitter emitter = new SseEmitter(10_000L);
        AtomicBoolean completed = new AtomicBoolean(false);
        sendSseEvent(emitter, completed, "error", message);
        completeOnce(emitter, completed);
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

    /**
     * 流式对话 token 用量落库（流式不走 AOP 切面，手动记录到 llm_token_usage + Prometheus）
     *
     * @param traceId      链路追踪 ID（请求线程捕获）
     * @param sessionId    会话 ID
     * @param userId       用户 ID
     * @param model        前端传入的模型名（用于推断 modelName 标签）
     * @param inputTokens  输入 token 数
     * @param outputTokens 输出 token 数
     * @param durationMs   耗时（毫秒）
     */
    private void recordStreamTokenUsage(String traceId, String sessionId, String userId, String model,
                                        int inputTokens, int outputTokens, long durationMs) {
        try {
            String modelName = inferModelName(model);
            double costUsd = TokenPricing.of(modelName).calculateCost(inputTokens, outputTokens);
            LlmCallContext ctx = LlmCallContext.builder()
                    .traceId(traceId)
                    .sessionId(sessionId)
                    .userId(userId)
                    .modelName(modelName)
                    .scenario("stream_chat")
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .durationMs(durationMs)
                    .success(true)
                    .costUsd(costUsd)
                    .build();
            llmMetricsRecorder.recordCallComplete(ctx);
            tokenUsageService.saveAsync(ctx);
        } catch (Exception e) {
            log.error("流式 token 落库失败 sessionId={} userId={} tokens={}/{}",
                    sessionId, userId, inputTokens, outputTokens, e);
        }
    }

    /** 从模型名推断modelName 标签，与 AOP 切面推断逻辑保持一致 */
    private static String inferModelName(String model) {
        if (model == null) return "deepseek";
        String lower = model.toLowerCase();
        if (lower.contains("claude")) return "anthropic";
        if (lower.contains("deepseek")) return "deepseek";
        return "deepseek";
    }

    private boolean sendSseEvent(SseEmitter emitter, AtomicBoolean completed, String eventName, String data) {
        if (completed.get()) {
            return false;
        }
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event().data(data);
            if (eventName != null && !eventName.isBlank()) {
                event.name(eventName);
            }
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 事件发送失败 event={} reason={}", eventName, e.getMessage());
            completeOnce(emitter, completed);
            return false;
        }
    }

    private void completeOnce(SseEmitter emitter, AtomicBoolean completed) {
        if (completed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    private record PendingStreamChat(
            String userId,
            String sessionId,
            String message,
            Long kbId,
            String orgId,
            String model,
            long createdAtMs
    ) {}
}
