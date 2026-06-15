package com.example.aiagent.kb.mapper;

import com.example.aiagent.kb.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 文档 MyBatis Mapper
 */
@Mapper
public interface DocumentMapper {

    List<Document> findByKbId(@Param("kbId") Long kbId);

    List<Document> findByKbIdAndParseStatus(@Param("kbId") Long kbId,
                                             @Param("parseStatus") String parseStatus);

    Optional<Document> findByFileHash(@Param("fileHash") String fileHash);

    long countByKbId(@Param("kbId") Long kbId);

    int updateParseStatus(@Param("id") Long id, @Param("parseStatus") String parseStatus);

    int updateParseStatusWithError(@Param("id") Long id,
                                   @Param("parseStatus") String parseStatus,
                                   @Param("parseError") String parseError);

    int updateChunkCount(@Param("id") Long id, @Param("chunkCount") int chunkCount);

    /** Fix 3: 更新文件存储路径（供解析失败重试时读取） */
    int updateFilePath(@Param("id") Long id, @Param("filePath") String filePath);

    void insert(Document document);

    void deleteById(@Param("id") Long id);

    /** 删除知识库下的所有文档（deleteKnowledgeBase 批量删除优化） */
    int deleteByKbId(@Param("kbId") Long kbId);

    Optional<Document> findById(@Param("id") Long id);
}
