package com.example.aiagent.memory;

import com.example.aiagent.chat.mapper.ChatMessageMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationMemoryServiceTest {

    @Mock
    RedisChatMemoryStore redisChatMemoryStore;

    @Mock
    ChatMessageMapper chatMessageMapper;

    @Mock
    MemoryCompactionService memoryCompactionService;

    ConversationMemoryService conversationMemoryService;

    @BeforeEach
    void setUp() {
        conversationMemoryService = new ConversationMemoryService(
                redisChatMemoryStore,
                chatMessageMapper,
                memoryCompactionService);
        ReflectionTestUtils.setField(conversationMemoryService, "maxMessages", 20);
    }

    @Test
    @DisplayName("预热会用已持久化的后处理答案校准 Redis 记忆")
    void warmup_refreshesMemoryFromPersistedProcessedAnswer() {
        when(redisChatMemoryStore.getMessages("user-1:sess-1")).thenReturn(List.of(
                UserMessage.from("修改这个五子棋代码"),
                AiMessage.from("public class GomokuGame extendsJFrame {}")
        ));
        when(chatMessageMapper.listRecentBySessionId("sess-1", 20)).thenReturn(List.of(
                dbMessage("user", "修改这个五子棋代码"),
                dbMessage("ai", "public class GomokuGame extends JFrame {}")
        ));

        conversationMemoryService.warmup("user-1:sess-1", "sess-1", "user-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisChatMemoryStore).updateMessages(eq("user-1:sess-1"), messagesCaptor.capture());

        ChatMessage latest = messagesCaptor.getValue().getLast();
        assertThat(latest).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) latest).text()).contains("extends JFrame");
    }

    @Test
    @DisplayName("回复完成后会把最后一条 AI 记忆替换为最终展示版本")
    void replaceLatestAiMessage_updatesLastAiMessage() {
        when(redisChatMemoryStore.getMessages("user-1:sess-1")).thenReturn(List.of(
                UserMessage.from("生成 Java"),
                AiMessage.from("returnfalse;")
        ));

        conversationMemoryService.replaceLatestAiMessage("user-1:sess-1", "return false;");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisChatMemoryStore).updateMessages(eq("user-1:sess-1"), messagesCaptor.capture());

        ChatMessage latest = messagesCaptor.getValue().getLast();
        assertThat(latest).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) latest).text()).isEqualTo("return false;");
    }

    private com.example.aiagent.chat.entity.ChatMessage dbMessage(String role, String content) {
        com.example.aiagent.chat.entity.ChatMessage message =
                new com.example.aiagent.chat.entity.ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
