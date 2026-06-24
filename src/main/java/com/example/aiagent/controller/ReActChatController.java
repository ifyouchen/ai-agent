package com.example.aiagent.controller;

import com.example.aiagent.agent.ReActAgent;
import com.example.aiagent.chat.service.ChatHistoryService;
import com.example.aiagent.controller.sse.SseDeltaBuffer;
import com.example.aiagent.kb.service.ChatRagContextService;
import com.example.aiagent.memory.ConversationMemoryService;
import com.example.aiagent.memory.UserMemoryService;
import com.example.aiagent.observability.metrics.LlmMetricsRecorder;
import com.example.aiagent.observability.model.LlmCallContext;
import com.example.aiagent.observability.model.TokenPricing;
import com.example.aiagent.observability.service.TokenUsageIntentService;
import com.example.aiagent.observability.service.TokenUsageService;
import com.example.aiagent.rag.retrieval.HybridRagContentRetriever;
import com.example.aiagent.security.filter.OutputContentFilter;
import com.example.aiagent.security.filter.PromptInjectionFilter;
import com.example.aiagent.security.service.AuditLogService;
import com.example.aiagent.security.service.RateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ReAct 多步推理接口
 *
 * <p>与普通对话接口的区别：
 * <ul>
 *   <li>普通对话（POST /api/v1/chat）：单次 LLM 调用，适合简单问答</li>
 *   <li>ReAct 推理（POST /api/v1/chat/react）：多轮 Thought→Action→Observation 循环，
 *       适合需要多工具协作的复杂任务</li>
 * </ul>
 *
 * <p>响应体额外包含每轮推理步骤，便于前端展示"思考过程"。
 *
 * <p>安全链路：注入检测 → 限流 → ReAct 推理 → 输出脱敏 → 审计日志
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
public class ReActChatController {

    private final ReActAgent reActAgent;
    private final PromptInjectionFilter promptInjectionFilter;
    private final RateLimitService rateLimitService;
    private final OutputContentFilter outputContentFilter;
    private final AuditLogService auditLogService;
    private final ChatRagContextService chatRagContextService;
    private final ChatHistoryService chatHistoryService;
    private final ConversationMemoryService conversationMemoryService;
    private final UserMemoryService userMemoryService;
    private final TokenUsageService tokenUsageService;
    private final TokenUsageIntentService tokenUsageIntentService;
    private final LlmMetricsRecorder llmMetricsRecorder;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long REACT_STREAM_TASK_TTL_MS = 120_000L;
    /** 流式 ReAct 不返回 tokenUsage，用 tokenizer 兜底估算 */
    private static final OpenAiTokenizer REACT_TOKENIZER = new OpenAiTokenizer();
    private final Map<String, PendingReactStream> pendingReactStreams = new ConcurrentHashMap<>();

    @Value("${chat.stream.flush-interval-ms:50}")
    private long streamFlushIntervalMs;

    @Value("${chat.stream.flush-min-chars:40}")
    private int streamFlushMinChars;

    /**
     * ReAct 专属 flush 配置：pro 模型生成速度慢，使用更小的阈值让 token 更及时地推送到前端。
     * fallback 到 stream 配置，确保未显式配置时仍有合理默认值。
     */
    @Value("${chat.react.flush-interval-ms:${chat.stream.flush-interval-ms:20}}")
    private long reactFlushIntervalMs;

    @Value("${chat.react.flush-min-chars:${chat.stream.flush-min-chars:3}}")
    private int reactFlushMinChars;

    /**
     * SSE 流式推理专属线程池（有界，防止高并发下 OOM）
     *
     * <p>通过 AppConfig#sseTaskExecutor Bean 注入，核心10/最大50/队列200，
     * 替代原来的 {@code Executors.newCachedThreadPool()}（无界，高并发风险）。
     */
    private final Executor sseExecutor;

    public ReActChatController(
            ReActAgent reActAgent,
            PromptInjectionFilter promptInjectionFilter,
            RateLimitService rateLimitService,
            OutputContentFilter outputContentFilter,
            AuditLogService auditLogService,
            ChatRagContextService chatRagContextService,
            ChatHistoryService chatHistoryService,
            ConversationMemoryService conversationMemoryService,
            UserMemoryService userMemoryService,
            TokenUsageService tokenUsageService,
            TokenUsageIntentService tokenUsageIntentService,
            LlmMetricsRecorder llmMetricsRecorder,
            @Qualifier("sseTaskExecutor") Executor sseExecutor) {
        this.reActAgent = reActAgent;
        this.promptInjectionFilter = promptInjectionFilter;
        this.rateLimitService = rateLimitService;
        this.outputContentFilter = outputContentFilter;
        this.auditLogService = auditLogService;
        this.chatRagContextService = chatRagContextService;
        this.chatHistoryService = chatHistoryService;
        this.conversationMemoryService = conversationMemoryService;
        this.userMemoryService = userMemoryService;
        this.tokenUsageService = tokenUsageService;
        this.tokenUsageIntentService = tokenUsageIntentService;
        this.llmMetricsRecorder = llmMetricsRecorder;
        this.sseExecutor = sseExecutor;
    }

    /**
     * ReAct 多步推理对话
     *
     * <pre>
     * POST /api/v1/chat/react
     * Header: Authorization: Bearer &lt;token&gt;
     * Body: {"sessionId": "user-123", "message": "查询用户U001的所有订单，统计总金额"}
     *
     * 响应：
     * {
     *   "answer": "最终答案文本",
     *   "iterations": 3,
     *   "durationMs": 4521,
     *   "steps": [
     *     {"iteration": 1, "thought": "需要先查...", "toolName": "queryUserOrders",
     *      "toolArgs": "{\"userId\":\"U001\"}", "observation": "查到3笔订单..."},
     *     ...
     *   ]
     * }
     * </pre>
     */
    @PostMapping("/react")
    public ResponseEntity<?> reactChat(
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal String userId,
            HttpServletRequest httpRequest) {

        String sessionId = request.getOrDefault("sessionId", "unknown");
        String message   = request.getOrDefault("message", "");
        String model     = request.get("model");
        String orgId     = request.get("orgId");
        String clientIp  = getClientIp(httpRequest);

        MDC.put("scenario", "react_chat");
        MDC.put("userId", userId != null ? userId : "anonymous");
        MDC.put("sessionId", sessionId != null ? sessionId : "unknown");

        try {
            // ── Step 1：Prompt 注入检测 ────────────────────
            PromptInjectionFilter.FilterResult injectionCheck = promptInjectionFilter.check(message);
            if (injectionCheck.blocked()) {
                log.warn("[ReAct] Prompt 注入被拦截 userId={}", userId);
                auditLogService.logSecurityBlock(
                        AuditLogService.EventType.PROMPT_INJECTION_DETECTED,
                        userId, clientIp, injectionCheck.reason());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", injectionCheck.reason()));
            }

            // ── Step 2：限流校验 ──────────────────────────
            RateLimitService.RateLimitResult rateLimit = rateLimitService.tryAcquire(userId);
            if (!rateLimit.allowed()) {
                log.warn("[ReAct] 限流触发 userId={}", userId);
                auditLogService.logSecurityBlock(
                        AuditLogService.EventType.RATE_LIMIT_TRIGGERED,
                        userId, clientIp, rateLimit.reason());
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", String.valueOf(rateLimit.retryAfterSeconds()))
                        .body(Map.of("error", rateLimit.reason()));
            }

            // ── Step 3：审计日志（请求开始）──────────────
            auditLogService.log(AuditLogService.EventType.AI_CHAT_REQUEST,
                    userId, sessionId, clientIp, true,
                    Map.of("messageLength", injectionCheck.sanitizedInput().length(), "mode", "react"));

            java.util.Optional<TokenUsageIntentService.Result> usageResult =
                    tokenUsageIntentService.resolve(userId, injectionCheck.sanitizedInput());
            if (usageResult.isPresent()) {
                String userText = injectionCheck.sanitizedInput();
                String aiText = usageResult.get().answer();
                chatHistoryService.saveExchange(sessionId, userId,
                        userText.substring(0, Math.min(userText.length(), 20)), parseLong(request.get("kbId")),
                        userText, aiText);
                auditLogService.logAiChat(userId, sessionId, clientIp, 0, 0);
                return ResponseEntity.ok(Map.of(
                        "sessionId", sessionId,
                        "answer", aiText,
                        "iterations", 0,
                        "durationMs", 0,
                        "steps", List.of()
                ));
            }

            // ── Step 4：ReAct 多步推理（设置 RAG 上下文后执行）──
            String kbIdStr = request.get("kbId");
            Long kbId = null;
            if (kbIdStr != null && !kbIdStr.isBlank()) {
                try { kbId = Long.parseLong(kbIdStr); } catch (NumberFormatException ignore) {}
            }
            HybridRagContentRetriever.RetrievalContext ragContext =
                    chatRagContextService.resolve(userId, orgId, kbId);
            if (ragContext != null) {
                HybridRagContentRetriever.setContext(ragContext);
            }
            ReActAgent.ReActResult result;
            try {
                String memoryKey = ConversationMemoryService.buildMemoryKey(userId, sessionId);
                conversationMemoryService.warmup(memoryKey, sessionId, userId);
                result = reActAgent.execute(injectionCheck.sanitizedInput(), memoryKey, model,
                        ragContext != null ? ragContext.tenantId() : null,
                        ragContext != null ? ragContext.kbId() : null);
            } finally {
                HybridRagContentRetriever.clearContext();
            }

            // ── Step 5：输出内容脱敏 ──────────────────────
            OutputContentFilter.FilterResult outputCheck = outputContentFilter.filter(result.answer());
            if (!outputCheck.detectedTypes().isEmpty()) {
                auditLogService.logSecurityBlock(
                        AuditLogService.EventType.OUTPUT_SENSITIVE_FILTERED,
                        userId, clientIp,
                        "ReAct 输出脱敏，检测到：" + outputCheck.detectedTypes());
            }
            String userText = injectionCheck.sanitizedInput();
            String aiText = outputCheck.filteredContent();
            reActAgent.rememberExchangeAsync(
                    ConversationMemoryService.buildMemoryKey(userId, sessionId), userText, aiText);
            chatHistoryService.saveExchange(sessionId, userId,
                    userText.substring(0, Math.min(userText.length(), 20)), kbId,
                    userText, aiText);
            // 异步提取用户长期记忆（跨会话事实/偏好）
            userMemoryService.extractAsync(userId, userText, aiText);

            // ── Step 6：审计日志（完成）──────────────────
            auditLogService.logAiChat(userId, sessionId, clientIp,
                    result.inputTokens(), result.outputTokens());
            if (model != null && !model.isBlank()) {
                recordReactTokenUsage(MDC.get("traceId"), sessionId, userId, model,
                        result.inputTokens(), result.outputTokens(), result.durationMs(),
                        userText, aiText);
            }
            log.info("[ReAct] 完成 userId={} iterations={} durationMs={} tokens={}/{}",
                    userId, result.iterations(), result.durationMs(),
                    result.inputTokens(), result.outputTokens());

            // 构建响应（包含推理步骤，便于前端展示思考过程）
            List<Map<String, Object>> stepList = result.steps().stream()
                    .map(s -> Map.<String, Object>of(
                            "iteration",   s.iteration(),
                            "thought",     s.thought() != null ? s.thought() : "",
                            "toolName",    s.toolName() != null ? s.toolName() : "",
                            "toolArgs",    s.toolArgs() != null ? s.toolArgs() : "",
                            "observation", s.observation() != null ? s.observation() : ""
                    ))
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "sessionId",  sessionId,
                    "answer",     aiText,
                    "iterations", result.iterations(),
                    "durationMs", result.durationMs(),
                    "steps",      stepList
            ));

        } catch (IllegalArgumentException e) {
            log.warn("[ReAct] 知识库上下文无效 userId={} orgId={} reason={}",
                    userId, orgId, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } finally {
            MDC.remove("scenario");
            MDC.remove("sessionId");
            MDC.remove("userId");
        }
    }

    /**
     * ReAct 多步推理流式接口（SSE）
     *
     * <p>与 POST /react 的区别：每完成一个推理步骤立即通过 SSE 推送给前端，
     * 前端可实时看到思考过程，而不必等全部完成。
     *
     * <pre>
     * POST /api/v1/chat/react/stream
     * Body: {"sessionId": "user-123", "message": "..."}
     * Response: {"streamId": "..."}
     *
     * GET /api/v1/chat/react/stream/{streamId}?token=&lt;jwt&gt;
     *
     * SSE 事件类型：
     *   status          - 当前阶段提示 JSON：{message}
     *   reasoning-start - 可见推理摘要开始 JSON：{iteration}
     *   reasoning-token - 可见推理摘要 token JSON：{iteration, token}
     *   reasoning-done  - 可见推理摘要完成 JSON：{iteration, text}
     *   tool-call       - 工具调用 JSON：{iteration, toolName, toolArgs}
     *   tool-result     - 工具结果 JSON：{iteration, toolName, observation}
     *   answer-start    - 最终答案开始 JSON：{iteration}
     *   answer-token    - 最终答案 token 文本
     *   answer          - 最终完整答案 JSON：{answer, iterations, durationMs}
     *   step            - 兼容旧前端的完整步骤 JSON
     *   react-error     - 业务错误 JSON：{message, code}
     *   error           - 兼容旧前端的错误文本
     *   done            - 结束标识
     * </pre>
     */
    @PostMapping("/react/stream")
    public ResponseEntity<Map<String, String>> createReactStream(
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal String userId) {

        cleanupExpiredReactStreams();
        String streamId = UUID.randomUUID().toString();
        pendingReactStreams.put(streamId, new PendingReactStream(
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

    @GetMapping(value = "/react/stream/{streamId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter reactStreamById(
            @PathVariable String streamId,
            @AuthenticationPrincipal String userId,
            HttpServletRequest httpRequest) {

        PendingReactStream request = pendingReactStreams.remove(streamId);
        if (request == null) {
            return reactStreamErrorEmitter("深度推理任务不存在或已过期，请重试", "stream_not_found");
        }
        if (System.currentTimeMillis() - request.createdAtMs() > REACT_STREAM_TASK_TTL_MS) {
            return reactStreamErrorEmitter("深度推理任务已过期，请重试", "stream_expired");
        }
        if (request.userId() != null && !request.userId().equals(userId)) {
            log.warn("[ReAct-Stream] streamId 用户不匹配 creator={} current={}", request.userId(), userId);
            return reactStreamErrorEmitter("无权访问该深度推理任务", "stream_forbidden");
        }
        return openReactStream(
                request.sessionId(),
                request.message(),
                request.kbId(),
                request.orgId(),
                request.model(),
                userId,
                httpRequest
        );
    }

    @GetMapping(value = "/react/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter reactStream(
            @RequestParam String sessionId,
            @RequestParam String message,
            @RequestParam(required = false) Long kbId,
            @RequestParam(required = false) String orgId,
            @RequestParam(required = false) String model,
            @AuthenticationPrincipal String userId,
            HttpServletRequest httpRequest) {

        return openReactStream(sessionId, message, kbId, orgId, model, userId, httpRequest);
    }

    private SseEmitter openReactStream(String sessionId,
                                      String message,
                                      Long kbId,
                                      String orgId,
                                      String model,
                                      String userId,
                                      HttpServletRequest httpRequest) {
        SseEmitter emitter = new SseEmitter(300_000L); // ReAct 可能较慢，超时设 5 分钟
        AtomicBoolean completed = new AtomicBoolean(false);
        emitter.onCompletion(() -> completed.set(true));
        emitter.onError(error -> {
            completed.set(true);
            log.debug("[ReAct-Stream] SSE 连接异常结束 userId={} sessionId={}: {}",
                    userId, sessionId, error.getMessage());
        });
        emitter.onTimeout(() -> {
            log.warn("[ReAct-Stream] SSE 超时 userId={} sessionId={} model={} kbId={}",
                    userId, sessionId, model, kbId);
            sendReactErrorAndComplete(emitter, completed,
                    "深度推理超时，请稍后重试", "timeout");
        });
        String clientIp = getClientIp(httpRequest);
        final String traceId = MDC.get("traceId");

        sendReactStatus(emitter, completed, "请求已接收");

        // ── Step 1：Prompt 注入检测 ────────────────────
        PromptInjectionFilter.FilterResult injectionCheck = promptInjectionFilter.check(message);
        if (injectionCheck.blocked()) {
            log.warn("[ReAct-Stream] Prompt 注入被拦截 userId={}", userId);
            auditLogService.logSecurityBlock(
                    AuditLogService.EventType.PROMPT_INJECTION_DETECTED,
                    userId, clientIp, injectionCheck.reason());
            sendReactErrorAndComplete(emitter, completed,
                    injectionCheck.reason(), "prompt_blocked");
            return emitter;
        }

        // ── Step 2：限流校验 ──────────────────────────
        RateLimitService.RateLimitResult rateLimit = rateLimitService.tryAcquire(userId);
        if (!rateLimit.allowed()) {
            log.warn("[ReAct-Stream] 限流触发 userId={}", userId);
            auditLogService.logSecurityBlock(
                    AuditLogService.EventType.RATE_LIMIT_TRIGGERED,
                    userId, clientIp, rateLimit.reason());
            sendReactErrorAndComplete(emitter, completed,
                    rateLimit.reason(), "rate_limited");
            return emitter;
        }

        // ── Step 3：审计日志（请求开始）──────────────
        auditLogService.log(AuditLogService.EventType.AI_CHAT_REQUEST,
                userId, sessionId, clientIp, true,
                Map.of("messageLength", injectionCheck.sanitizedInput().length(), "mode", "react-stream"));

        java.util.Optional<TokenUsageIntentService.Result> usageResult =
                tokenUsageIntentService.resolve(userId, injectionCheck.sanitizedInput());
        if (usageResult.isPresent()) {
            String userText = injectionCheck.sanitizedInput();
            String aiText = usageResult.get().answer();
            chatHistoryService.saveExchange(sessionId, userId,
                    userText.substring(0, Math.min(userText.length(), 20)), kbId,
                    userText, aiText);
            auditLogService.logAiChat(userId, sessionId, clientIp, 0, 0);
            sendReactJsonEvent(emitter, completed, "answer-start", Map.of("iteration", 0));
            sendReactJsonEvent(emitter, completed, "answer", Map.of(
                    "answer", aiText,
                    "iterations", 0,
                    "durationMs", 0
            ));
            sendSseEvent(emitter, completed, "done", "[DONE]");
            completeOnce(emitter, completed);
            return emitter;
        }

        // ── Step 4：异步线程执行 ReAct 推理 ──────────
        final String sanitizedMessage = injectionCheck.sanitizedInput();
        final String memoryKey = ConversationMemoryService.buildMemoryKey(userId, sessionId);
        final HybridRagContentRetriever.RetrievalContext ragContext;
        try {
            sendReactStatus(emitter, completed, "知识库校验中");
            ragContext = chatRagContextService.resolve(userId, orgId, kbId);
        } catch (IllegalArgumentException e) {
            log.warn("[ReAct-Stream] 知识库上下文无效 userId={} orgId={} kbId={} reason={}",
                    userId, orgId, kbId, e.getMessage());
            sendReactErrorAndComplete(emitter, completed,
                    e.getMessage(), "kb_forbidden");
            return emitter;
        }
        try {
        sseExecutor.execute(() -> {
            if (traceId != null) {
                MDC.put("traceId", traceId);
            }
            MDC.put("scenario", "react_stream");
            MDC.put("userId", userId != null ? userId : "anonymous");
            MDC.put("sessionId", sessionId != null ? sessionId : "unknown");
            long startMs = System.currentTimeMillis();
            // 异步线程中设置 RAG 上下文（ThreadLocal 是线程级别的）
            if (ragContext != null) {
                HybridRagContentRetriever.setContext(ragContext);
            }
            class ReactSseCallback implements ReActAgent.ReActStreamCallback {
                private SseDeltaBuffer reasoningBuffer;
                private int reasoningIteration;
                private final SseDeltaBuffer answerBuffer = new SseDeltaBuffer(
                        "answer-token",
                        reactFlushMinChars,
                        reactFlushIntervalMs,
                        data -> data,
                        (eventName, data) -> sendSseEvent(emitter, completed, eventName, data));

                private SseDeltaBuffer newReasoningBuffer(int iteration) {
                    reasoningIteration = iteration;
                    // reasoning-start 事件已告知前端当前轮次，token 本身只需发原始文本。
                    // 去掉 JSON 封装可以减少每次 flush 的序列化开销，前端用 currentReasoningIteration 追踪轮次。
                    return new SseDeltaBuffer(
                            "reasoning-token",
                            reactFlushMinChars,
                            reactFlushIntervalMs,
                            data -> data,
                            (eventName, data) -> sendSseEvent(emitter, completed, eventName, data));
                }

                void flushDeltas() {
                    if (reasoningBuffer != null) {
                        reasoningBuffer.flush();
                    }
                    answerBuffer.flush();
                }

                @Override
                public void onReasoningStart(int iteration) {
                    if (reasoningBuffer != null) {
                        reasoningBuffer.flush();
                    }
                    reasoningBuffer = newReasoningBuffer(iteration);
                    sendReactJsonEvent(emitter, completed, "reasoning-start",
                            Map.of("iteration", iteration));
                }

                @Override
                public void onReasoningToken(int iteration, String token) {
                    if (reasoningBuffer == null || reasoningIteration != iteration) {
                        if (reasoningBuffer != null) {
                            reasoningBuffer.flush();
                        }
                        reasoningBuffer = newReasoningBuffer(iteration);
                    }
                    reasoningBuffer.append(token);
                }

                @Override
                public void onReasoningDone(int iteration, String text) {
                    if (reasoningBuffer != null) {
                        reasoningBuffer.flush();
                    }
                    sendReactJsonEvent(emitter, completed, "reasoning-done", Map.of(
                            "iteration", iteration,
                            "text", text != null ? text : ""
                    ));
                }

                @Override
                public void onToolCall(ReActAgent.ReActStep step) {
                    flushDeltas();
                    sendReactJsonEvent(emitter, completed, "tool-call", Map.of(
                            "iteration", step.iteration(),
                            "toolName", step.toolName() != null ? step.toolName() : "",
                            "toolArgs", step.toolArgs() != null ? step.toolArgs() : ""
                    ));
                }

                @Override
                public void onToolResult(ReActAgent.ReActStep step) {
                    flushDeltas();
                    sendReactJsonEvent(emitter, completed, "tool-result", Map.of(
                            "iteration", step.iteration(),
                            "toolName", step.toolName() != null ? step.toolName() : "",
                            "observation", step.observation() != null ? step.observation() : ""
                    ));
                    sendLegacyStepEvent(emitter, completed, step);
                }

                @Override
                public void onAnswerStart(int iteration) {
                    flushDeltas();
                    sendReactJsonEvent(emitter, completed, "answer-start",
                            Map.of("iteration", iteration));
                }

                @Override
                public void onAnswerToken(String token) {
                    answerBuffer.append(token);
                }
            }
            ReactSseCallback reactCallback = new ReactSseCallback();
            try {
                sendReactStatus(emitter, completed, "模型推理中");
                conversationMemoryService.warmup(memoryKey, sessionId, userId);
                ReActAgent.ReActResult result = reActAgent.executeStreamingWithCallback(
                        sanitizedMessage, memoryKey, model,
                        ragContext != null ? ragContext.tenantId() : null,
                        ragContext != null ? ragContext.kbId() : null,
                        reactCallback);
                reactCallback.flushDeltas();

                // ── Step 5：输出脱敏 ──────────────────
                sendReactStatus(emitter, completed, "整理答案中");
                OutputContentFilter.FilterResult outputCheck = outputContentFilter.filter(result.answer());
                if (!outputCheck.detectedTypes().isEmpty()) {
                    auditLogService.logSecurityBlock(
                            AuditLogService.EventType.OUTPUT_SENSITIVE_FILTERED,
                            userId, clientIp,
                            "ReAct-Stream 输出脱敏，检测到：" + outputCheck.detectedTypes());
                    // 通知前端替换已显示的最终答案
                    sendSseEvent(emitter, completed, "replace-answer",
                            MAPPER.writeValueAsString(Map.of("answer", outputCheck.filteredContent())));
                }
                String aiText = outputCheck.filteredContent();
                sendReactJsonEvent(emitter, completed, "answer", Map.of(
                        "answer", aiText,
                        "iterations", result.iterations(),
                        "durationMs", result.durationMs()
                ));

                sendSseEvent(emitter, completed, "done", "[DONE]");
                completeOnce(emitter, completed);

                reActAgent.rememberExchangeAsync(memoryKey, sanitizedMessage, aiText);
                chatHistoryService.saveExchange(sessionId, userId,
                        sanitizedMessage.substring(0, Math.min(sanitizedMessage.length(), 20)), kbId,
                        sanitizedMessage, aiText);
                // 异步提取用户长期记忆（跨会话事实/偏好）
                userMemoryService.extractAsync(userId, sanitizedMessage, aiText);

                // ── Step 6：审计日志 ──────────────────
                long duration = System.currentTimeMillis() - startMs;
                auditLogService.logAiChat(userId, sessionId, clientIp,
                        result.inputTokens(), result.outputTokens());
                recordReactTokenUsage(MDC.get("traceId"), sessionId, userId, model,
                        result.inputTokens(), result.outputTokens(), duration,
                        sanitizedMessage, aiText);
                log.info("[ReAct-Stream] 完成 userId={} iterations={} durationMs={} tokens={}/{}",
                        userId, result.iterations(), duration,
                        result.inputTokens(), result.outputTokens());

            } catch (Exception e) {
                reactCallback.flushDeltas();
                long duration = System.currentTimeMillis() - startMs;
                log.error("[ReAct-Stream] 推理出错 userId={} sessionId={} model={} kbId={} durationMs={}",
                        userId, sessionId, model, kbId, duration, e);
                // 不用 completeWithError：SSE 响应已 committed，异步线程中 request 为 null，
                // 会触发 "Cannot render error page for request [null]" 警告
                ReactError error = mapReactError(e);
                sendReactErrorAndComplete(emitter, completed, error.message(), error.code());
            } finally {
                HybridRagContentRetriever.clearContext();
                MDC.remove("scenario");
                MDC.remove("sessionId");
                MDC.remove("userId");
                MDC.remove("traceId");
            }
        });
        } catch (RejectedExecutionException ex) {
            // 线程池已满，快速失败并向客户端发送错误事件
            log.warn("[ReAct-Stream] SSE 线程池已满，拒绝请求 userId={}", userId);
            sendReactErrorAndComplete(emitter, completed,
                    "服务繁忙，请稍后重试", "busy");
        }

        return emitter;
    }

    private void cleanupExpiredReactStreams() {
        long now = System.currentTimeMillis();
        pendingReactStreams.entrySet().removeIf(entry ->
                now - entry.getValue().createdAtMs() > REACT_STREAM_TASK_TTL_MS);
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    private SseEmitter reactStreamErrorEmitter(String message, String code) {
        SseEmitter emitter = new SseEmitter(10_000L);
        AtomicBoolean completed = new AtomicBoolean(false);
        try {
            sseExecutor.execute(() -> sendReactErrorAndComplete(emitter, completed, message, code));
        } catch (RejectedExecutionException ex) {
            sendReactErrorAndComplete(emitter, completed, message, code);
        }
        return emitter;
    }

    private void sendReactStatus(SseEmitter emitter, AtomicBoolean completed, String message) {
        try {
            sendSseEvent(emitter, completed, "status", MAPPER.writeValueAsString(Map.of("message", message)));
        } catch (IOException e) {
            log.debug("[ReAct-Stream] status 序列化失败: {}", e.getMessage());
            completeOnce(emitter, completed);
        }
    }

    private void sendReactJsonEvent(SseEmitter emitter, AtomicBoolean completed,
                                    String eventName, Map<String, ?> payload) {
        sendSseEvent(emitter, completed, eventName, toJsonPayload(payload));
    }

    private String toJsonPayload(Map<String, ?> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (IOException e) {
            log.debug("[ReAct-Stream] JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    private void sendLegacyStepEvent(SseEmitter emitter, AtomicBoolean completed, ReActAgent.ReActStep step) {
        sendReactJsonEvent(emitter, completed, "step", Map.of(
                "iteration", step.iteration(),
                "thought", step.thought() != null ? step.thought() : "",
                "toolName", step.toolName() != null ? step.toolName() : "",
                "toolArgs", step.toolArgs() != null ? step.toolArgs() : "",
                "observation", step.observation() != null ? step.observation() : ""
        ));
    }

    private void sendReactErrorAndComplete(SseEmitter emitter, AtomicBoolean completed, String message) {
        sendReactErrorAndComplete(emitter, completed, message, "internal_error");
    }

    private void sendReactErrorAndComplete(SseEmitter emitter, AtomicBoolean completed,
                                           String message, String code) {
        String safeCode = code != null && !code.isBlank() ? code : "internal_error";
        String safeMessage = message != null && !message.isBlank()
                ? message
                : "深度推理失败，请稍后重试";
        try {
            String payload = MAPPER.writeValueAsString(Map.of(
                    "message", safeMessage,
                    "code", safeCode
            ));
            sendSseEvent(emitter, completed, "react-error", payload);
        } catch (IOException e) {
            log.debug("[ReAct-Stream] react-error 序列化失败: {}", e.getMessage());
        }

        // 兼容旧前端：旧代码监听 error 事件并直接读取 ev.data。
        sendSseEvent(emitter, completed, "error", safeMessage);
        completeOnce(emitter, completed);
    }

    private boolean sendSseEvent(SseEmitter emitter, AtomicBoolean completed,
                                 String eventName, String data) {
        if (completed.get()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("[ReAct-Stream] SSE 事件发送失败 event={} reason={}", eventName, e.getMessage());
            completeOnce(emitter, completed);
            return false;
        }
    }

    private void completeOnce(SseEmitter emitter, AtomicBoolean completed) {
        if (completed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    private ReactError mapReactError(Throwable error) {
        String message = collectExceptionMessages(error).toLowerCase();
        if (message.contains("account_overdue")) {
            return new ReactError(
                    "模型/Embedding 服务账号欠费或不可用，请检查 API Key 或账户余额",
                    "account_overdue");
        }
        return new ReactError("深度推理失败，请稍后重试", "internal_error");
    }

    private String collectExceptionMessages(Throwable error) {
        StringBuilder sb = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return sb.toString();
    }

    private record ReactError(String message, String code) {}

    private record PendingReactStream(
            String userId,
            String sessionId,
            String message,
            Long kbId,
            String orgId,
            String model,
            long createdAtMs
    ) {}

    /**
     * ReAct 流式 token 用量落库（流式不走 AOP 切面，手动记录到 llm_token_usage + Prometheus）
     */
    private void recordReactTokenUsage(String traceId, String sessionId, String userId, String model,
                                       int inputTokens, int outputTokens, long durationMs,
                                       String userText, String aiText) {
        try {
            // 流式 ReAct 的 tokenUsage 始终为 null，用 tokenizer 兜底估算
            if (inputTokens == 0 && outputTokens == 0) {
                outputTokens = REACT_TOKENIZER.estimateTokenCountInText(aiText == null ? "" : aiText);
                inputTokens  = REACT_TOKENIZER.estimateTokenCountInText(userText == null ? "" : userText) + 800;
            }
            String modelName = inferReactModelName(model);
            double costUsd = TokenPricing.of(modelName).calculateCost(inputTokens, outputTokens);
            LlmCallContext ctx = LlmCallContext.builder()
                    .traceId(traceId)
                    .sessionId(sessionId)
                    .userId(userId)
                    .modelName(modelName)
                    .scenario("react_stream")
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .durationMs(durationMs)
                    .success(true)
                    .costUsd(costUsd)
                    .build();
            llmMetricsRecorder.recordCallComplete(ctx);
            tokenUsageService.saveAsync(ctx);
        } catch (Exception e) {
            log.debug("[ReAct] token 落库失败 sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    private static String inferReactModelName(String model) {
        if (model == null || model.isBlank()) return "deepseek-v4-pro";
        return model.trim();
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

