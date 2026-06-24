package com.example.aiagent.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
@Profile("deepseek")
@RequiredArgsConstructor
public class DeepSeekModelFactory {

    public static final String QUICK_MODEL = "deepseek-v4-flash";
    public static final String EXPERT_MODEL = "deepseek-v4-pro";

    private static final Set<String> SUPPORTED_MODELS = Set.of(QUICK_MODEL, EXPERT_MODEL);

    private final DeepSeekProperties props;
    private final ConcurrentMap<String, ChatLanguageModel> chatModels = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, StreamingChatLanguageModel> streamingModels = new ConcurrentHashMap<>();

    public String normalizeModelName(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            log.info("模型名称为空，回退到默认模型 {}", QUICK_MODEL);
            return QUICK_MODEL;
        }
        String modelName = requestedModel.trim();
        if (!SUPPORTED_MODELS.contains(modelName)) {
            log.info("不支持的模型 {}，回退到默认模型 {}", modelName, QUICK_MODEL);
            return QUICK_MODEL;
        }
        return modelName;
    }

    public ChatLanguageModel chatModel(String requestedModel) {
        String modelName = normalizeModelName(requestedModel);
        return chatModels.computeIfAbsent(modelName, this::createChatModel);
    }

    public StreamingChatLanguageModel streamingModel(String requestedModel) {
        String modelName = normalizeModelName(requestedModel);
        return streamingModels.computeIfAbsent(modelName, this::createStreamingModel);
    }

    private ChatLanguageModel createChatModel(String modelName) {
        log.info("创建 ChatLanguageModel model={} baseUrl={}", modelName, props.getBaseUrl());
        return OpenAiChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(modelName)
                .temperature(props.getTemperature())
                .maxTokens(props.getMaxTokens())
                .build();
    }

    private StreamingChatLanguageModel createStreamingModel(String modelName) {
        log.info("创建 StreamingChatLanguageModel model={} baseUrl={}", modelName, props.getBaseUrl());
        return OpenAiStreamingChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(modelName)
                .temperature(props.getTemperature())
                .tokenizer(new OpenAiTokenizer())
                .build();
    }
}
