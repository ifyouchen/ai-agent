package com.example.aiagent.chat.mapper;

import com.example.aiagent.chat.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 聊天消息 Mapper
 */
@Mapper
public interface ChatMessageMapper {

    /** 保存一条消息 */
    void insert(ChatMessage message);

    /** 查询会话的所有消息（按时间升序） */
    List<ChatMessage> listBySessionId(@Param("sessionId") String sessionId,
                                      @Param("limit") int limit);

    /** 删除会话的所有消息 */
    void deleteBySessionId(@Param("sessionId") String sessionId);

    /** 删除当前用户指定会话的所有消息 */
    void deleteByUserIdAndSessionIds(@Param("userId") String userId,
                                     @Param("sessionIds") List<String> sessionIds);

    /** 删除当前用户的所有消息 */
    void deleteByUserId(@Param("userId") String userId);

    /** 更新消息反馈（点赞/点踩，幂等） */
    void updateFeedback(@Param("id") Long id,
                        @Param("userId") String userId,
                        @Param("feedback") String feedback);
}
