package com.example.aiagent.agent;

import com.example.aiagent.config.DeepSeekModelFactory;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AgentFactory {

    private final ChatLanguageModel chatLanguageModel;
    private final StreamingChatLanguageModel streamingChatLanguageModel;
    private final RedisChatMemoryStore redisChatMemoryStore;
    private final HybridRagContentRetriever hybridRagContentRetriever;
    private final BusinessTools businessTools;
    private final ObjectProvider<DeepSeekModelFactory> deepSeekModelFactory;

    private final Map<String, ChatAssistant> chatAssistantCache = new ConcurrentHashMap<>();
    private final Map<String, StreamingChatAssistant> streamingChatAssistantCache = new ConcurrentHashMap<>();

    @Value("${agent.memory.max-messages:20}")
    private int maxMessages;

    /**
     * 普通对话 Agent（同步）
     *
     * <p>包含：多轮记忆（Redis 持久化）+ 混合 RAG 检索 + 工具调用（8 种业务工具）
     */
    @Bean
    public ChatAssistant chatAssistant() {
        return buildChatAssistant(chatLanguageModel);
    }

    public ChatAssistant chatAssistantForModel(String modelName) {
        DeepSeekModelFactory factory = deepSeekModelFactory.getIfAvailable();
        if (factory == null) {
            log.info("DeepSeekModelFactory 不可用，回退到默认模型");
            return chatAssistant();
        }
        String key = factory.normalizeModelName(modelName);
        log.info("获取同步聊天助手 model={} key={}", modelName, key);
        return chatAssistantCache.computeIfAbsent(key, ignored -> {
            log.info("创建新的同步聊天助手 model={}", key);
            return buildChatAssistant(factory.chatModel(key));
        });
    }

    private ChatAssistant buildChatAssistant(ChatLanguageModel model) {
        return AiServices.builder(ChatAssistant.class)
                .chatLanguageModel(model)
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
        return buildStreamingChatAssistant(streamingChatLanguageModel);
    }

    public StreamingChatAssistant streamingChatAssistantForModel(String modelName) {
        DeepSeekModelFactory factory = deepSeekModelFactory.getIfAvailable();
        if (factory == null) {
            log.info("DeepSeekModelFactory 不可用，回退到默认流式模型");
            return streamingChatAssistant();
        }
        String key = factory.normalizeModelName(modelName);
        log.info("获取流式聊天助手 model={} key={}", modelName, key);
        return streamingChatAssistantCache.computeIfAbsent(key,
                ignored -> {
                    log.info("创建新的流式聊天助手 model={}", key);
                    return buildStreamingChatAssistant(factory.streamingModel(key));
                });
    }

    private StreamingChatAssistant buildStreamingChatAssistant(StreamingChatLanguageModel model) {
        return AiServices.builder(StreamingChatAssistant.class)
                .streamingChatLanguageModel(model)
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
