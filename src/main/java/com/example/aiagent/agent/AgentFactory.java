package com.example.aiagent.agent;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.store.embedding.EmbeddingStore;
import com.example.aiagent.memory.RedisChatMemoryStore;
import com.example.aiagent.tool.BusinessTools;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 组装工厂
 * 将 LLM + 记忆 + RAG + 工具 组装为可用的 Assistant Bean
 */
@Configuration
@RequiredArgsConstructor
public class AgentFactory {

    private final ChatLanguageModel chatLanguageModel;
    private final StreamingChatLanguageModel streamingChatLanguageModel;
    private final RedisChatMemoryStore redisChatMemoryStore;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final BusinessTools businessTools;

    @Value("${agent.memory.max-messages:20}")
    private int maxMessages;

    @Value("${agent.rag.max-results:3}")
    private int ragMaxResults;

    @Value("${agent.rag.min-score:0.7}")
    private double ragMinScore;

    /**
     * 普通对话 Agent（同步）
     * 包含：多轮记忆 + RAG 知识库 + 工具调用
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
                .contentRetriever(buildContentRetriever())
                .tools(businessTools)
                .build();
    }

    /**
     * 流式对话 Agent（SSE 推送）
     * 包含：多轮记忆 + RAG 知识库 + 工具调用
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
                .contentRetriever(buildContentRetriever())
                .tools(businessTools)
                .build();
    }

    /**
     * RAG 检索器
     * 每次对话前自动从知识库检索最相关的段落注入上下文
     */
    private EmbeddingStoreContentRetriever buildContentRetriever() {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(ragMaxResults)
                .minScore(ragMinScore)
                .build();
    }

    // ==================== Assistant 接口定义 ====================

    /**
     * 同步对话接口
     * @MemoryId  标注会话 ID，框架自动路由到对应记忆
     * @UserMessage 标注用户输入
     */
    public interface ChatAssistant {
        @SystemMessage(fromResource = "prompts/system-assistant.st")
        String chat(@MemoryId String sessionId, @UserMessage String message);
    }

    /**
     * 流式对话接口
     * 返回 TokenStream，逐 token 推送给前端
     */
    public interface StreamingChatAssistant {
        @SystemMessage(fromResource = "prompts/system-assistant.st")
        TokenStream streamChat(@MemoryId String sessionId, @UserMessage String message);
    }
}
