package com.example.aiagent.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static dev.langchain4j.data.message.ChatMessageDeserializer.messagesFromJson;
import static dev.langchain4j.data.message.ChatMessageSerializer.messagesToJson;

/**
 * 基于 Redis 的对话记忆持久化存储
 * 服务重启后记忆不丢失，支持 TTL 自动过期
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

    private final StringRedisTemplate redisTemplate;

    @Value("${agent.memory.ttl-hours:24}")
    private long ttlHours;

    private static final String KEY_PREFIX = "chat:memory:";

    /**
     * 读取指定会话的消息历史
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = buildKey(memoryId);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return messagesFromJson(json);
        } catch (Exception e) {
            log.warn("读取会话记忆失败，sessionId={}，将返回空记忆: {}", memoryId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 更新会话记忆（每次对话后自动调用）
     * 每次更新都刷新 TTL，实现"最后活跃时间"滑动过期
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = buildKey(memoryId);
        try {
            String json = messagesToJson(messages);
            redisTemplate.opsForValue().set(key, json, Duration.ofHours(ttlHours));
        } catch (Exception e) {
            log.error("保存会话记忆失败，sessionId={}: {}", memoryId, e.getMessage());
        }
    }

    /**
     * 清除会话记忆（用户主动清除或开启新话题时调用）
     */
    @Override
    public void deleteMessages(Object memoryId) {
        String key = buildKey(memoryId);
        redisTemplate.delete(key);
        log.info("已清除会话记忆，sessionId={}", memoryId);
    }

    private String buildKey(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }
}
