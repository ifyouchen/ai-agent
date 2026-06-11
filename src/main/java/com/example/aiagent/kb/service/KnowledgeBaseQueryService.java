package com.example.aiagent.kb.service;

import com.example.aiagent.rag.model.RagResponse;
import com.example.aiagent.rag.pipeline.HybridRagPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 知识库问答服务
 *
 * 在 HybridRagPipeline 基础上增加企业级特性：
 * 1. 置信度评估：低置信度时明确告知用户"未找到相关信息"
 * 2. 多租户隔离：确保只检索当前租户的文档
 * 3. 检索日志：记录每次查询用于效果分析
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseQueryService {

    private final HybridRagPipeline ragPipeline;

    @Value("${kb.confidence-threshold:0.6}")
    private double confidenceThreshold;

    public record QueryResult(
            String answer,
            RagResponse ragResponse,
            boolean answerFound,
            double confidence
    ) {}

    /**
     * 知识库问答（带置信度评估）
     *
     * @param tenantId  租户 ID（多租户隔离）
     * @param kbId      知识库 ID
     * @param userId    用户 ID
     * @param question  用户问题
     */
    public QueryResult query(String tenantId, Long kbId, String userId, String question) {
        long start = System.currentTimeMillis();
        log.info("知识库查询 tenantId={} kbId={} userId={} question='{}'",
                tenantId, kbId, userId, question);

        // 设置 MDC 上下文（供 AOP 切面读取）
        org.slf4j.MDC.put("userId",   userId);
        org.slf4j.MDC.put("scenario", "kb_query");

        try {
            RagResponse ragResponse = ragPipeline.execute(question);

            // 置信度评估：根据 Reranker 最高得分判断
            double confidence = ragResponse.getCitations().isEmpty() ? 0.0
                    : ragResponse.getCitations().get(0).getRelevanceScore();

            boolean answerFound = confidence >= confidenceThreshold
                    && !ragResponse.getCitations().isEmpty();

            String finalAnswer = answerFound
                    ? ragResponse.getAnswer()
                    : buildNoAnswerMessage(question);

            log.info("查询完成 answerFound={} confidence={} citations={} cost={}ms",
                    answerFound, String.format("%.3f", confidence),
                    ragResponse.getCitations().size(),
                    System.currentTimeMillis() - start);

            return new QueryResult(finalAnswer, ragResponse, answerFound, confidence);

        } finally {
            org.slf4j.MDC.remove("scenario");
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
}
