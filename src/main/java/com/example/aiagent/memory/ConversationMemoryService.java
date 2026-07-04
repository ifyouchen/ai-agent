package com.example.aiagent.memory;

import com.example.aiagent.chat.mapper.ChatMessageMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
        refreshFromPersistedHistory(memoryKey, sessionId, userId);
        memoryCompactionService.compact(memoryKey);
    }

    /**
     * 将当前会话记忆中的最后一条 AI 回复替换为最终展示版本。
     *
     * <p>前置条件：LLM 已经完成回复，且调用方已完成输出脱敏和代码块后处理。后置条件：Redis
     * 记忆中的最近 AI 消息与前端展示、数据库历史保持一致。该方法吞掉 Redis 异常并记录日志，不影响主流程。
     *
     * @param memoryKey   复合记忆 key（userId:sessionId）
     * @param finalAnswer 已完成后处理的 AI 回复
     */
    public void replaceLatestAiMessage(String memoryKey, String finalAnswer) {
        if (memoryKey == null || memoryKey.isBlank() || finalAnswer == null) return;
        try {
            List<ChatMessage> messages = redisChatMemoryStore.getMessages(memoryKey);
            int latestAiIndex = latestAiMessageIndex(messages);
            if (latestAiIndex < 0) {
                return;
            }
            ChatMessage current = messages.get(latestAiIndex);
            if (current instanceof AiMessage aiMessage && finalAnswer.equals(aiMessage.text())) {
                return;
            }
            List<ChatMessage> updated = new ArrayList<>(messages);
            updated.set(latestAiIndex, AiMessage.from(finalAnswer));
            redisChatMemoryStore.updateMessages(memoryKey, updated);
            log.debug("已同步最终 AI 回复到 Redis 记忆 memoryKey={}", memoryKey);
        } catch (Exception e) {
            log.warn("同步最终 AI 回复到 Redis 记忆失败 memoryKey={}: {}", memoryKey, e.getMessage());
        }
    }

    private void backfill(String memoryKey, String sessionId, String userId) {
        try {
            List<ChatMessage> existing = redisChatMemoryStore.getMessages(memoryKey);
            if (existing != null && !existing.isEmpty()) {
                return;
            }
            List<ChatMessage> converted = loadPersistedMessages(sessionId);
            if (converted.isEmpty()) {
                return;
            }
            redisChatMemoryStore.updateMessages(memoryKey, converted);
            log.info("记忆回灌 userId={} sessionId={} count={}", userId, sessionId, converted.size());
        } catch (Exception e) {
            log.warn("记忆回灌失败 sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    private void refreshFromPersistedHistory(String memoryKey, String sessionId, String userId) {
        try {
            List<ChatMessage> existing = redisChatMemoryStore.getMessages(memoryKey);
            if (existing == null || existing.isEmpty()) {
                return;
            }
            List<ChatMessage> persisted = loadPersistedMessages(sessionId);
            if (persisted.isEmpty() || hasSameConversationTail(existing, persisted)) {
                return;
            }
            redisChatMemoryStore.updateMessages(memoryKey, persisted);
            log.info("记忆校准 userId={} sessionId={} count={}", userId, sessionId, persisted.size());
        } catch (Exception e) {
            log.warn("记忆校准失败 sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    private List<ChatMessage> loadPersistedMessages(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<com.example.aiagent.chat.entity.ChatMessage> dbMsgs =
                chatMessageMapper.listRecentBySessionId(sessionId, maxMessages);
        if (dbMsgs == null || dbMsgs.isEmpty()) {
            return List.of();
        }
        return dbMsgs.stream()
                .map(this::toLcMessage)
                .filter(Objects::nonNull)
                .toList();
    }

    private boolean hasSameConversationTail(List<ChatMessage> existing, List<ChatMessage> persisted) {
        return Objects.equals(latestUserText(existing), latestUserText(persisted))
                && Objects.equals(latestAiText(existing), latestAiText(persisted));
    }

    private int latestAiMessageIndex(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return -1;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage) {
                return i;
            }
        }
        return -1;
    }

    private String latestAiText(List<ChatMessage> messages) {
        int index = latestAiMessageIndex(messages);
        if (index < 0) {
            return null;
        }
        return ((AiMessage) messages.get(index)).text();
    }

    private String latestUserText(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage userMessage) {
                return userMessage.singleText();
            }
        }
        return null;
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

