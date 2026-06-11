package com.example.aiagent.kb.repository;

import com.example.aiagent.kb.entity.Chunk;
import com.example.aiagent.kb.entity.Document;
import com.example.aiagent.kb.entity.KnowledgeBase;
import com.example.aiagent.kb.mapper.ChunkMapper;
import com.example.aiagent.kb.mapper.DocumentMapper;
import com.example.aiagent.kb.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 知识库聚合查询门面（Repository Facade）
 *
 * 封装跨多个 Mapper 的复合查询逻辑，提供面向业务的统一查询接口。
 * Service 层通过此类访问数据，而不直接操作多个 Mapper。
 *
 * 职责分界：
 * - Mapper：单表 SQL 操作（CRUD）
 * - Repository：跨表复合查询、业务逻辑组合
 * - Service：事务控制、权限校验、业务规则
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class KnowledgeBaseRepository {

    private final KnowledgeBaseMapper kbMapper;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;

    // ─── 知识库查询 ──────────────────────────────────────────────

    /**
     * 按租户查询所有知识库
     */
    public List<KnowledgeBase> findByTenant(String tenantId) {
        return kbMapper.findByTenantId(tenantId);
    }

    /**
     * 按租户查询激活状态的知识库
     */
    public List<KnowledgeBase> findActiveByTenant(String tenantId) {
        return kbMapper.findByTenantIdAndStatus(tenantId, 1);
    }

    /**
     * 按 ID 查询（返回 Optional）
     */
    public Optional<KnowledgeBase> findById(Long id) {
        return kbMapper.findById(id);
    }

    /**
     * 按租户和名称查询（唯一性校验时使用）
     */
    public Optional<KnowledgeBase> findByTenantAndName(String tenantId, String name) {
        return kbMapper.findByTenantIdAndName(tenantId, name);
    }

    /**
     * 保存知识库（insert，id 由数据库生成后回填）
     */
    public void save(KnowledgeBase kb) {
        kbMapper.insert(kb);
    }

    /**
     * 更新知识库文档计数
     */
    public void updateDocCount(Long kbId, int docCount) {
        kbMapper.updateDocCount(kbId, docCount);
    }

    /**
     * 删除知识库（仅删除知识库记录，级联删除请使用 deleteKnowledgeBaseCascade）
     */
    public void deleteById(Long id) {
        kbMapper.deleteById(id);
    }

    /**
     * 级联删除知识库（切片 → 文档 → 知识库）
     *
     * 在同一事务内完成，保证数据一致性。
     * 删除顺序：先删子表，再删父表，避免外键约束错误。
     */
    @Transactional
    public void deleteKnowledgeBaseCascade(Long kbId) {
        log.info("[KB-REPO] 级联删除知识库 kbId={}", kbId);

        // 1. 查出所有文档
        List<Document> docs = documentMapper.findByKbId(kbId);
        log.debug("[KB-REPO] 待删除文档 {} 个", docs.size());

        // 2. 逐文档删除切片
        for (Document doc : docs) {
            int deleted = chunkMapper.deleteByDocId(doc.getId());
            log.debug("[KB-REPO] 删除文档 {} 的切片 {} 个", doc.getId(), deleted);
        }

        // 3. 逐文档删除
        for (Document doc : docs) {
            documentMapper.deleteById(doc.getId());
        }

        // 4. 删除知识库
        kbMapper.deleteById(kbId);
        log.info("[KB-REPO] 知识库 {} 及 {} 个文档已删除", kbId, docs.size());
    }

    // ─── 文档查询 ──────────────────────────────────────────────

    /**
     * 查询知识库下的所有文档
     */
    public List<Document> findDocumentsByKbId(Long kbId) {
        return documentMapper.findByKbId(kbId);
    }

    /**
     * 查询等待解析的文档（parseStatus = PENDING）
     */
    public List<Document> findPendingDocuments(Long kbId) {
        return documentMapper.findByKbIdAndParseStatus(kbId, "PENDING");
    }

    /**
     * 查询解析失败的文档（parseStatus = FAILED），用于重试
     */
    public List<Document> findFailedDocuments(Long kbId) {
        return documentMapper.findByKbIdAndParseStatus(kbId, "FAILED");
    }

    /**
     * 按文件哈希查询文档（用于重复文件检测）
     */
    public Optional<Document> findDocumentByFileHash(String fileHash) {
        return documentMapper.findByFileHash(fileHash);
    }

    /**
     * 查询文档详情
     */
    public Optional<Document> findDocumentById(Long docId) {
        return documentMapper.findById(docId);
    }

    /**
     * 保存文档
     */
    public void saveDocument(Document document) {
        documentMapper.insert(document);
    }

    /**
     * 更新文档解析状态
     */
    public void updateDocumentStatus(Long docId, String status) {
        documentMapper.updateParseStatus(docId, status);
    }

    /**
     * 更新文档解析失败状态（附带错误信息）
     */
    public void updateDocumentStatusFailed(Long docId, String errorMessage) {
        documentMapper.updateParseStatusWithError(docId, "FAILED", errorMessage);
    }

    /**
     * 删除文档
     */
    public void deleteDocument(Long docId) {
        documentMapper.deleteById(docId);
    }

    /**
     * 统计知识库文档数
     */
    public long countDocuments(Long kbId) {
        return documentMapper.countByKbId(kbId);
    }

    // ─── 切片查询 ──────────────────────────────────────────────

    /**
     * 查询文档下的所有切片
     */
    public List<Chunk> findChunksByDocId(Long docId) {
        return chunkMapper.findByDocId(docId);
    }

    /**
     * 查询知识库下所有激活切片（用于全量重建索引）
     */
    public List<Chunk> findActiveChunksByKbId(Long kbId) {
        return chunkMapper.findActiveByKbId(kbId);
    }

    /**
     * 统计知识库激活切片数
     */
    public long countActiveChunks(Long kbId) {
        return chunkMapper.countByKbIdAndIsActive(kbId, true);
    }

    /**
     * 保存切片
     */
    public void saveChunk(Chunk chunk) {
        chunkMapper.insert(chunk);
    }

    /**
     * 删除文档的所有切片
     */
    public int deleteChunksByDocId(Long docId) {
        return chunkMapper.deleteByDocId(docId);
    }

    // ─── 复合查询（跨多张表） ──────────────────────────────────

    /**
     * 知识库完整摘要（文档数 + 激活切片数）
     * 避免 Service 层调两个 Mapper，此处封装为一次业务查询。
     */
    public KbSummary getKbSummary(Long kbId) {
        long docCount   = documentMapper.countByKbId(kbId);
        long chunkCount = chunkMapper.countByKbIdAndIsActive(kbId, true);
        return new KbSummary(kbId, docCount, chunkCount);
    }

    /**
     * 知识库摘要 VO
     */
    public record KbSummary(Long kbId, long docCount, long chunkCount) {}
}

