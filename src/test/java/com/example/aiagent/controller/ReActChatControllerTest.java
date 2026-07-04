package com.example.aiagent.controller;

import com.example.aiagent.agent.ReActAgent;
import com.example.aiagent.chat.service.ChatHistoryService;
import com.example.aiagent.chat.service.CodeBlockPostProcessor;
import com.example.aiagent.kb.service.ChatRagContextService;
import com.example.aiagent.memory.ConversationMemoryService;
import com.example.aiagent.memory.UserMemoryService;
import com.example.aiagent.observability.metrics.LlmMetricsRecorder;
import com.example.aiagent.observability.service.TokenUsageIntentService;
import com.example.aiagent.observability.service.TokenUsageService;
import com.example.aiagent.security.filter.OutputContentFilter;
import com.example.aiagent.security.filter.PromptInjectionFilter;
import com.example.aiagent.security.service.AuditLogService;
import com.example.aiagent.security.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReActChatControllerTest {

    private static final String USER_ID = "user-1";

    @Mock
    ReActAgent reActAgent;

    @Mock
    PromptInjectionFilter promptInjectionFilter;

    @Mock
    RateLimitService rateLimitService;

    @Mock
    OutputContentFilter outputContentFilter;

    @Mock
    AuditLogService auditLogService;

    @Mock
    ChatRagContextService chatRagContextService;

    @Mock
    ChatHistoryService chatHistoryService;

    @Mock
    ConversationMemoryService conversationMemoryService;

    @Mock
    UserMemoryService userMemoryService;

    @Mock
    TokenUsageService tokenUsageService;

    @Mock
    TokenUsageIntentService tokenUsageIntentService;

    @Mock
    LlmMetricsRecorder llmMetricsRecorder;

    @Mock
    CodeBlockPostProcessor codeBlockPostProcessor;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = buildMockMvc(Runnable::run);
    }

    @Test
    @DisplayName("ReAct 流式推理异常会发送 react-error 并完成 SSE")
    void reactStreamSendsReactErrorWhenAgentThrows() throws Exception {
        stubPreflight();
        when(chatRagContextService.resolve(USER_ID, null, null)).thenReturn(null);
        when(reActAgent.executeStreamingWithCallback(
                eq("hello"),
                eq("user-1:sess-1"),
                eq("deepseek-v4-pro"),
                isNull(String.class),
                isNull(Long.class),
                any(ReActAgent.ReActStreamCallback.class)))
                .thenThrow(new RuntimeException("""
                        {"error":{"code":"account_overdue","message":"Access denied due to overdue account"}}
                        """));

        MvcResult result = performReactStream();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:react-error")))
                .andExpect(content().string(containsString("\"code\":\"account_overdue\"")));
    }

    @Test
    @DisplayName("ReAct SSE 线程池拒绝时会快速返回 react-error")
    void reactStreamSendsReactErrorWhenExecutorRejects() throws Exception {
        mockMvc = buildMockMvc(command -> {
            throw new RejectedExecutionException("full");
        });
        stubPreflight();
        when(chatRagContextService.resolve(USER_ID, null, null)).thenReturn(null);

        MvcResult result = performReactStream();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:react-error")))
                .andExpect(content().string(containsString("\"code\":\"busy\"")));
    }

    @Test
    @DisplayName("知识库上下文非法时会返回 react-error 而不是让前端等待")
    void reactStreamSendsReactErrorWhenKnowledgeBaseIsForbidden() throws Exception {
        stubPreflight();
        when(chatRagContextService.resolve(USER_ID, null, null))
                .thenThrow(new IllegalArgumentException("无权访问该知识库"));

        MvcResult result = performReactStream();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:react-error")))
                .andExpect(content().string(containsString("\"code\":\"kb_forbidden\"")));
    }

    @Test
    @DisplayName("ReAct 流式推理会发送推理、工具和答案 token 事件")
    void reactStreamSendsTokenLevelEvents() throws Exception {
        stubPreflight();
        when(chatRagContextService.resolve(USER_ID, null, null)).thenReturn(null);
        when(outputContentFilter.filter("final answer"))
                .thenReturn(new OutputContentFilter.FilterResult("final answer", java.util.List.of(), false));
        when(codeBlockPostProcessor.process("final answer", "deepseek-v4-pro"))
                .thenReturn("final answer");

        doAnswer(invocation -> {
            ReActAgent.ReActStreamCallback callback = invocation.getArgument(5);
            callback.onReasoningStart(1);
            callback.onReasoningToken(1, "需要");
            callback.onReasoningDone(1, "需要查工具");
            ReActAgent.ReActStep step = new ReActAgent.ReActStep(
                    1, "需要查工具", "listKbDocuments", "{}", "文档列表");
            callback.onToolCall(step);
            callback.onToolResult(step);
            callback.onAnswerStart(1);
            callback.onAnswerToken("final ");
            callback.onAnswerToken("answer");
            return new ReActAgent.ReActResult("final answer", java.util.List.of(step), 1, 123, 0, 0);
        }).when(reActAgent).executeStreamingWithCallback(
                eq("hello"),
                eq("user-1:sess-1"),
                eq("deepseek-v4-pro"),
                isNull(String.class),
                isNull(Long.class),
                any(ReActAgent.ReActStreamCallback.class));

        MvcResult result = performReactStream();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:reasoning-token")))
                .andExpect(content().string(containsString("event:tool-call")))
                .andExpect(content().string(containsString("event:tool-result")))
                .andExpect(content().string(containsString("event:answer-token")))
                .andExpect(content().string(containsString("\"answer\":\"final answer\"")))
                .andExpect(content().string(containsString("event:answer")))
                .andExpect(content().string(containsString("event:done")));
    }

    private MockMvc buildMockMvc(Executor executor) {
        ReActChatController controller = new ReActChatController(
                reActAgent,
                promptInjectionFilter,
                rateLimitService,
                outputContentFilter,
                auditLogService,
                chatRagContextService,
                chatHistoryService,
                conversationMemoryService,
                userMemoryService,
                tokenUsageService,
                tokenUsageIntentService,
                llmMetricsRecorder,
                codeBlockPostProcessor,
                executor);
        ReflectionTestUtils.setField(controller, "streamFlushIntervalMs", 50L);
        ReflectionTestUtils.setField(controller, "streamFlushMinChars", 40);

        return MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .build();
    }

    private void stubPreflight() {
        when(promptInjectionFilter.check("hello"))
                .thenReturn(PromptInjectionFilter.FilterResult.pass("hello"));
        when(rateLimitService.tryAcquire(USER_ID))
                .thenReturn(RateLimitService.RateLimitResult.allow());
        when(tokenUsageIntentService.resolve(USER_ID, "hello"))
                .thenReturn(java.util.Optional.empty());
    }

    private MvcResult performReactStream() throws Exception {
        return mockMvc.perform(get("/api/v1/chat/react/stream")
                        .param("sessionId", "sess-1")
                        .param("message", "hello")
                        .param("model", "deepseek-v4-pro"))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private HandlerMethodArgumentResolver authenticationPrincipalResolver() {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                        && parameter.getParameterType().equals(String.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter,
                                          ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest,
                                          WebDataBinderFactory binderFactory) {
                return USER_ID;
            }
        };
    }
}
