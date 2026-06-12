package com.example.aiagent.agent;

import com.example.aiagent.config.DeepSeekModelFactory;
import com.example.aiagent.memory.RedisChatMemoryStore;
import com.example.aiagent.rag.pipeline.HybridRagPipeline;
import com.example.aiagent.tool.BusinessTools;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReActAgentTest {

    @Mock
    ChatLanguageModel chatModel;

    @Mock
    BusinessTools businessTools;

    @Mock
    HybridRagPipeline hybridRagPipeline;

    @Mock
    ObjectProvider<DeepSeekModelFactory> deepSeekModelFactory;

    @Mock
    RedisChatMemoryStore redisChatMemoryStore;

    ReActAgent reActAgent;

    @BeforeEach
    void setUp() {
        reActAgent = new ReActAgent(
                chatModel,
                businessTools,
                hybridRagPipeline,
                deepSeekModelFactory,
                redisChatMemoryStore);
        ReflectionTestUtils.setField(reActAgent, "maxMessages", 6);
        ReflectionTestUtils.setField(reActAgent, "cachedToolSpecs", List.<ToolSpecification>of());
    }

    @Test
    @DisplayName("深度思考执行时会带上同一 session 的历史记忆")
    void executeIncludesStoredSessionMemory() {
        when(redisChatMemoryStore.getMessages("session-1")).thenReturn(List.of(
                UserMessage.from("机构产品讲了啥？"),
                AiMessage.from("机构产品是系统中的核心金融产品概念。")
        ));
        when(chatModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("更完整的机构产品说明")));

        ReActAgent.ReActResult result = reActAgent.execute(
                "你再好好回复下",
                "session-1",
                "deepseek-v4-pro",
                null,
                null);

        assertThat(result.answer()).isEqualTo("更完整的机构产品说明");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatModel).generate(messagesCaptor.capture(), anyList());
        String messagesText = messagesCaptor.getValue().stream()
                .map(String::valueOf)
                .collect(Collectors.joining("\n"));

        assertThat(messagesText)
                .contains("机构产品讲了啥？")
                .contains("机构产品是系统中的核心金融产品概念。")
                .contains("你再好好回复下");
    }

    @Test
    @DisplayName("深度思考完成后会写回同一个 Redis 记忆窗口")
    void rememberExchangeAppendsAnswerToSessionMemory() {
        ReflectionTestUtils.setField(reActAgent, "maxMessages", 4);
        when(redisChatMemoryStore.getMessages("session-1")).thenReturn(List.of(
                UserMessage.from("旧问题1"),
                AiMessage.from("旧答案1"),
                UserMessage.from("旧问题2"),
                AiMessage.from("旧答案2")
        ));

        reActAgent.rememberExchange("session-1", "新问题", "新答案");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisChatMemoryStore).updateMessages(org.mockito.ArgumentMatchers.eq("session-1"),
                messagesCaptor.capture());

        String messagesText = messagesCaptor.getValue().stream()
                .map(String::valueOf)
                .collect(Collectors.joining("\n"));
        assertThat(messagesCaptor.getValue()).hasSize(4);
        assertThat(messagesText)
                .doesNotContain("旧问题1")
                .doesNotContain("旧答案1")
                .contains("旧问题2")
                .contains("旧答案2")
                .contains("新问题")
                .contains("新答案");
    }
}
