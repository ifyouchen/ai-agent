package com.example.aiagent.kb.service;

import com.example.aiagent.rag.model.RagResponse;
import com.example.aiagent.rag.pipeline.HybridRagPipeline;
import com.example.aiagent.rag.retrieval.HybridRagContentRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 知识库问答服务
 *
 * 在 HybridRagPipeline 基础上增加企业级特性：
 * 1. 置信度评估：低置信度时明确告知用户"未找到相关信息"
 * 2. 多租户隔离：确保只检索当前租户的文档（tenantId + kbId 传递到向量检索和 BM25 过滤）
 * 3. 检索日志：记录每次查询用于效果分析
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseQueryService {

    private final HybridRagPipeline ragPipeline;
    private final KnowledgeBaseService knowledgeBaseService;

    @Value("${kb.confidence-threshold:0.6}")
    private double confidenceThreshold;

    public record QueryResult(
            String answer,
            RagResponse ragResponse,
            boolean answerFound,
            double confidence
    ) {}

    /**
     * 知识库问答（带置信度评估 + 多租户隔离）
     *
     * @param tenantId  租户 ID（多租户隔离，传递到向量检索和 BM25 过滤）
     * @param kbId      知识库 ID（按 KB 隔离过滤）
     * @param userId    用户 ID
     * @param question  用户问题
     */
    public QueryResult query(String tenantId, Long kbId, String userId, String question) {
        long start = System.currentTimeMillis();
        log.info("知识库查询 tenantId={} kbId={} userId={} question='{}'",
                tenantId, kbId, userId, question);

        // 设置 MDC 上下文（供 AOP 切面读取）
        MDC.put("userId",   userId);
        MDC.put("scenario", "kb_query");

        // 设置检索上下文（供 Agent 对话场景的 HybridRagContentRetriever 使用）
        HybridRagContentRetriever.setContext(
                new HybridRagContentRetriever.RetrievalContext(tenantId, kbId));

        try {
            // 传递 tenantId/kbId 到 Pipeline，确保向量检索和 BM25 按租户过滤
            RagResponse ragResponse = ragPipeline.execute(question, tenantId, kbId);

            // 置信度评估：根据引用片段中的最高 Reranker 得分判断
            double confidence = computeConfidence(ragResponse);

            int citationCount = ragResponse.getCitations() != null ? ragResponse.getCitations().size() : 0;
            boolean answerFound = confidence >= confidenceThreshold && citationCount > 0;

            String finalAnswer = answerFound
                    ? ragResponse.getAnswer()
                    : buildNoAnswerMessage(question);

            int totalMs = (int) (System.currentTimeMillis() - start);
            RagResponse.RetrievalStats stats = ragResponse.getStats();
            knowledgeBaseService.recordRetrievalLog(
                    tenantId, kbId, null, userId, question,
                    buildRewrittenQuery(ragResponse),
                    buildTopChunksJson(ragResponse),
                    confidence,
                    answerFound ? "ANSWERED" : "NO_ANSWER",
                    stats != null ? safeInt(stats.getRetrievalTimeMs()) : null,
                    stats != null ? safeInt(stats.getRerankingTimeMs()) : null,
                    stats != null ? safeInt(stats.getGenerationTimeMs()) : null,
                    totalMs
            );

            log.info("查询完成 answerFound={} confidence={} citations={} cost={}ms",
                    answerFound, String.format("%.3f", confidence),
                    citationCount,
                    totalMs);

            return new QueryResult(finalAnswer, ragResponse, answerFound, confidence);

        } finally {
            MDC.remove("scenario");
            // 清除检索上下文
            HybridRagContentRetriever.clearContext();
        }
    }

    /** 未找到答案时的标准回复 */
    private String buildNoAnswerMessage(String question) {
        return String.format(
                "抱歉，在知识库中未找到关于「%s」的相关信息。\n\n" +
                "您可以尝试：\n" +
                "1. 换一种表达方式重新提问\n" +
                "2. 联系相关负责人获取帮助",
                question.length() > 30 ? question.substring(0, 30) + "..." : question
        );
    }

    private double computeConfidence(RagResponse ragResponse) {
        if (ragResponse == null || ragResponse.getCitations() == null || ragResponse.getCitations().isEmpty()) {
            return 0.0;
        }
        return ragResponse.getCitations().stream()
                .mapToDouble(RagResponse.Citation::getRelevanceScore)
                .max()
                .orElse(0.0);
    }

    private String buildRewrittenQuery(RagResponse ragResponse) {
        if (ragResponse == null || ragResponse.getRewrittenQueries() == null) {
            return null;
        }
        return String.join("\n", ragResponse.getRewrittenQueries());
    }

    private String buildTopChunksJson(RagResponse ragResponse) {
        if (ragResponse == null || ragResponse.getCitations() == null || ragResponse.getCitations().isEmpty()) {
            return "[]";
        }

        StringBuilder json = new StringBuilder("[");
        int count = Math.min(ragResponse.getCitations().size(), 5);
        for (int i = 0; i < count; i++) {
            RagResponse.Citation citation = ragResponse.getCitations().get(i);
            if (i > 0) json.append(",");
            json.append("{")
                    .append("\"chunk_id\":\"").append(escapeJson(citation.getChunkId())).append("\",")
                    .append("\"document_name\":\"").append(escapeJson(citation.getDocumentName())).append("\",")
                    .append("\"score\":").append(String.format(java.util.Locale.ROOT, "%.4f", citation.getRelevanceScore()))
                    .append("}");
        }
        json.append("]");
        return json.toString();
    }

    private Integer safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
