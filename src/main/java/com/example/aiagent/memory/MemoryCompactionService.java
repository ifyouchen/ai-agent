package com.example.aiagent.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话记忆滚动摘要压缩服务
 *
 * <p>当会话消息条数超过 {@code trigger-messages} 时，将较早的消息交给 LLM 压缩为摘要，
 * 仅保留最近 {@code keep-recent} 条原文。摘要单独存储在 Redis（{@code chat:memory:summary:{key}}），
 * 不进入消息窗口，因此不会被 {@code MessageWindowChatMemory} 驱逐。
 *
 * <p>压缩在每次对话前的 {@link ConversationMemoryService#warmup} 中同步执行，
 * 确保在消息窗口（maxMessages）驱逐前捕获并摘要旧消息，避免上下文丢失。
 *
 * <p>摘要累积：每次压缩时将"已有摘要 + 新增旧消息"一并交给 LLM，生成更新后的完整摘要。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryCompactionService {

    private final RedisChatMemoryStore redisChatMemoryStore;
    private final ChatLanguageModel chatLanguageModel;

    @Value("${agent.memory.summarize.enabled:true}")
    private boolean summarizeEnabled;

    @Value("${agent.memory.summarize.trigger-messages:18}")
    private int triggerMessages;

    @Value("${agent.memory.summarize.keep-recent:14}")
    private int keepRecent;

    /**
     * 压缩指定会话的记忆：若消息过多则摘要旧消息、保留最近 N 条原文。
     *
     * @param memoryKey 复合记忆 key（userId:sessionId）
     */
    public void compact(String memoryKey) {
        if (!summarizeEnabled || memoryKey == null || memoryKey.isBlank()) {
            return;
        }
        try {
            List<ChatMessage> conv = redisChatMemoryStore.getMessages(memoryKey);
            if (conv == null || conv.size() <= triggerMessages) {
                return;
            }
            int keep = Math.max(1, keepRecent);
            if (conv.size() <= keep) {
                return;
            }
            List<ChatMessage> older = conv.subList(0, conv.size() - keep);
            List<ChatMessage> recent = conv.subList(conv.size() - keep, conv.size());

            String existingSummary = redisChatMemoryStore.getSummary(memoryKey);
            String newSummary = summarize(existingSummary, older);
            if (newSummary != null && !newSummary.isBlank()) {
                redisChatMemoryStore.setSummary(memoryKey, newSummary);
            }
            // 无论摘要是否成功，都裁剪到最近 N 条，防止消息窗口驱逐
            redisChatMemoryStore.updateMessages(memoryKey, recent);
            log.info("记忆压缩 memoryKey={} older={} keep={} summaryLen={}",
                    memoryKey, older.size(), recent.size(),
                    newSummary != null ? newSummary.length() : 0);
        } catch (Exception e) {
            log.warn("记忆压缩失败 memoryKey={}: {}", memoryKey, e.getMessage());
        }
    }

    /**
     * 调用 LLM 将"已有摘要 + 新增旧消息"压缩为更新后的完整摘要。
     * 摘要生成失败时返回旧摘要，保证不丢失已有上下文。
     */
    private String summarize(String existingSummary, List<ChatMessage> older) {
        StringBuilder sb = new StringBuilder();
        sb.append("请将以下对话历史压缩为简洁摘要，保留关键事实、用户偏好、约定和未完成话题，不超过 300 字。\n\n");
        if (existingSummary != null && !existingSummary.isBlank()) {
            sb.append("已有摘要：\n").append(existingSummary).append("\n\n");
        }
        sb.append("新增对话：\n");
        for (ChatMessage m : older) {
            if (m instanceof UserMessage um && um.hasSingleText()) {
                sb.append("用户：").append(truncate(um.singleText())).append("\n");
            } else if (m instanceof AiMessage am && am.text() != null) {
                sb.append("助手：").append(truncate(am.text())).append("\n");
            }
        }
        sb.append("\n请直接输出更新后的完整摘要。");
        try {
            return chatLanguageModel.generate(sb.toString());
        } catch (Exception e) {
            log.warn("摘要生成失败，保留旧摘要: {}", e.getMessage());
            return existingSummary;
        }
    }

    private static final int SUMMARY_MSG_TRUNCATE = 500;

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() <= SUMMARY_MSG_TRUNCATE ? s : s.substring(0, SUMMARY_MSG_TRUNCATE) + "…";
    }
}

