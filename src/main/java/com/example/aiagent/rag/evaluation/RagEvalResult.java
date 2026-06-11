package com.example.aiagent.rag.evaluation;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * RAG 评估结果
 *
 * 参考 RAGAS（RAG Assessment）框架的四项核心指标：
 *
 * ┌─────────────────────┬──────────────────────────────────────────────────────────────────────┐
 * │  指标                │  含义                                                                │
 * ├─────────────────────┼──────────────────────────────────────────────────────────────────────┤
 * │  faithfulness       │  忠实度：答案中的每个陈述都能在检索文档中找到依据（防幻觉）              │
 * │  answerRelevance    │  答案相关性：答案与问题的相关程度（是否在回答问题本身）                  │
 * │  contextRecall      │  上下文召回：检索到的文档覆盖了多少参考答案中的信息                     │
 * │  contextPrecision   │  上下文精确率：检索到的文档中有多少是真正有用的                         │
 * └─────────────────────┴──────────────────────────────────────────────────────────────────────┘
 *
 * 所有分数范围 [0.0, 1.0]，越高越好。
 * null 表示该指标未计算（如无参考答案时无法计算 contextRecall）。
 */
@Data
@Builder
public class RagEvalResult {

    /** 被评估的用户问题 */
    private String question;

    /** RAG 生成的答案 */
    private String generatedAnswer;

    /** 参考答案（ground truth），用于计算 contextRecall */
    private String referenceAnswer;

    /** 检索到的上下文文档列表 */
    private List<String> retrievedContexts;

    // ─── 四项核心指标 ──────────────────────────────────────────────

    /**
     * 忠实度（Faithfulness）：[0, 1]
     * 答案中的陈述有多少比例能从检索到的文档中找到支撑。
     * = 有文档支撑的陈述数 / 答案中的总陈述数
     */
    private Double faithfulness;

    /**
     * 答案相关性（Answer Relevance）：[0, 1]
     * 使用 LLM 从答案中反向生成问题，计算反向生成问题与原问题的语义相似度。
     */
    private Double answerRelevance;

    /**
     * 上下文召回率（Context Recall）：[0, 1]
     * 参考答案中有多少陈述可以在检索到的上下文中找到（需要 referenceAnswer）。
     * 为 null 表示无参考答案，无法计算。
     */
    private Double contextRecall;

    /**
     * 上下文精确率（Context Precision）：[0, 1]
     * 检索到的文档块中，有多少是真正有用的（与参考答案相关的排在前面）。
     */
    private Double contextPrecision;

    // ─── 综合评分 ──────────────────────────────────────────────────

    /**
     * 综合 RAG 分数（所有非 null 指标的算术平均值）
     */
    private Double overallScore;

    // ─── 元信息 ────────────────────────────────────────────────────

    /** 评估耗时（毫秒） */
    private long evalTimeMs;

    /** 评估时间 */
    private Instant evaluatedAt;

    /** LLM 评估器使用的模型名称 */
    private String evaluatorModel;

    /** 详细的评估推理过程（可选，用于 debug） */
    private String reasoning;
}

