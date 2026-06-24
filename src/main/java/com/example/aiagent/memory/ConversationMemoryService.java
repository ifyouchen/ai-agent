package com.example.aiagent.memory;

import com.example.aiagent.chat.mapper.ChatMessageMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 会话记忆服务
 *
 * <p>职责：
 * <ul>
 *   <li>记忆 key 隔离：将 userId 与 sessionId 组合为复合 key，避免不同用户同 sessionId 串扰</li>
 *   <li>记忆回灌：Redis 记忆为空时，从 PostgreSQL 历史表恢复最近 N 条，解决"隔天失忆"</li>
 *   <li>记忆压缩：对话过长时滚动摘要旧消息，避免消息窗口裁剪丢失上下文</li>
 * </ul>
 *
 * <p>三条聊天链路（同步 / 流式 / ReAct）在调用 LLM 前均应调用 {@link #warmup}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationMemoryService {

    private final RedisChatMemoryStore redisChatMemoryStore;
    private final ChatMessageMapper chatMessageMapper;
    private final MemoryCompactionService memoryCompactionService;

    @Value("${agent.memory.max-messages:20}")
    private int maxMessages;

    /**
     * 构造复合记忆 key（userId:sessionId），用于 Redis 隔离。
     * userId 为空时回退为裸 sessionId，兼容历史数据。
     */
    public static String buildMemoryKey(String userId, String sessionId) {
        if (userId == null || userId.isBlank()) return sessionId;
        return userId + ":" + sessionId;
    }

    /**
     * 从复合记忆 key 中还原 userId。
     * 复合 key 格式为 "userId:sessionId"；若不含分隔符（回退模式）返回 null。
     */
    public static String extractUserId(String memoryKey) {
        if (memoryKey == null || memoryKey.isBlank()) return null;
        int idx = memoryKey.indexOf(':');
        if (idx <= 0) return null;
        return memoryKey.substring(0, idx);
    }

    /**
     * 记忆预热：回灌 DB 历史 + 压缩过长会话。
     *
     * <p>在调用 LLM 前执行：
     * <ol>
     *   <li>若 Redis 记忆为空，从 DB 历史表恢复最近 N 条（解决隔天/重开旧会话失忆）</li>
     *   <li>若会话消息过长，滚动摘要旧消息并裁剪到最近 N 条（避免消息窗口驱逐丢失上下文）</li>
     * </ol>
     *
     * @param memoryKey 复合记忆 key（userId:sessionId）
     * @param sessionId 原始会话 ID（用于查 DB 历史表）
     * @param userId    用户 ID（仅用于日志）
     */
    public void warmup(String memoryKey, String sessionId, String userId) {
        if (memoryKey == null || memoryKey.isBlank()) return;
        backfill(memoryKey, sessionId, userId);
        memoryCompactionService.compact(memoryKey);
    }

    private void backfill(String memoryKey, String sessionId, String userId) {
        try {
            List<ChatMessage> existing = redisChatMemoryStore.getMessages(memoryKey);
            if (existing != null && !existing.isEmpty()) {
                return;
            }
            List<com.example.aiagent.chat.entity.ChatMessage> dbMsgs =
                    chatMessageMapper.listBySessionId(sessionId, maxMessages);
            if (dbMsgs == null || dbMsgs.isEmpty()) {
                return;
            }
            List<ChatMessage> converted = dbMsgs.stream()
                    .map(this::toLcMessage)
                    .filter(Objects::nonNull)
                    .toList();
            if (converted.isEmpty()) {
                return;
            }
            redisChatMemoryStore.updateMessages(memoryKey, converted);
            log.info("记忆回灌 userId={} sessionId={} count={}", userId, sessionId, converted.size());
        } catch (Exception e) {
            log.warn("记忆回灌失败 sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 获取会话滚动摘要，格式化为可注入 system prompt 的片段。
     * 无摘要时返回空字符串。
     */
    public String getSummaryForPrompt(String memoryKey) {
        if (memoryKey == null || memoryKey.isBlank()) return "";
        String summary = redisChatMemoryStore.getSummary(memoryKey);
        if (summary == null || summary.isBlank()) return "";
        return "\n\n## 历史对话摘要\n" + summary;
    }

    /**
     * 将 DB 消息实体转换为 LangChain4j 消息对象。
     * 仅保留 user / ai 两类对话消息，忽略其他角色。
     */
    private ChatMessage toLcMessage(com.example.aiagent.chat.entity.ChatMessage db) {
        String content = db.getContent() != null ? db.getContent() : "";
        if ("user".equals(db.getRole())) {
            return UserMessage.from(content);
        }
        if ("ai".equals(db.getRole())) {
            return AiMessage.from(content);
        }
        return null;
    }
}

