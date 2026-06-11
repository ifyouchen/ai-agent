package com.example.aiagent.kb.mapper;

import com.example.aiagent.kb.entity.Chunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文档切片 MyBatis Mapper
 */
@Mapper
public interface ChunkMapper {

    List<Chunk> findByDocId(@Param("docId") Long docId);

    List<Chunk> findByDocIdAndIsActive(@Param("docId") Long docId,
                                        @Param("isActive") boolean isActive);

    int deleteByDocId(@Param("docId") Long docId);

    long countByKbIdAndIsActive(@Param("kbId") Long kbId, @Param("isActive") boolean isActive);

    List<Chunk> findActiveByKbId(@Param("kbId") Long kbId);

    /** 分页查询所有激活切片（用于 BM25 索引恢复） */
    List<Chunk> findActiveChunksPage(@Param("limit") int limit, @Param("offset") int offset);

    void insert(Chunk chunk);
}
