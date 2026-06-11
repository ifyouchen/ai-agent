package com.example.aiagent.controller;

import com.example.aiagent.agent.AgentFactory.ChatAssistant;
import com.example.aiagent.dto.ChatRequest;
import com.example.aiagent.dto.ChatResponse;
import com.example.aiagent.memory.RedisChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 普通对话接口（同步，适合非实时场景）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatAssistant chatAssistant;
    private final RedisChatMemoryStore memoryStore;

    /**
     * 发送消息（普通同步模式）
     *
     * POST /api/v1/chat
     * {
     *   "sessionId": "user-123",
     *   "message": "帮我查一下订单 #12345 的状态"
     * }
     */
    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        log.info("收到对话请求 sessionId={}, message={}", request.getSessionId(), request.getMessage());

        long start = System.currentTimeMillis();
        String reply = chatAssistant.chat(request.getSessionId(), request.getMessage());
        long duration = System.currentTimeMillis() - start;

        log.info("对话完成 sessionId={}, 耗时={}ms", request.getSessionId(), duration);

        return ChatResponse.builder()
                .sessionId(request.getSessionId())
                .reply(reply)
                .durationMs(duration)
                .build();
    }

    /**
     * 清除会话记忆（开启新话题时调用）
     *
     * DELETE /api/v1/chat/memory/{sessionId}
     */
    @DeleteMapping("/memory/{sessionId}")
    public String clearMemory(@PathVariable String sessionId) {
        memoryStore.deleteMessages(sessionId);
        return "会话 " + sessionId + " 的记忆已清除";
    }
}
