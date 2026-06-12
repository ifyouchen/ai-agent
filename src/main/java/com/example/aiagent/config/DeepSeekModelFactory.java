package com.example.aiagent.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
            return QUICK_MODEL;
        }
        String modelName = requestedModel.trim();
        return SUPPORTED_MODELS.contains(modelName) ? modelName : QUICK_MODEL;
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
        return OpenAiChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(modelName)
                .temperature(props.getTemperature())
                .maxTokens(props.getMaxTokens())
                .build();
    }

    private StreamingChatLanguageModel createStreamingModel(String modelName) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(modelName)
                .temperature(props.getTemperature())
                .build();
    }
}
