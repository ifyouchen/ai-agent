package com.example.aiagent.kb.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 检索日志实体（对应 kb_retrieval_log 表）
 *
 * 记录每次 RAG 检索的完整过程，用于效果分析和 A/B 测试。
 * topChunks 以 JSON 格式存储 top-3 的 chunk_id 和得分，如：
 * [{"chunk_id":123,"score":0.9521},{"chunk_id":456,"score":0.8730}]
 */
@Entity
@Table(name = "kb_retrieval_log",
       indexes = {
           @Index(name = "idx_log_tenant_time", columnList = "tenant_id, created_at"),
           @Index(name = "idx_log_kb_id",       columnList = "kb_id")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "kb_id")
    private Long kbId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "user_id", length = 64)
    private String userId;

    /** 用户原始问题 */
    @Column(name = "query", columnDefinition = "TEXT", nullable = false)
    private String query;

    /** 经 QueryRewriter 改写后的查询（可为 null） */
    @Column(name = "rewritten_query", columnDefinition = "TEXT")
    private String rewrittenQuery;

    /**
     * 检索结果摘要（JSON），存储 top-3 的 chunk_id 和得分：
     * [{"chunk_id":123,"score":0.9521},...]
     */
    @Column(name = "top_chunks", columnDefinition = "TEXT")
    private String topChunks;

    /** 最高相似度得分（精度 6 位，4 位小数） */
    @Column(name = "top_score", precision = 6, scale = 4)
    private BigDecimal topScore;

    /** 回答类型：ANSWERED | NO_ANSWER | PARTIAL */
    @Column(name = "answer_type", length = 32)
    private String answerType;

    /** 向量检索耗时（毫秒） */
    @Column(name = "retrieval_ms")
    private Integer retrievalMs;

    /** Reranker 耗时（毫秒） */
    @Column(name = "rerank_ms")
    private Integer rerankMs;

    /** LLM 生成耗时（毫秒） */
    @Column(name = "generate_ms")
    private Integer generateMs;

    /** 全链路总耗时（毫秒） */
    @Column(name = "total_ms")
    private Integer totalMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
