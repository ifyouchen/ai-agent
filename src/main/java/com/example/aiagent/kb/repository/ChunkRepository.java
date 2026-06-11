package com.example.aiagent.kb.repository;

import com.example.aiagent.kb.entity.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 文档切片 Repository
 */
@Repository
public interface ChunkRepository extends JpaRepository<Chunk, Long> {

    /**
     * 查询文档的所有切片（含停用的）
     */
    List<Chunk> findByDocId(Long docId);

    /**
     * 查询文档中指定激活状态的切片
     *
     * @param isActive true=激活中，false=已停用
     */
    List<Chunk> findByDocIdAndIsActive(Long docId, boolean isActive);

    /**
     * 物理删除文档的所有切片（文档删除时级联调用）
     */
    @Modifying
    @Query("DELETE FROM Chunk c WHERE c.docId = :docId")
    void deleteByDocId(@Param("docId") Long docId);

    /**
     * 统计知识库中激活切片数量（用于统计展示）
     */
    long countByKbIdAndIsActive(Long kbId, boolean isActive);

    /**
     * 按知识库查询所有激活切片（批量向量化场景）
     */
    @Query("SELECT c FROM Chunk c WHERE c.kbId = :kbId AND c.isActive = true ORDER BY c.docId, c.chunkIndex")
    List<Chunk> findActiveByKbId(@Param("kbId") Long kbId);
}
