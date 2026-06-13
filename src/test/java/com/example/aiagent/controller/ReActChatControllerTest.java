package com.example.aiagent.controller;

import com.example.aiagent.agent.ReActAgent;
import com.example.aiagent.chat.service.ChatHistoryService;
import com.example.aiagent.kb.service.ChatRagContextService;
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
        when(reActAgent.executeWithCallback(
                eq("hello"),
                eq("sess-1"),
                eq("deepseek-v4-pro"),
                isNull(String.class),
                isNull(Long.class),
                any(ReActAgent.StepCallback.class)))
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

    private MockMvc buildMockMvc(Executor executor) {
        ReActChatController controller = new ReActChatController(
                reActAgent,
                promptInjectionFilter,
                rateLimitService,
                outputContentFilter,
                auditLogService,
                chatRagContextService,
                chatHistoryService,
                executor);

        return MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .build();
    }

    private void stubPreflight() {
        when(promptInjectionFilter.check("hello"))
                .thenReturn(PromptInjectionFilter.FilterResult.pass("hello"));
        when(rateLimitService.tryAcquire(USER_ID))
                .thenReturn(RateLimitService.RateLimitResult.allow());
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
