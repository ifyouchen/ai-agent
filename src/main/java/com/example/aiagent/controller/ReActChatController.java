package com.example.aiagent.controller;

import com.example.aiagent.agent.ReActAgent;
import com.example.aiagent.kb.service.ChatRagContextService;
import com.example.aiagent.rag.retrieval.HybridRagContentRetriever;
import com.example.aiagent.security.filter.OutputContentFilter;
import com.example.aiagent.security.filter.PromptInjectionFilter;
import com.example.aiagent.security.service.AuditLogService;
import com.example.aiagent.security.service.RateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            @Qualifier("sseTaskExecutor") Executor sseExecutor) {
        this.reActAgent = reActAgent;
        this.promptInjectionFilter = promptInjectionFilter;
        this.rateLimitService = rateLimitService;
        this.outputContentFilter = outputContentFilter;
        this.auditLogService = auditLogService;
        this.chatRagContextService = chatRagContextService;
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
                result = reActAgent.execute(injectionCheck.sanitizedInput(), sessionId, model,
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

            // ── Step 6：审计日志（完成）──────────────────
            auditLogService.logAiChat(userId, sessionId, clientIp, 0, 0);
            log.info("[ReAct] 完成 userId={} iterations={} durationMs={}",
                    userId, result.iterations(), result.durationMs());

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
                    "answer",     outputCheck.filteredContent(),
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
        }
    }

    /**
     * ReAct 多步推理流式接口（SSE）
     *
     * <p>与 POST /react 的区别：每完成一个推理步骤立即通过 SSE 推送给前端，
     * 前端可实时看到思考过程，而不必等全部完成。
     *
     * <pre>
     * GET /api/v1/chat/react/stream?sessionId=user-123&message=...&token=&lt;jwt&gt;
     *
     * SSE 事件类型：
     *   step  - 每步推理（工具调用）JSON：{iteration, thought, toolName, toolArgs, observation}
     *   answer- 最终答案 JSON：{answer, iterations, durationMs}
     *   error - 错误信息文本
     *   done  - 结束标识
     * </pre>
     */
    @GetMapping(value = "/react/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter reactStream(
            @RequestParam String sessionId,
            @RequestParam String message,
            @RequestParam(required = false) Long kbId,
            @RequestParam(required = false) String orgId,
            @RequestParam(required = false) String model,
            @AuthenticationPrincipal String userId,
            HttpServletRequest httpRequest) {

        SseEmitter emitter = new SseEmitter(300_000L); // ReAct 可能较慢，超时设 5 分钟
        String clientIp = getClientIp(httpRequest);

        // ── Step 1：Prompt 注入检测 ────────────────────
        PromptInjectionFilter.FilterResult injectionCheck = promptInjectionFilter.check(message);
        if (injectionCheck.blocked()) {
            log.warn("[ReAct-Stream] Prompt 注入被拦截 userId={}", userId);
            auditLogService.logSecurityBlock(
                    AuditLogService.EventType.PROMPT_INJECTION_DETECTED,
                    userId, clientIp, injectionCheck.reason());
            try {
                emitter.send(SseEmitter.event().name("error").data(injectionCheck.reason()));
                emitter.complete();
            } catch (IOException ignore) {}
            return emitter;
        }

        // ── Step 2：限流校验 ──────────────────────────
        RateLimitService.RateLimitResult rateLimit = rateLimitService.tryAcquire(userId);
        if (!rateLimit.allowed()) {
            log.warn("[ReAct-Stream] 限流触发 userId={}", userId);
            auditLogService.logSecurityBlock(
                    AuditLogService.EventType.RATE_LIMIT_TRIGGERED,
                    userId, clientIp, rateLimit.reason());
            try {
                emitter.send(SseEmitter.event().name("error").data(rateLimit.reason()));
                emitter.complete();
            } catch (IOException ignore) {}
            return emitter;
        }

        // ── Step 3：审计日志（请求开始）──────────────
        auditLogService.log(AuditLogService.EventType.AI_CHAT_REQUEST,
                userId, sessionId, clientIp, true,
                Map.of("messageLength", injectionCheck.sanitizedInput().length(), "mode", "react-stream"));

        // ── Step 4：异步线程执行 ReAct 推理 ──────────
        final String sanitizedMessage = injectionCheck.sanitizedInput();
        final HybridRagContentRetriever.RetrievalContext ragContext;
        try {
            ragContext = chatRagContextService.resolve(userId, orgId, kbId);
        } catch (IllegalArgumentException e) {
            log.warn("[ReAct-Stream] 知识库上下文无效 userId={} orgId={} kbId={} reason={}",
                    userId, orgId, kbId, e.getMessage());
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (IOException ignore) {}
            emitter.complete();
            return emitter;
        }
        try {
        sseExecutor.execute(() -> {
            MDC.put("scenario", "react_stream");
            MDC.put("userId", userId);
            long startMs = System.currentTimeMillis();
            // 异步线程中设置 RAG 上下文（ThreadLocal 是线程级别的）
            if (ragContext != null) {
                HybridRagContentRetriever.setContext(ragContext);
            }
            try {
                ReActAgent.ReActResult result = reActAgent.executeWithCallback(
                        sanitizedMessage, sessionId, model,
                        ragContext != null ? ragContext.tenantId() : null,
                        ragContext != null ? ragContext.kbId() : null,
                        (step, isFinal) -> {
                            try {
                                if (isFinal) {
                                    // 最终答案通过 answer 事件推送
                                    long dur = System.currentTimeMillis() - startMs;
                                    String answerJson = MAPPER.writeValueAsString(Map.of(
                                            "answer", step.thought() != null ? step.thought() : "",
                                            "iterations", step.iteration(),
                                            "durationMs", dur
                                    ));
                                    emitter.send(SseEmitter.event().name("answer").data(answerJson));
                                } else {
                                    // 推理步骤通过 step 事件推送
                                    String stepJson = MAPPER.writeValueAsString(Map.of(
                                            "iteration",   step.iteration(),
                                            "thought",     step.thought()      != null ? step.thought()      : "",
                                            "toolName",    step.toolName()     != null ? step.toolName()     : "",
                                            "toolArgs",    step.toolArgs()     != null ? step.toolArgs()     : "",
                                            "observation", step.observation()  != null ? step.observation()  : ""
                                    ));
                                    emitter.send(SseEmitter.event().name("step").data(stepJson));
                                }
                            } catch (IOException e) {
                                // 客户端断开，静默关闭，不用 completeWithError
                                log.debug("[ReAct-Stream] SSE 推送失败，客户端可能已断开: {}", e.getMessage());
                                emitter.complete();
                            }
                        });

                // ── Step 5：输出脱敏 ──────────────────
                OutputContentFilter.FilterResult outputCheck = outputContentFilter.filter(result.answer());
                if (!outputCheck.detectedTypes().isEmpty()) {
                    auditLogService.logSecurityBlock(
                            AuditLogService.EventType.OUTPUT_SENSITIVE_FILTERED,
                            userId, clientIp,
                            "ReAct-Stream 输出脱敏，检测到：" + outputCheck.detectedTypes());
                    // 通知前端替换已显示的最终答案
                    emitter.send(SseEmitter.event().name("replace-answer")
                            .data(MAPPER.writeValueAsString(Map.of("answer", outputCheck.filteredContent()))));
                }

                // ── Step 6：审计日志 ──────────────────
                long duration = System.currentTimeMillis() - startMs;
                auditLogService.logAiChat(userId, sessionId, clientIp, 0, 0);
                log.info("[ReAct-Stream] 完成 userId={} iterations={} durationMs={}",
                        userId, result.iterations(), duration);

                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();

            } catch (Exception e) {
                log.error("[ReAct-Stream] 推理出错 userId={}: {}", userId, e.getMessage());
                // 不用 completeWithError：SSE 响应已 committed，异步线程中 request 为 null，
                // 会触发 "Cannot render error page for request [null]" 警告
                try {
                    emitter.send(SseEmitter.event().name("error").data(
                            e.getMessage() != null ? e.getMessage() : "ReAct 推理失败，请重试"));
                } catch (IOException ignore) {}
                emitter.complete();
            } finally {
                HybridRagContentRetriever.clearContext();
                MDC.remove("scenario");
                MDC.remove("userId");
            }
        });
        } catch (RejectedExecutionException ex) {
            // 线程池已满，快速失败并向客户端发送错误事件
            log.warn("[ReAct-Stream] SSE 线程池已满，拒绝请求 userId={}", userId);
            try {
                emitter.send(SseEmitter.event().name("error").data("服务繁忙，请稍后重试"));
            } catch (IOException ignore) {}
            emitter.complete();
        }

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

