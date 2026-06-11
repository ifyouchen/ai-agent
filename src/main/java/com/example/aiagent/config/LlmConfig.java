package com.example.aiagent.config;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class LlmConfig {

    // ==================== DeepSeek Profile ====================

    /**
     * DeepSeek 同步模型
     * DeepSeek 兼容 OpenAI 协议，只需修改 baseUrl
     */
    @Bean
    @Profile("deepseek")
    public ChatLanguageModel deepSeekChatModel(DeepSeekProperties props) {
        return OpenAiChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(props.getModelName())
                .temperature(props.getTemperature())
                .maxTokens(props.getMaxTokens())
                .build();
    }

    /**
     * DeepSeek 流式模型（用于 SSE 实时推送）
     */
    @Bean
    @Profile("deepseek")
    public StreamingChatLanguageModel deepSeekStreamingModel(DeepSeekProperties props) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(props.getModelName())
                .temperature(props.getTemperature())
                .build();
    }

    // ==================== Claude Profile ====================

    /**
     * Claude 同步模型
     */
    @Bean
    @Profile("claude")
    public ChatLanguageModel claudeChatModel(
            @Value("${anthropic.api-key}") String apiKey,
            @Value("${anthropic.model-name:claude-opus-4-8}") String modelName,
            @Value("${anthropic.max-tokens:8192}") int maxTokens) {
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .build();
    }

    /**
     * Claude 流式模型
     */
    @Bean
    @Profile("claude")
    public StreamingChatLanguageModel claudeStreamingModel(
            @Value("${anthropic.api-key}") String apiKey,
            @Value("${anthropic.model-name:claude-opus-4-8}") String modelName) {
        return AnthropicStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
    }
}
