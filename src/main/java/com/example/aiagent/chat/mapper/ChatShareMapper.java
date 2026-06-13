package com.example.aiagent.chat.mapper;

import com.example.aiagent.chat.entity.ChatShare;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 会话分享 Mapper。
 */
@Mapper
public interface ChatShareMapper {

    void insert(ChatShare share);

    ChatShare findByShareId(@Param("shareId") String shareId);

    void revoke(@Param("shareId") String shareId, @Param("userId") String userId);
}
