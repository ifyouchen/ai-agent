package com.example.aiagent.controller;

import com.example.aiagent.agent.ReActAgent;
import com.example.aiagent.security.filter.OutputContentFilter;
import com.example.aiagent.security.filter.PromptInjectionFilter;
import com.example.aiagent.security.service.AuditLogService;
import com.example.aiagent.security.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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
@RequiredArgsConstructor
public class ReActChatController {

    private final ReActAgent reActAgent;
    private final PromptInjectionFilter promptInjectionFilter;
    private final RateLimitService rateLimitService;
    private final OutputContentFilter outputContentFilter;
    private final AuditLogService auditLogService;

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

            // ── Step 4：ReAct 多步推理 ────────────────────
            ReActAgent.ReActResult result = reActAgent.execute(
                    injectionCheck.sanitizedInput(), sessionId);

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

        } finally {
            MDC.remove("scenario");
        }
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

