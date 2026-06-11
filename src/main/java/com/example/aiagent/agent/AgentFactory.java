package com.example.aiagent.agent;

import com.example.aiagent.memory.RedisChatMemoryStore;
import com.example.aiagent.rag.retrieval.HybridRagContentRetriever;
import com.example.aiagent.tool.BusinessTools;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 组装工厂
 *
 * <p>将 LLM + 多轮记忆 + 混合 RAG 知识库 + 工具调用 组装为可用的 Assistant Bean。
 *
 * <p>RAG 升级说明：
 * 原实现使用 {@code EmbeddingStoreContentRetriever}（仅向量检索），
 * 现已替换为 {@link HybridRagContentRetriever}，完整串联：
 * <pre>
 *   HyDE 查询改写 → 向量检索 + BM25 双路 → RRF 融合 → Reranker 精排
 * </pre>
 * 每次对话前自动执行上述 4 步混合检索，将精排后的文档片段注入 LLM 上下文。
 */
@Configuration
@RequiredArgsConstructor
public class AgentFactory {

    private final ChatLanguageModel chatLanguageModel;
    private final StreamingChatLanguageModel streamingChatLanguageModel;
    private final RedisChatMemoryStore redisChatMemoryStore;
    private final HybridRagContentRetriever hybridRagContentRetriever;
    private final BusinessTools businessTools;

    @Value("${agent.memory.max-messages:20}")
    private int maxMessages;

    /**
     * 普通对话 Agent（同步）
     *
     * <p>包含：多轮记忆（Redis 持久化）+ 混合 RAG 检索 + 工具调用（8 种业务工具）
     */
    @Bean
    public ChatAssistant chatAssistant() {
        return AiServices.builder(ChatAssistant.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(memoryId ->
                        MessageWindowChatMemory.builder()
                                .id(memoryId)
                                .maxMessages(maxMessages)
                                .chatMemoryStore(redisChatMemoryStore)
                                .build())
                // 使用混合 RAG 检索器（HyDE + 双路检索 + RRF + Reranker）
                .contentRetriever(hybridRagContentRetriever)
                .tools(businessTools)
                .build();
    }

    /**
     * 流式对话 Agent（SSE 推送）
     *
     * <p>包含：多轮记忆（Redis 持久化）+ 混合 RAG 检索 + 工具调用（8 种业务工具）
     */
    @Bean
    public StreamingChatAssistant streamingChatAssistant() {
        return AiServices.builder(StreamingChatAssistant.class)
                .streamingChatLanguageModel(streamingChatLanguageModel)
                .chatMemoryProvider(memoryId ->
                        MessageWindowChatMemory.builder()
                                .id(memoryId)
                                .maxMessages(maxMessages)
                                .chatMemoryStore(redisChatMemoryStore)
                                .build())
                // 使用混合 RAG 检索器（HyDE + 双路检索 + RRF + Reranker）
                .contentRetriever(hybridRagContentRetriever)
                .tools(businessTools)
                .build();
    }

    // ==================== Assistant 接口定义 ====================

    /**
     * 同步对话接口
     *
     * @MemoryId    标注会话 ID，框架自动路由到对应 Redis 记忆空间
     * @UserMessage 标注用户输入
     */
    public interface ChatAssistant {
        @SystemMessage(fromResource = "prompts/system-assistant.st")
        String chat(@MemoryId String sessionId, @UserMessage String message);
    }

    /**
     * 流式对话接口
     *
     * <p>返回 {@link TokenStream}，逐 token 推送给前端（SSE）。
     */
    public interface StreamingChatAssistant {
        @SystemMessage(fromResource = "prompts/system-assistant.st")
        TokenStream streamChat(@MemoryId String sessionId, @UserMessage String message);
    }
}
