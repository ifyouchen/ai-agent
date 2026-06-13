package com.example.aiagent.chat.service;

import com.example.aiagent.chat.entity.ChatMessage;
import com.example.aiagent.chat.entity.ChatShare;
import com.example.aiagent.chat.entity.ChatSession;
import com.example.aiagent.chat.mapper.ChatMessageMapper;
import com.example.aiagent.chat.mapper.ChatShareMapper;
import com.example.aiagent.chat.mapper.ChatSessionMapper;
import com.example.aiagent.memory.RedisChatMemoryStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
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
    private final ChatShareMapper chatShareMapper;
    private final RedisChatMemoryStore redisChatMemoryStore;
    private final ObjectMapper objectMapper;

    /** 每个会话最多保留的消息数（防止单会话无限增长） */
    private static final int MAX_MESSAGES_PER_SESSION = 200;
    /** 查询用户会话列表时的最大条数 */
    private static final int MAX_SESSIONS_PER_USER = 50;
    private static final int MAX_SHARE_MESSAGES = 200;
    private static final int SHARE_TTL_DAYS = 7;
    private static final SecureRandom RANDOM = new SecureRandom();

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
     * 用前端确认后的消息列表重写当前会话历史，通常用于“编辑旧问题并重新生成”。
     */
    public void rewriteMessages(String sessionId, String userId, List<Map<String, Object>> messages) {
        ChatSession session = chatSessionMapper.findBySessionId(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new IllegalArgumentException("会话不存在或无权限");
        }

        chatMessageMapper.deleteBySessionId(sessionId);
        if (messages != null) {
            messages.stream()
                    .limit(MAX_MESSAGES_PER_SESSION)
                    .map(m -> toChatMessage(sessionId, userId, m))
                    .filter(m -> !m.getContent().isBlank())
                    .forEach(chatMessageMapper::insert);
        }
        chatSessionMapper.updateTitle(sessionId, null);
        redisChatMemoryStore.deleteMessages(sessionId);
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
     * 批量删除当前用户的指定会话及消息。
     */
    @Async
    public void deleteSessions(List<String> sessionIds, String userId) {
        if (sessionIds == null || sessionIds.isEmpty()) return;
        List<String> cleanedIds = sessionIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (cleanedIds.isEmpty()) return;
        try {
            chatMessageMapper.deleteByUserIdAndSessionIds(userId, cleanedIds);
            chatSessionMapper.deleteByUserIdAndSessionIds(userId, cleanedIds);
        } catch (Exception e) {
            log.warn("批量删除会话失败 userId={} count={}: {}", userId, cleanedIds.size(), e.getMessage());
        }
    }

    /**
     * 删除当前用户的全部会话及消息。
     */
    @Async
    public void deleteAllSessions(String userId) {
        if (userId == null || userId.isBlank()) return;
        try {
            chatMessageMapper.deleteByUserId(userId);
            chatSessionMapper.deleteByUserId(userId);
        } catch (Exception e) {
            log.warn("清空全部会话失败 userId={}: {}", userId, e.getMessage());
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

    public ShareResponse createShare(String sessionId, String userId, String title, List<Map<String, Object>> messages) {
        ChatSession session = chatSessionMapper.findBySessionId(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new IllegalArgumentException("会话不存在或无权限");
        }
        List<ShareMessage> snapshot = sanitizeShareMessages(messages);
        if (snapshot.isEmpty()) {
            snapshot = listMessages(sessionId, userId).stream()
                    .map(m -> new ShareMessage(m.getRole(), safeContent(m.getContent()), toEpochMillis(m.getCreatedAt())))
                    .limit(MAX_SHARE_MESSAGES)
                    .toList();
        }
        if (snapshot.isEmpty()) {
            throw new IllegalArgumentException("空会话无法分享");
        }

        String shareId = newShareId();
        Instant expiresAt = Instant.now().plus(SHARE_TTL_DAYS, ChronoUnit.DAYS);
        try {
            ChatShare share = new ChatShare();
            share.setShareId(shareId);
            share.setSessionId(sessionId);
            share.setUserId(userId);
            share.setTitle(truncate((title == null || title.isBlank()) ? session.getTitle() : title, 80));
            share.setSnapshotJson(objectMapper.writeValueAsString(snapshot));
            share.setExpiresAt(expiresAt);
            chatShareMapper.insert(share);
        } catch (Exception e) {
            log.warn("创建会话分享失败 sessionId={}: {}", sessionId, e.getMessage());
            throw new IllegalStateException("创建分享失败");
        }
        return new ShareResponse(shareId, expiresAt);
    }

    public SharedSession readShare(String shareId) {
        ChatShare share = chatShareMapper.findByShareId(shareId);
        if (share == null || share.getRevokedAt() != null || share.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("分享不存在或已失效");
        }
        try {
            List<ShareMessage> messages = objectMapper.readValue(
                    share.getSnapshotJson(), new TypeReference<List<ShareMessage>>() {});
            return new SharedSession(
                    share.getShareId(),
                    share.getTitle(),
                    messages,
                    share.getCreatedAt(),
                    share.getExpiresAt()
            );
        } catch (Exception e) {
            log.warn("读取会话分享失败 shareId={}: {}", shareId, e.getMessage());
            throw new IllegalStateException("分享内容读取失败");
        }
    }

    public void revokeShare(String shareId, String userId) {
        chatShareMapper.revoke(shareId, userId);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }

    private ChatMessage toChatMessage(String sessionId, String userId, Map<String, Object> input) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setUserId(userId);
        msg.setRole(safeRole(String.valueOf(input.getOrDefault("role", "user"))));
        msg.setContent(safeContent(String.valueOf(input.getOrDefault("content", ""))));
        msg.setCreatedAt(toInstant(input.get("timestamp")));
        return msg;
    }

    private List<ShareMessage> sanitizeShareMessages(List<Map<String, Object>> messages) {
        if (messages == null) return List.of();
        return messages.stream()
                .limit(MAX_SHARE_MESSAGES)
                .map(m -> new ShareMessage(
                        safeRole(String.valueOf(m.getOrDefault("role", "user"))),
                        safeContent(String.valueOf(m.getOrDefault("content", ""))),
                        toEpochMillis(toInstant(m.get("timestamp")))))
                .filter(m -> !m.content().isBlank())
                .toList();
    }

    private String safeRole(String role) {
        return "ai".equals(role) ? "ai" : "user";
    }

    private String safeContent(String content) {
        if (content == null || "null".equals(content)) return "";
        return content.length() <= 20_000 ? content : content.substring(0, 20_000);
    }

    private Instant toInstant(Object timestamp) {
        if (timestamp instanceof Number n) {
            long value = n.longValue();
            if (value > 0) return Instant.ofEpochMilli(value);
        }
        return Instant.now();
    }

    private long toEpochMillis(Instant instant) {
        return instant != null ? instant.toEpochMilli() : Instant.now().toEpochMilli();
    }

    private String newShareId() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record ShareMessage(String role, String content, long timestamp) {}

    public record ShareResponse(String shareId, Instant expiresAt) {}

    public record SharedSession(String shareId, String title, List<ShareMessage> messages,
                                Instant createdAt, Instant expiresAt) {}
}
