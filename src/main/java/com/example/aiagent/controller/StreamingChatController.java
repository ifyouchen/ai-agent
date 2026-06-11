package com.example.aiagent.controller;

import com.example.aiagent.agent.AgentFactory.StreamingChatAssistant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 流式对话接口（SSE，适合实时展示场景）
 * 前端用 EventSource 或 fetch + ReadableStream 接收
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class StreamingChatController {

    private final StreamingChatAssistant streamingChatAssistant;

    /**
     * 流式对话（SSE 推送，字符逐步出现）
     *
     * GET /api/v1/chat/stream?sessionId=user-123&message=你好
     *
     * 前端接收示例（JavaScript）：
     * const es = new EventSource(`/api/v1/chat/stream?sessionId=xxx&message=你好`);
     * es.onmessage = e => output.textContent += e.data;
     * es.addEventListener('done', () => es.close());
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam String sessionId,
            @RequestParam String message) {

        // 120 秒超时（长文本生成可能需要较长时间）
        SseEmitter emitter = new SseEmitter(120_000L);

        log.info("开始流式对话 sessionId={}", sessionId);

        streamingChatAssistant.streamChat(sessionId, message)
                .onNext(token -> {
                    // 每生成一个 token 就立即推送给前端
                    try {
                        emitter.send(SseEmitter.event().data(token));
                    } catch (IOException e) {
                        log.warn("SSE 推送失败，客户端可能已断开: {}", e.getMessage());
                        emitter.completeWithError(e);
                    }
                })
                .onComplete(response -> {
                    // 生成完毕，发送结束信号
                    try {
                        emitter.send(SseEmitter.event()
                                .name("done")
                                .data("[DONE]"));
                        emitter.complete();
                        log.info("流式对话完成 sessionId={}", sessionId);
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                })
                .onError(error -> {
                    log.error("流式对话出错 sessionId={}: {}", sessionId, error.getMessage());
                    emitter.completeWithError(error);
                })
                .start();

        return emitter;
    }
}
