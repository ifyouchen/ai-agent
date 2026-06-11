package com.example.aiagent.kb.entity;

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
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalLog {

    private Long id;

    private String tenantId;

    private Long kbId;

    private String sessionId;

    private String userId;

    /** 用户原始问题 */
    private String query;

    /** 经 QueryRewriter 改写后的查询（可为 null） */
    private String rewrittenQuery;

    /**
     * 检索结果摘要（JSON），存储 top-3 的 chunk_id 和得分：
     * [{"chunk_id":123,"score":0.9521},...]
     */
    private String topChunks;

    /** 最高相似度得分（精度 6 位，4 位小数） */
    private BigDecimal topScore;

    /** 回答类型：ANSWERED | NO_ANSWER | PARTIAL */
    private String answerType;

    /** 向量检索耗时（毫秒） */
    private Integer retrievalMs;

    /** Reranker 耗时（毫秒） */
    private Integer rerankMs;

    /** LLM 生成耗时（毫秒） */
    private Integer generateMs;

    /** 全链路总耗时（毫秒） */
    private Integer totalMs;

    private Instant createdAt;
}
