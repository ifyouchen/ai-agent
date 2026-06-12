package com.example.aiagent.chat.mapper;

import com.example.aiagent.chat.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 聊天会话 Mapper
 */
@Mapper
public interface ChatSessionMapper {

    /** 保存会话（存在则更新标题和时间） */
    void upsert(ChatSession session);

    /** 查询用户的会话列表（按最新更新时间倒序） */
    List<ChatSession> listByUserId(@Param("userId") String userId, @Param("limit") int limit);

    /** 根据 sessionId 查询会话 */
    ChatSession findBySessionId(@Param("sessionId") String sessionId);

    /** 更新会话标题 */
    void updateTitle(@Param("sessionId") String sessionId, @Param("title") String title);

    /** 删除会话（级联删除消息在业务层处理） */
    void deleteBySessionId(@Param("sessionId") String sessionId);

    /** 按标题或消息内容搜索用户会话 */
    List<ChatSession> searchByUserId(@Param("userId") String userId,
                                     @Param("keyword") String keyword,
                                     @Param("limit") int limit);
}
