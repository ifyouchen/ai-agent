package com.example.aiagent.rag.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 检索到的文档片段，携带完整的溯源信息
 */
@Data
@Builder
public class RetrievedChunk {

    private String chunkId;
    private String content;
    private String documentName;
    private String documentPath;
    private Integer pageNumber;
    private Integer chunkIndex;

    /** 租户 ID（多租户过滤核心字段） */
    private String tenantId;

    /** 知识库 ID（按 KB 隔离过滤） */
    private Long kbId;

    /** 向量检索得分（余弦相似度） */
    private double vectorScore;

    /** BM25 关键词检索得分 */
    private double bm25Score;

    /** RRF 融合后的最终得分 */
    private double rrfScore;

    /** Reranker 精排后的得分 */
    private double rerankerScore;

    private Map<String, String> metadata;

    /** 检索来源 */
    private RetrievalSource retrievalSource;

    public enum RetrievalSource {
        VECTOR_ONLY, BM25_ONLY, BOTH
    }
}
