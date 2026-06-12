package com.example.aiagent.kb.service;

import com.example.aiagent.kb.entity.Document;
import com.example.aiagent.kb.entity.KnowledgeBase;
import com.example.aiagent.kb.entity.RetrievalLog;
import com.example.aiagent.kb.mapper.ChunkMapper;
import com.example.aiagent.kb.mapper.DocumentMapper;
import com.example.aiagent.kb.mapper.KnowledgeBaseMapper;
import com.example.aiagent.kb.mapper.RetrievalLogMapper;
import com.example.aiagent.rag.retrieval.Bm25Retriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理服务
 *
 * 提供知识库生命周期管理（CRUD）、文档管理、检索日志记录和统计分析功能。
 * 所有写操作均在事务内执行，保证数据一致性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper kbMapper;
    private final DocumentMapper      documentMapper;
    private final ChunkMapper         chunkMapper;
    private final RetrievalLogMapper  retrievalLogMapper;

    /** BM25 检索器，required=false：未启用时 Bean 不存在，注入 null，不影响启动 */
    @Autowired(required = false)
    private Bm25Retriever bm25Retriever;

    // ====================================================================
    // 知识库管理
    // ====================================================================

    /**
     * 创建知识库
     *
     * @param tenantId    租户 ID
     * @param name        知识库名称（同一租户内唯一）
     * @param description 描述（可为 null）
     * @return 已持久化的 KnowledgeBase 对象
     * @throws IllegalArgumentException 若同名知识库已存在
     */
    @Transactional
    public KnowledgeBase createKnowledgeBase(String tenantId, String name, String description, String createdBy) {
        log.info("创建知识库 tenantId={} name={} createdBy={}", tenantId, name, createdBy);

        kbMapper.findByTenantIdAndName(tenantId, name).ifPresent(existing -> {
            throw new IllegalArgumentException(
                    String.format("知识库「%s」在租户 %s 下已存在（id=%d）", name, tenantId, existing.getId()));
        });

        KnowledgeBase kb = KnowledgeBase.builder()
                .tenantId(tenantId)
                .name(name)
                .description(description)
                .createdBy(createdBy)
                .build();

        kbMapper.insert(kb);
        log.info("知识库创建成功 id={} tenantId={} name={}", kb.getId(), tenantId, name);
        return kb;
    }

    /**
     * 列出租户下的所有知识库
     */
    @Transactional(readOnly = true)
    public List<KnowledgeBase> listKnowledgeBases(String tenantId) {
        return kbMapper.findByTenantId(tenantId);
    }

    /**
     * 按 kbId 查询知识库（不校验 tenantId）。
     *
     * <p>专供"通过 kb_member 显式授权、跨组织追加列表"场景使用。
     * 常规业务逻辑请使用 {@link #getKnowledgeBase(String, Long)}（带租户校验）。
     */
    @Transactional(readOnly = true)
    public java.util.Optional<KnowledgeBase> findById(Long kbId) {
        return kbMapper.findById(kbId);
    }

    /**
     * 获取单个知识库（多租户校验）
     *
     * @throws IllegalArgumentException 若知识库不存在或不属于该租户
     */
    @Transactional(readOnly = true)
    public KnowledgeBase getKnowledgeBase(String tenantId, Long kbId) {
        KnowledgeBase kb = kbMapper.findById(kbId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在：kbId=" + kbId));

        if (!kb.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException(
                    String.format("知识库 %d 不属于租户 %s", kbId, tenantId));
        }
        return kb;
    }

    /**
     * 删除知识库（级联删除全部文档和切片）
     *
     * 删除顺序：切片 → 文档 → 知识库，防止外键约束冲突。
     *
     * @throws IllegalArgumentException 若知识库不存在或不属于该租户
     */
    @Transactional
    public void deleteKnowledgeBase(String tenantId, Long kbId) {
        KnowledgeBase kb = getKnowledgeBase(tenantId, kbId);
        log.info("删除知识库 id={} tenantId={} name={}", kbId, tenantId, kb.getName());

        // 1. 查出该知识库下所有文档
        List<Document> docs = documentMapper.findByKbId(kbId);

        // 2. 逐文档删除切片（避免单次大批量 DELETE 锁表）
        for (Document doc : docs) {
            chunkMapper.deleteByDocId(doc.getId());
        }

        // 3. 批量删除文档
        for (Document doc : docs) {
            documentMapper.deleteById(doc.getId());
        }

        // 4. 删除知识库
        kbMapper.deleteById(kbId);

        // 5. 清理 Lucene BM25 索引（按 kbId 删除该知识库下的所有切片索引）
        if (bm25Retriever != null && bm25Retriever.isAvailable()) {
            bm25Retriever.deleteByKbId(String.valueOf(kbId));
        }

        log.info("知识库删除完成 id={} 共删除文档 {} 个", kbId, docs.size());
    }

    // ====================================================================
    // 文档管理
    // ====================================================================

    /**
     * 列出知识库下的所有文档
     *
     * @throws IllegalArgumentException 若知识库不存在或不属于该租户
     */
    @Transactional(readOnly = true)
    public List<Document> getDocuments(String tenantId, Long kbId) {
        // 先校验知识库归属
        getKnowledgeBase(tenantId, kbId);
        return documentMapper.findByKbId(kbId);
    }

    /**
     * 删除文档及其所有切片，同时更新知识库的 docCount
     *
     * @param tenantId 租户 ID（用于权限校验）
     * @param docId    文档 ID
     * @throws IllegalArgumentException 若文档不存在或不属于该租户
     */
    @Transactional
    public void deleteDocument(String tenantId, Long docId) {
        Document doc = documentMapper.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在：docId=" + docId));

        if (!doc.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException(
                    String.format("文档 %d 不属于租户 %s", docId, tenantId));
        }

        log.info("删除文档 docId={} kbId={} name={}", docId, doc.getKbId(), doc.getName());

        // 1. 删除切片
        chunkMapper.deleteByDocId(docId);

        // 2. 删除文档
        documentMapper.deleteById(docId);

        // 3. 清理 Lucene BM25 索引（按文档名删除该文档的切片索引）
        if (bm25Retriever != null && bm25Retriever.isAvailable()) {
            bm25Retriever.deleteByDocumentName(doc.getName());
        }

        // 4. 更新知识库 docCount（减 1，最小为 0）
        kbMapper.findById(doc.getKbId()).ifPresent(kb -> {
            int newCount = Math.max(0, kb.getDocCount() - 1);
            kbMapper.updateDocCount(kb.getId(), newCount);
        });

        log.info("文档删除完成 docId={}", docId);
    }

    // ====================================================================
    // 检索日志
    // ====================================================================

    /**
     * 异步记录检索日志（不阻塞主链路响应）
     *
     * 需要在 Spring 配置中启用 @EnableAsync。
     *
     * @param tenantId   租户 ID
     * @param kbId       知识库 ID
     * @param sessionId  会话 ID
     * @param userId     用户 ID
     * @param query      原始查询
     * @param topScore   最高相似度得分
     * @param answerType 回答类型：ANSWERED | NO_ANSWER | PARTIAL
     * @param totalMs    全链路耗时（毫秒）
     */
    @Async
    public void recordRetrievalLog(String tenantId, Long kbId, String sessionId,
                                   String userId, String query,
                                   double topScore, String answerType, int totalMs) {
        try {
            RetrievalLog log = RetrievalLog.builder()
                    .tenantId(tenantId)
                    .kbId(kbId)
                    .sessionId(sessionId)
                    .userId(userId)
                    .query(query)
                    .topScore(BigDecimal.valueOf(topScore))
                    .answerType(answerType)
                    .totalMs(totalMs)
                    .build();

            retrievalLogMapper.insert(log);
        } catch (Exception e) {
            // 日志记录失败不应影响主流程，仅打印警告
            KnowledgeBaseService.log.warn("检索日志记录失败 tenantId={} kbId={}: {}",
                    tenantId, kbId, e.getMessage());
        }
    }

    // ====================================================================
    // 统计分析
    // ====================================================================

    /**
     * 获取知识库统计信息
     *
     * 返回 Map 包含：
     * - docCount      : 文档总数
     * - chunkCount    : 激活切片总数
     * - recentQueries : 近 7 天查询次数
     * - answerStats   : 近 7 天 answerType 分组统计（{"ANSWERED":120,"NO_ANSWER":30}）
     *
     * @throws IllegalArgumentException 若知识库不存在或不属于该租户
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getStats(String tenantId, Long kbId) {
        // 先校验归属
        getKnowledgeBase(tenantId, kbId);

        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);

        long docCount   = documentMapper.countByKbId(kbId);
        long chunkCount = chunkMapper.countByKbIdAndIsActive(kbId, true);
        long recentQueries = retrievalLogMapper
                .countByTenantIdAndKbIdAndCreatedAtAfter(tenantId, kbId, sevenDaysAgo);

        // 按 answerType 分组统计
        List<Map<String, Object>> answerTypeRows = retrievalLogMapper
                .countGroupByAnswerType(tenantId, kbId, sevenDaysAgo);

        Map<String, Long> answerStats = new HashMap<>();
        for (Map<String, Object> row : answerTypeRows) {
            String type  = row.get("answer_type") != null ? row.get("answer_type").toString() : "UNKNOWN";
            Long   count = ((Number) row.get("cnt")).longValue();
            answerStats.put(type, count);
        }

        // 获取最近 5 条查询记录摘要
        List<RetrievalLog> recentLogs = retrievalLogMapper
                .findRecentByKbId(tenantId, kbId, 5);

        List<Map<String, Object>> recentLogSummary = recentLogs.stream()
                .map(l -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("query",      l.getQuery());
                    item.put("answerType", l.getAnswerType());
                    item.put("topScore",   l.getTopScore());
                    item.put("totalMs",    l.getTotalMs());
                    item.put("createdAt",  l.getCreatedAt());
                    return item;
                })
                .toList();

        Map<String, Object> stats = new HashMap<>();
        stats.put("docCount",      docCount);
        stats.put("chunkCount",    chunkCount);
        stats.put("recentQueries", recentQueries);
        stats.put("answerStats",   answerStats);
        stats.put("recentLogs",    recentLogSummary);

        return stats;
    }
}
