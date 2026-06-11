package com.example.aiagent.rag.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * RAG 最终响应，包含答案和引用溯源
 */
@Data
@Builder
public class RagResponse {

    /** LLM 生成的最终答案 */
    private String answer;

    /** 引用的文档片段列表 */
    private List<Citation> citations;

    /** 查询改写后的子查询列表 */
    private List<String> rewrittenQueries;

    /** 检索统计信息 */
    private RetrievalStats stats;

    @Data
    @Builder
    public static class Citation {
        private int number;
        private String chunkId;
        private String documentName;
        private String documentPath;
        private Integer pageNumber;
        /** 引用的具体文本摘录 */
        private String excerpt;
        private double relevanceScore;
    }

    @Data
    @Builder
    public static class RetrievalStats {
        private int totalVectorResults;
        private int totalBm25Results;
        private int afterRrfFusion;
        private int afterReranking;
        private long retrievalTimeMs;
        private long rerankingTimeMs;
        private long generationTimeMs;
    }
}
