package com.example.aiagent.chat.service;

import com.example.aiagent.chat.entity.ChatMessage;
import com.example.aiagent.chat.entity.ChatSession;
import com.example.aiagent.chat.mapper.ChatMessageMapper;
import com.example.aiagent.chat.mapper.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 聊天历史服务
 *
 * <p>消息保存采用 @Async 异步执行，不阻塞主链路（对话延迟零影响）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;

    /** 每个会话最多保留的消息数（防止单会话无限增长） */
    private static final int MAX_MESSAGES_PER_SESSION = 200;
    /** 查询用户会话列表时的最大条数 */
    private static final int MAX_SESSIONS_PER_USER = 50;

    // ── 会话管理 ─────────────────────────────────────────────

    /**
     * 保存/更新会话（异步）
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     * @param title     会话标题（取首条消息前 20 字）
     * @param kbId      关联知识库 ID（可选）
     */
    @Async
    public void saveSession(String sessionId, String userId, String title, Long kbId) {
        try {
            ChatSession session = new ChatSession();
            session.setSessionId(sessionId);
            session.setUserId(userId);
            session.setTitle(title != null ? truncate(title, 50) : "新对话");
            session.setKbId(kbId);
            chatSessionMapper.upsert(session);
        } catch (Exception e) {
            log.warn("保存会话失败 sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 保存消息（异步）
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     * @param role      消息角色：user | ai
     * @param content   消息内容（纯文本，不含 HTML 标签）
     */
    @Async
    public void saveMessage(String sessionId, String userId, String role, String content) {
        try {
            ChatMessage msg = new ChatMessage();
            msg.setSessionId(sessionId);
            msg.setUserId(userId);
            msg.setRole(role);
            msg.setContent(content != null ? content : "");
            chatMessageMapper.insert(msg);

            // 同步更新会话的 updated_at（让列表排序保持最新）
            chatSessionMapper.updateTitle(sessionId, null); // null 表示只更新时间
        } catch (Exception e) {
            log.warn("保存消息失败 sessionId={} role={}: {}", sessionId, role, e.getMessage());
        }
    }

    // ── 查询 ──────────────────────────────────────────────────

    /**
     * 查询用户的会话列表
     */
    public List<ChatSession> listSessions(String userId) {
        return chatSessionMapper.listByUserId(userId, MAX_SESSIONS_PER_USER);
    }

    /**
     * 按标题关键词搜索用户会话（服务端搜索）
     *
     * @param userId  用户 ID
     * @param keyword 搜索关键词（空时返回空列表）
     */
    public List<ChatSession> searchSessions(String userId, String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();
        return chatSessionMapper.searchByUserId(userId, keyword.strip(), MAX_SESSIONS_PER_USER);
    }

    /**
     * 查询会话的历史消息
     */
    public List<ChatMessage> listMessages(String sessionId, String userId) {
        // 校验会话归属，防止越权访问
        ChatSession session = chatSessionMapper.findBySessionId(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            return List.of();
        }
        return chatMessageMapper.listBySessionId(sessionId, MAX_MESSAGES_PER_SESSION);
    }

    /**
     * 更新会话标题（前端双击标题手动编辑后调用）
     */
    public void updateSessionTitle(String sessionId, String userId, String title) {
        ChatSession session = chatSessionMapper.findBySessionId(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            log.warn("会话不存在或无权限 sessionId={} userId={}", sessionId, userId);
            return;
        }
        chatSessionMapper.updateTitle(sessionId, truncate(title, 50));
    }

    /**
     * 删除会话及其所有消息
     */
    @Async
    public void deleteSession(String sessionId, String userId) {
        try {
            ChatSession session = chatSessionMapper.findBySessionId(sessionId);
            if (session == null || !session.getUserId().equals(userId)) return;
            chatMessageMapper.deleteBySessionId(sessionId);
            chatSessionMapper.deleteBySessionId(sessionId);
        } catch (Exception e) {
            log.warn("删除会话失败 sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 批量保存前端同步来的会话+消息（用于首次登录时从 localStorage 迁移到后端）
     */
    @Async
    public void syncFromClient(String userId, List<Map<String, Object>> sessions) {
        if (sessions == null || sessions.isEmpty()) return;
        for (Map<String, Object> s : sessions) {
            try {
                String sessionId = (String) s.get("id");
                String title = (String) s.getOrDefault("title", "新对话");
                if (sessionId == null) continue;

                ChatSession session = new ChatSession();
                session.setSessionId(sessionId);
                session.setUserId(userId);
                session.setTitle(truncate(title, 50));
                chatSessionMapper.upsert(session);

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> messages = (List<Map<String, Object>>) s.get("messages");
                if (messages != null) {
                    for (Map<String, Object> m : messages) {
                        String role = (String) m.getOrDefault("role", "user");
                        String content = (String) m.getOrDefault("content", "");
                        if (content.isBlank()) continue;
                        ChatMessage msg = new ChatMessage();
                        msg.setSessionId(sessionId);
                        msg.setUserId(userId);
                        msg.setRole(role);
                        msg.setContent(content);
                        chatMessageMapper.insert(msg);
                    }
                }
            } catch (Exception e) {
                log.warn("同步会话失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 更新消息反馈（点赞 'up' / 点踩 'down' / 撤销 null）
     *
     * @param messageId 消息数据库 ID
     * @param userId    操作用户（防越权）
     * @param feedback  反馈类型：'up' | 'down' | null（撤销）
     */
    public void updateFeedback(Long messageId, String userId, String feedback) {
        // 合法值校验
        if (feedback != null && !feedback.equals("up") && !feedback.equals("down")) {
            throw new IllegalArgumentException("反馈类型只能为 up、down 或 null");
        }
        chatMessageMapper.updateFeedback(messageId, userId, feedback);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }
}
