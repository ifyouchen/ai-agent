package com.example.aiagent.chat.controller;

import com.example.aiagent.chat.entity.ChatMessage;
import com.example.aiagent.chat.entity.ChatSession;
import com.example.aiagent.chat.service.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 聊天历史接口
 *
 * <p>提供会话列表、历史消息查询、会话删除等接口，
 * 供前端在页面加载时从服务端恢复历史对话，解决跨浏览器/清缓存后历史丢失的问题。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;

    /**
     * 查询当前用户的会话列表（最近 50 个，按更新时间倒序）
     * 传入 keyword 参数时进行标题模糊搜索（服务端搜索）
     *
     * GET /api/v1/chat/sessions
     * GET /api/v1/chat/sessions?keyword=xxx
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<ChatSession>> listSessions(
            @AuthenticationPrincipal String userId,
            @RequestParam(required = false, defaultValue = "") String keyword) {
        if (keyword.isBlank()) {
            return ResponseEntity.ok(chatHistoryService.listSessions(userId));
        }
        return ResponseEntity.ok(chatHistoryService.searchSessions(userId, keyword));
    }

    /**
     * 更新会话标题（前端双击标题后保存）
     *
     * PATCH /api/v1/chat/sessions/{sessionId}/title
     * Body: {"title": "新标题"}
     */
    @org.springframework.web.bind.annotation.PatchMapping("/sessions/{sessionId}/title")
    public ResponseEntity<String> updateSessionTitle(
            @PathVariable String sessionId,
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        String title = body.get("title");
        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().body("标题不能为空");
        }
        chatHistoryService.updateSessionTitle(sessionId, userId, title.strip());
        return ResponseEntity.ok("已更新");
    }

    /**
     * 查询某个会话的历史消息（最多 200 条，按时间升序）
     *
     * GET /api/v1/chat/sessions/{sessionId}/messages
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<List<ChatMessage>> listMessages(
            @PathVariable String sessionId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(chatHistoryService.listMessages(sessionId, userId));
    }

    /**
     * 删除会话（同时删除该会话的所有消息）
     *
     * DELETE /api/v1/chat/sessions/{sessionId}
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<String> deleteSession(
            @PathVariable String sessionId,
            @AuthenticationPrincipal String userId) {
        chatHistoryService.deleteSession(sessionId, userId);
        log.info("删除会话 userId={} sessionId={}", userId, sessionId);
        return ResponseEntity.ok("已删除会话：" + sessionId);
    }

    /**
     * 批量删除会话（同时删除所选会话的所有消息）
     *
     * POST /api/v1/chat/sessions/batch-delete
     * Body: {"sessionIds": ["session-a", "session-b"]}
     */
    @PostMapping("/sessions/batch-delete")
    public ResponseEntity<String> deleteSessions(
            @RequestBody BatchDeleteRequest request,
            @AuthenticationPrincipal String userId) {
        List<String> sessionIds = request != null ? request.sessionIds() : List.of();
        chatHistoryService.deleteSessions(sessionIds, userId);
        log.info("批量删除会话 userId={} count={}", userId, sessionIds != null ? sessionIds.size() : 0);
        return ResponseEntity.ok("已批量删除会话");
    }

    /**
     * 删除当前用户的全部会话（同时删除所有消息）
     *
     * DELETE /api/v1/chat/sessions
     */
    @DeleteMapping("/sessions")
    public ResponseEntity<String> deleteAllSessions(@AuthenticationPrincipal String userId) {
        chatHistoryService.deleteAllSessions(userId);
        log.info("清空全部会话 userId={}", userId);
        return ResponseEntity.ok("已清空全部会话");
    }

    /**
     * 更新消息反馈（点赞/点踩/撤销）
     *
     * PATCH /api/v1/chat/messages/{messageId}/feedback
     * Body: {"feedback": "up"} | {"feedback": "down"} | {"feedback": null}
     */
    @PatchMapping("/messages/{messageId}/feedback")
    public ResponseEntity<String> updateFeedback(
            @PathVariable Long messageId,
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        try {
            String feedback = body.get("feedback"); // null 表示撤销
            chatHistoryService.updateFeedback(messageId, userId, feedback);
            return ResponseEntity.ok("反馈已记录");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * 前端同步历史数据到服务端（首次登录时，将 localStorage 数据迁移到数据库）
     *
     * POST /api/v1/chat/sessions/sync
     * Body: [ { "id": "session-xxx", "title": "标题", "messages": [ {role, content}, ... ] } ]
     */
    @PostMapping("/sessions/sync")
    public ResponseEntity<String> syncSessions(
            @RequestBody List<Map<String, Object>> sessions,
            @AuthenticationPrincipal String userId) {
        chatHistoryService.syncFromClient(userId, sessions);
        log.info("同步会话历史 userId={} count={}", userId, sessions != null ? sessions.size() : 0);
        return ResponseEntity.ok("同步完成");
    }

    private record BatchDeleteRequest(List<String> sessionIds) {}
}
