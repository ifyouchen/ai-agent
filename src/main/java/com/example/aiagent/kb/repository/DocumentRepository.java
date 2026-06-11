package com.example.aiagent.kb.repository;

import com.example.aiagent.kb.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 文档 Repository
 */
@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    /**
     * 查询知识库下的所有文档
     */
    List<Document> findByKbId(Long kbId);

    /**
     * 按知识库和处理状态查询文档（如查询所有 PENDING 文档进行批量处理）
     */
    List<Document> findByKbIdAndParseStatus(Long kbId, String parseStatus);

    /**
     * 按文件 MD5 查询（用于检测重复上传）
     */
    Optional<Document> findByFileHash(String fileHash);

    /**
     * 统计知识库内的文档数量
     */
    long countByKbId(Long kbId);

    /**
     * 更新文档的处理状态
     *
     * @param docId       文档 ID
     * @param parseStatus 新状态（PARSING / CHUNKING / EMBEDDING / DONE / FAILED）
     */
    @Modifying
    @Query("UPDATE Document d SET d.parseStatus = :parseStatus, d.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE d.id = :docId")
    int updateParseStatus(@Param("docId") Long docId,
                          @Param("parseStatus") String parseStatus);

    /**
     * 更新文档处理状态并记录错误信息（FAILED 时使用）
     */
    @Modifying
    @Query("UPDATE Document d SET d.parseStatus = :parseStatus, d.parseError = :parseError, " +
           "d.updatedAt = CURRENT_TIMESTAMP WHERE d.id = :docId")
    int updateParseStatusWithError(@Param("docId") Long docId,
                                   @Param("parseStatus") String parseStatus,
                                   @Param("parseError") String parseError);
}
