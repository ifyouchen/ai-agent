package com.example.aiagent.controller;

import com.example.aiagent.agent.AgentFactory;
import com.example.aiagent.chat.service.ChatHistoryService;
import com.example.aiagent.dto.ChatRequest;
import com.example.aiagent.dto.ChatResponse;
import com.example.aiagent.kb.service.ChatRagContextService;
import com.example.aiagent.memory.ConversationMemoryService;
import com.example.aiagent.memory.RedisChatMemoryStore;
import com.example.aiagent.memory.UserMemoryService;
import com.example.aiagent.rag.retrieval.HybridRagContentRetriever;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 普通对话接口（同步，适合非实时场景）
 *
 * 完整安全链路：
 *   1. JWT 认证（由 JwtAuthFilter 完成，userId 已注入 SecurityContext）
 *   2. Prompt 注入检测（PromptInjectionFilter）
 *   3. 限流校验（RateLimitService）
 *   4. LLM 调用（chatAssistant.chat）
 *   5. 输出内容脱敏（OutputContentFilter）
 *   6. 审计日志（AuditLogService）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final AgentFactory agentFactory;
    private final RedisChatMemoryStore memoryStore;
    private final ConversationMemoryService conversationMemoryService;
    private final UserMemoryService userMemoryService;
    private final PromptInjectionFilter promptInjectionFilter;
    private final RateLimitService rateLimitService;
    private final OutputContentFilter outputContentFilter;
    private final AuditLogService auditLogService;
    private final ChatHistoryService chatHistoryService;
    private final ChatRagContextService chatRagContextService;

    /**
     * 发送消息（普通同步模式）
     *
     * POST /api/v1/chat
     * Header: Authorization: Bearer <token>
     * Body: {"sessionId": "user-123", "message": "帮我查一下订单 #12345 的状态"}
     *
     * @param userId 由 Spring Security 从 JWT 中解析注入（principal = userId）
     */
    @PostMapping
    public ResponseEntity<?> chat(@RequestBody ChatRequest request,
                                   @AuthenticationPrincipal String userId,
                                   HttpServletRequest httpRequest) {

        String clientIp = getClientIp(httpRequest);
        MDC.put("scenario", "chat");

        try {
            // ── Step 1：Prompt 注入检测 ────────────────────
            PromptInjectionFilter.FilterResult injectionCheck =
                    promptInjectionFilter.check(request.getMessage());

            if (injectionCheck.blocked()) {
                log.warn("Prompt 注入被拦截 userId={} reason={}", userId, injectionCheck.reason());
                auditLogService.logSecurityBlock(
                        AuditLogService.EventType.PROMPT_INJECTION_DETECTED,
                        userId, clientIp, injectionCheck.reason());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", injectionCheck.reason()));
            }

            // ── Step 2：限流校验 ──────────────────────────
            RateLimitService.RateLimitResult rateLimit = rateLimitService.tryAcquire(userId);

            if (!rateLimit.allowed()) {
                log.warn("限流触发 userId={} reason={}", userId, rateLimit.reason());
                auditLogService.logSecurityBlock(
                        AuditLogService.EventType.RATE_LIMIT_TRIGGERED,
                        userId, clientIp, rateLimit.reason());
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", String.valueOf(rateLimit.retryAfterSeconds()))
                        .body(Map.of("error", rateLimit.reason()));
            }

            // ── Step 3：审计日志（请求开始）──────────────
            auditLogService.log(AuditLogService.EventType.AI_CHAT_REQUEST,
                    userId, request.getSessionId(), clientIp, true,
                    Map.of("messageLength", injectionCheck.sanitizedInput().length()));

            // ── Step 4：LLM 调用（请求指定 kbId 时设置 RAG 上下文）──────────
            // 只有显式选择知识库时才注入 ThreadLocal；未选择知识库则保持普通对话。
            HybridRagContentRetriever.RetrievalContext ragContext =
                    chatRagContextService.resolve(userId, request.getOrgId(), request.getKbId());
            if (ragContext != null) {
                HybridRagContentRetriever.setContext(ragContext);
            }
            // 记忆回灌：Redis 记忆为空时从 DB 历史恢复，避免隔天/重开旧会话失忆
            String memoryKey = ConversationMemoryService.buildMemoryKey(userId, request.getSessionId());
            conversationMemoryService.warmup(memoryKey, request.getSessionId(), userId);
            // 取滚动摘要注入 system prompt（长会话压缩后的历史上下文）
            String sessionSummary = conversationMemoryService.getSummaryForPrompt(memoryKey);
            // 取用户长期记忆注入 system prompt（跨会话个性化）
            String userMemory = userMemoryService.getMemoryText(userId);
            long start = System.currentTimeMillis();
            String rawReply;
            try {
                rawReply = agentFactory.chatAssistantForModel(request.getModel()).chat(
                        memoryKey, injectionCheck.sanitizedInput(), sessionSummary, userMemory);
            } finally {
                // 无论成功或异常，必须清除 ThreadLocal 防止内存泄漏
                HybridRagContentRetriever.clearContext();
            }
            long duration = System.currentTimeMillis() - start;

            // ── Step 5：输出内容脱敏 ──────────────────────
            OutputContentFilter.FilterResult outputCheck = outputContentFilter.filter(rawReply);
            if (outputCheck.hasViolation()) {
                auditLogService.logSecurityBlock(
                        AuditLogService.EventType.OUTPUT_SENSITIVE_FILTERED,
                        userId, clientIp, "输出内容包含敏感信息，已脱敏：" + outputCheck.detectedTypes());
            }

            // ── Step 6：审计日志（对话完成）──────────────
            auditLogService.logAiChat(userId, request.getSessionId(), clientIp, 0, 0);

            // ── Step 7：异步持久化聊天记录 ──────────────
            String userText = injectionCheck.sanitizedInput();
            String aiText   = outputCheck.filteredContent();
            chatHistoryService.saveExchange(request.getSessionId(), userId,
                    userText.substring(0, Math.min(userText.length(), 20)), request.getKbId(),
                    userText, aiText);
            // 异步提取用户长期记忆（跨会话事实/偏好）
            userMemoryService.extractAsync(userId, userText, aiText);

            log.info("对话完成 userId={} sessionId={} 耗时={}ms",
                    userId, request.getSessionId(), duration);

            return ResponseEntity.ok(ChatResponse.builder()
                    .sessionId(request.getSessionId())
                    .reply(aiText)
                    .durationMs(duration)
                    .build());

        } catch (IllegalArgumentException e) {
            log.warn("对话知识库上下文无效 userId={} kbId={} orgId={} reason={}",
                    userId, request.getKbId(), request.getOrgId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } finally {
            MDC.remove("scenario");
        }
    }

    /**
     * 清除会话记忆（开启新话题时调用）
     *
     * DELETE /api/v1/chat/memory/{sessionId}
     */
    @DeleteMapping("/memory/{sessionId}")
    public ResponseEntity<String> clearMemory(@PathVariable String sessionId,
                                               @AuthenticationPrincipal String userId) {
        memoryStore.deleteMessages(ConversationMemoryService.buildMemoryKey(userId, sessionId));
        log.info("清除会话记忆 userId={} sessionId={}", userId, sessionId);
        return ResponseEntity.ok("会话 " + sessionId + " 的记忆已清除");
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
