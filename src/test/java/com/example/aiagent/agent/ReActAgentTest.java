package com.example.aiagent.agent;

import com.example.aiagent.config.DeepSeekModelFactory;
import com.example.aiagent.memory.RedisChatMemoryStore;
import com.example.aiagent.memory.UserMemoryService;
import com.example.aiagent.rag.pipeline.HybridRagPipeline;
import com.example.aiagent.tool.BusinessTools;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReActAgentTest {

    @Mock
    ChatLanguageModel chatModel;

    @Mock
    StreamingChatLanguageModel streamingChatModel;

    @Mock
    BusinessTools businessTools;

    @Mock
    HybridRagPipeline hybridRagPipeline;

    @Mock
    ObjectProvider<DeepSeekModelFactory> deepSeekModelFactory;

    @Mock
    RedisChatMemoryStore redisChatMemoryStore;

    @Mock
    UserMemoryService userMemoryService;

    ReActAgent reActAgent;

    @BeforeEach
    void setUp() {
        reActAgent = new ReActAgent(
                chatModel,
                streamingChatModel,
                businessTools,
                hybridRagPipeline,
                deepSeekModelFactory,
                redisChatMemoryStore,
                userMemoryService);
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

    @Test
    @DisplayName("深度思考流式回调会推送最终答案")
    void executeWithCallbackPushesFinalAnswer() {
        when(chatModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("最终答案")));

        List<ReActAgent.ReActStep> finalSteps = new java.util.ArrayList<>();
        ReActAgent.ReActResult result = reActAgent.executeWithCallback(
                "问题",
                "session-1",
                "deepseek-v4-pro",
                null,
                null,
                (step, isFinal) -> {
                    if (isFinal) finalSteps.add(step);
                });

        assertThat(result.answer()).isEqualTo("最终答案");
        assertThat(finalSteps).hasSize(1);
        assertThat(finalSteps.getFirst().thought()).isEqualTo("最终答案");
    }

    @Test
    @DisplayName("全链路 ReAct 流式执行会推送推理 token 和答案 token")
    void executeStreamingWithCallbackPushesReasoningAndAnswerTokens() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(2);
            handler.onNext("需要");
            handler.onNext("分析");
            handler.onComplete(Response.from(AiMessage.from("需要分析")));
            return null;
        }).when(streamingChatModel).generate(anyList(), anyList(), any(StreamingResponseHandler.class));

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onNext("最终");
            handler.onNext("答案");
            handler.onComplete(Response.from(AiMessage.from("最终答案")));
            return null;
        }).when(streamingChatModel).generate(anyList(), any(StreamingResponseHandler.class));

        List<String> reasoningTokens = new java.util.ArrayList<>();
        List<String> answerTokens = new java.util.ArrayList<>();

        ReActAgent.ReActResult result = reActAgent.executeStreamingWithCallback(
                "问题",
                "session-1",
                "deepseek-v4-pro",
                null,
                null,
                new ReActAgent.ReActStreamCallback() {
                    @Override
                    public void onReasoningToken(int iteration, String token) {
                        reasoningTokens.add(token);
                    }

                    @Override
                    public void onAnswerToken(String token) {
                        answerTokens.add(token);
                    }
                });

        assertThat(result.answer()).isEqualTo("最终答案");
        assertThat(reasoningTokens).containsExactly("需要", "分析");
        assertThat(answerTokens).containsExactly("最终", "答案");
    }

    @Test
    @DisplayName("全链路 ReAct 无工具调用且已有完整答案时不再额外 synthesis")
    void executeStreamingWithCallbackReusesDirectAnswerWhenNoToolCall() {
        String directAnswer = "这是一个完整的业务说明，已经能够直接回答用户的问题。";
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(2);
            handler.onNext(directAnswer);
            handler.onComplete(Response.from(AiMessage.from(directAnswer)));
            return null;
        }).when(streamingChatModel).generate(anyList(), anyList(), any(StreamingResponseHandler.class));

        List<String> answerTokens = new java.util.ArrayList<>();

        ReActAgent.ReActResult result = reActAgent.executeStreamingWithCallback(
                "问题",
                "session-1",
                "deepseek-v4-pro",
                null,
                null,
                new ReActAgent.ReActStreamCallback() {
                    @Override
                    public void onAnswerToken(String token) {
                        answerTokens.add(token);
                    }
                });

        assertThat(result.answer()).isEqualTo(directAnswer);
        assertThat(answerTokens).containsExactly(directAnswer);
        verify(streamingChatModel, never()).generate(anyList(), any(StreamingResponseHandler.class));
    }

    @Test
    @DisplayName("深度思考流式执行中的模型异常会向调用方传播")
    void executeWithCallbackPropagatesModelErrors() {
        when(chatModel.generate(anyList(), anyList()))
                .thenThrow(new RuntimeException("account_overdue"));

        assertThatThrownBy(() -> reActAgent.executeWithCallback(
                "问题",
                "session-1",
                "deepseek-v4-pro",
                null,
                null,
                (step, isFinal) -> {}))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("account_overdue");
    }
}
