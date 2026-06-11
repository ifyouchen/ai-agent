package com.example.aiagent.controller;

import com.example.aiagent.agent.AgentFactory.StreamingChatAssistant;
import com.example.aiagent.security.filter.PromptInjectionFilter;
import com.example.aiagent.security.service.RateLimitService;
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

/**
 * 流式对话接口（SSE，适合实时展示场景）
 * 前端用 EventSource 或 fetch + ReadableStream 接收
 *
 * 安全链路与 ChatController 相同：注入检测 → 限流 → LLM 调用
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class StreamingChatController {

    private final StreamingChatAssistant streamingChatAssistant;
    private final PromptInjectionFilter promptInjectionFilter;
    private final RateLimitService rateLimitService;

    /**
     * 流式对话（SSE 推送，字符逐步出现）
     *
     * GET /api/v1/chat/stream?sessionId=user-123&message=你好
     * Header: Authorization: Bearer <token>
     *
     * 前端接收示例（JavaScript）：
     * const es = new EventSource(`/api/v1/chat/stream?sessionId=xxx&message=你好`);
     * es.onmessage = e => output.textContent += e.data;
     * es.addEventListener('done', () => es.close());
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam String sessionId,
            @RequestParam String message,
            @AuthenticationPrincipal String userId) {

        SseEmitter emitter = new SseEmitter(120_000L);
        MDC.put("scenario", "stream_chat");

        try {
            // ── Step 1：Prompt 注入检测 ────────────────────
            PromptInjectionFilter.FilterResult injectionCheck = promptInjectionFilter.check(message);
            if (injectionCheck.blocked()) {
                log.warn("流式对话 Prompt 注入被拦截 userId={}", userId);
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
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(rateLimit.reason()));
                emitter.complete();
                return emitter;
            }

        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        } finally {
            MDC.remove("scenario");
        }

        // ── Step 3：LLM 流式调用 ──────────────────────────
        log.info("开始流式对话 userId={} sessionId={}", userId, sessionId);
        String sanitizedMessage = promptInjectionFilter.check(message).sanitizedInput();

        streamingChatAssistant.streamChat(sessionId, sanitizedMessage)
                .onNext(token -> {
                    try {
                        emitter.send(SseEmitter.event().data(token));
                    } catch (IOException e) {
                        log.warn("SSE 推送失败，客户端可能已断开: {}", e.getMessage());
                        emitter.completeWithError(e);
                    }
                })
                .onComplete(response -> {
                    try {
                        emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        emitter.complete();
                        log.info("流式对话完成 userId={} sessionId={}", userId, sessionId);
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                })
                .onError(error -> {
                    log.error("流式对话出错 userId={} sessionId={}: {}", userId, sessionId, error.getMessage());
                    emitter.completeWithError(error);
                })
                .start();

        return emitter;
    }
}
