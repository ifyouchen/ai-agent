package com.example.aiagent.rag.evaluation;

import com.example.aiagent.rag.model.RagResponse;
import com.example.aiagent.rag.model.RetrievedChunk;
import com.example.aiagent.rag.pipeline.HybridRagPipeline;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 评估服务（RAGAS 风格，基于 LLM-as-Judge）
 *
 * 使用 LLM 作为评估裁判，对 RAG Pipeline 的输出质量进行自动化评分。
 * 支持批量评估和单条评估两种模式。
 *
 * 评估方法论（RAGAS）：
 * ─────────────────────────────────────────────────────────────────────
 * 1. Faithfulness（忠实度）
 *    ① 让 LLM 从答案中提取所有原子陈述
 *    ② 让 LLM 判断每个陈述是否有检索文档支撑
 *    ③ 分数 = 有支撑的陈述数 / 总陈述数
 *
 * 2. Answer Relevance（答案相关性）
 *    ① 让 LLM 根据答案反向生成 N 个问题
 *    ② 计算反向问题与原问题的语义相似度（此处用 LLM 直接打分简化实现）
 *
 * 3. Context Recall（上下文召回，需要参考答案）
 *    ① 将参考答案分解为原子陈述
 *    ② 统计有多少陈述能在检索上下文中找到支撑
 *    ③ 分数 = 有支撑的陈述数 / 参考答案总陈述数
 *
 * 4. Context Precision（上下文精确率）
 *    ① 遍历每个检索文档块
 *    ② 判断该文档块是否对生成正确答案有用
 *    ③ 计算加权精确率（排名靠前的相关文档权重更高）
 * ─────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagEvaluationService {

    private final ChatLanguageModel chatModel;
    private final HybridRagPipeline ragPipeline;

    // ─────────────────────────────────────────────────────────────
    // 公开 API
    // ─────────────────────────────────────────────────────────────

    /**
     * 单条评估（直接提供问题，自动运行 RAG Pipeline）
     *
     * @param question        用户问题
     * @param referenceAnswer 参考答案（可为 null，null 时跳过 contextRecall）
     */
    public RagEvalResult evaluate(String question, String referenceAnswer) {
        long start = System.currentTimeMillis();
        log.info("[RAG-EVAL] 开始评估，问题：'{}'", question);

        // 1. 运行 RAG Pipeline 获取答案和检索上下文
        RagResponse ragResponse = ragPipeline.execute(question);
        String generatedAnswer = ragResponse.getAnswer();
        List<String> contexts = ragResponse.getCitations().stream()
                .map(RagResponse.Citation::getExcerpt)
                .collect(Collectors.toList());

        return evaluateWithAnswer(question, generatedAnswer, contexts, referenceAnswer, start);
    }

    /**
     * 单条评估（已有答案和上下文，不重新运行 Pipeline）
     *
     * @param question         用户问题
     * @param generatedAnswer  RAG 生成的答案
     * @param retrievedChunks  检索到的文档块
     * @param referenceAnswer  参考答案（可为 null）
     */
    public RagEvalResult evaluateWithChunks(String question, String generatedAnswer,
                                             List<RetrievedChunk> retrievedChunks,
                                             String referenceAnswer) {
        long start = System.currentTimeMillis();
        List<String> contexts = retrievedChunks.stream()
                .map(RetrievedChunk::getContent)
                .collect(Collectors.toList());
        return evaluateWithAnswer(question, generatedAnswer, contexts, referenceAnswer, start);
    }

    /**
     * 批量评估（测试集评估）
     *
     * @param testCases 测试用例列表，每条包含 question（必填）和 referenceAnswer（可选）
     * @return 每条问题的评估结果，最后一条是汇总统计
     */
    public List<RagEvalResult> batchEvaluate(List<EvalTestCase> testCases) {
        log.info("[RAG-EVAL] 开始批量评估，共 {} 条", testCases.size());
        List<RagEvalResult> results = new ArrayList<>();

        for (int i = 0; i < testCases.size(); i++) {
            EvalTestCase tc = testCases.get(i);
            log.info("[RAG-EVAL] 评估第 {}/{} 条：'{}'", i + 1, testCases.size(), tc.question());
            try {
                RagEvalResult result = evaluate(tc.question(), tc.referenceAnswer());
                results.add(result);
            } catch (Exception e) {
                log.error("[RAG-EVAL] 第 {} 条评估失败：{}", i + 1, e.getMessage());
                // 失败条目用 0 分记录，不中断整体评估
                results.add(RagEvalResult.builder()
                        .question(tc.question())
                        .referenceAnswer(tc.referenceAnswer())
                        .faithfulness(0.0)
                        .answerRelevance(0.0)
                        .overallScore(0.0)
                        .evaluatedAt(Instant.now())
                        .reasoning("评估失败：" + e.getMessage())
                        .build());
            }
        }

        // 输出汇总统计
        logSummary(results);
        return results;
    }

    // ─────────────────────────────────────────────────────────────
    // 内部核心逻辑
    // ─────────────────────────────────────────────────────────────

    private RagEvalResult evaluateWithAnswer(String question, String generatedAnswer,
                                              List<String> contexts, String referenceAnswer,
                                              long startMs) {
        String contextText = String.join("\n\n---\n\n", contexts);

        // 并行计算各项指标（实际可用 CompletableFuture 并发，此处顺序计算保持简洁）
        double faithfulness      = evalFaithfulness(generatedAnswer, contextText);
        double answerRelevance   = evalAnswerRelevance(question, generatedAnswer);
        Double contextRecall     = referenceAnswer != null
                ? evalContextRecall(referenceAnswer, contextText) : null;
        double contextPrecision  = evalContextPrecision(question, contexts);

        double overallScore = computeOverall(faithfulness, answerRelevance, contextRecall, contextPrecision);

        long evalTime = System.currentTimeMillis() - startMs;
        log.info("[RAG-EVAL] 完成：faithfulness={:.2f} answerRelevance={:.2f} contextPrecision={:.2f} overall={:.2f} 耗时{}ms",
                faithfulness, answerRelevance, contextPrecision, overallScore, evalTime);

        return RagEvalResult.builder()
                .question(question)
                .generatedAnswer(generatedAnswer)
                .referenceAnswer(referenceAnswer)
                .retrievedContexts(contexts)
                .faithfulness(faithfulness)
                .answerRelevance(answerRelevance)
                .contextRecall(contextRecall)
                .contextPrecision(contextPrecision)
                .overallScore(overallScore)
                .evalTimeMs(evalTime)
                .evaluatedAt(Instant.now())
                .evaluatorModel("llm-as-judge")
                .build();
    }

    /**
     * 忠实度评估
     * 让 LLM 从答案提取原子陈述，再判断每条是否有文档支撑
     */
    private double evalFaithfulness(String answer, String context) {
        String prompt = """
                你是一个 RAG 系统质量评估专家。请评估以下答案的"忠实度"。

                忠实度定义：答案中的每个具体陈述，是否都能在给定的上下文文档中找到明确支撑。

                **上下文文档：**
                %s

                **待评估答案：**
                %s

                **评估步骤：**
                1. 从答案中提取所有具体陈述（每行一条，编号）
                2. 对每条陈述判断：Y=有文档支撑，N=无文档支撑
                3. 计算分数 = Y的数量 / 总陈述数

                **输出格式（严格遵守）：**
                陈述1: Y
                陈述2: N
                ...
                SCORE: 0.XX

                只输出上述格式，不要其他内容。
                """.formatted(truncate(context, 3000), truncate(answer, 1500));

        try {
            String response = chatModel.generate(prompt);
            return parseScore(response);
        } catch (Exception e) {
            log.warn("[RAG-EVAL] 忠实度评估失败：{}", e.getMessage());
            return 0.5; // 无法判断时返回中间值
        }
    }

    /**
     * 答案相关性评估
     * 让 LLM 直接打分：答案是否回答了问题
     */
    private double evalAnswerRelevance(String question, String answer) {
        String prompt = """
                你是一个 RAG 系统质量评估专家。请评估以下答案与问题的"相关性"。

                相关性定义：答案是否直接回答了用户的问题（而不是跑题或回避）。

                **问题：** %s

                **答案：** %s

                **评估标准：**
                - 1.0：完全切题，完整回答了问题
                - 0.7：基本切题，但有部分遗漏
                - 0.4：部分相关，但有较多跑题
                - 0.0：完全不相关，或拒绝回答

                **输出格式（严格遵守）：**
                SCORE: 0.XX

                只输出上述格式，不要其他内容。
                """.formatted(question, truncate(answer, 1500));

        try {
            String response = chatModel.generate(prompt);
            return parseScore(response);
        } catch (Exception e) {
            log.warn("[RAG-EVAL] 答案相关性评估失败：{}", e.getMessage());
            return 0.5;
        }
    }

    /**
     * 上下文召回率评估（需参考答案）
     * 参考答案中有多少信息点能在检索文档中找到
     */
    private double evalContextRecall(String referenceAnswer, String context) {
        String prompt = """
                你是一个 RAG 系统质量评估专家。请评估检索到的上下文对参考答案的"覆盖程度"。

                召回率定义：参考答案中的关键信息点，有多少比例能在上下文文档中找到。

                **参考答案（标准答案）：**
                %s

                **检索到的上下文文档：**
                %s

                **评估步骤：**
                1. 从参考答案中提取关键信息点（每行一条，编号）
                2. 对每个信息点判断：Y=上下文中可以找到，N=上下文中找不到
                3. 分数 = Y的数量 / 总信息点数

                **输出格式（严格遵守）：**
                信息点1: Y
                信息点2: N
                ...
                SCORE: 0.XX

                只输出上述格式，不要其他内容。
                """.formatted(truncate(referenceAnswer, 1000), truncate(context, 2500));

        try {
            String response = chatModel.generate(prompt);
            return parseScore(response);
        } catch (Exception e) {
            log.warn("[RAG-EVAL] 上下文召回率评估失败：{}", e.getMessage());
            return 0.5;
        }
    }

    /**
     * 上下文精确率评估
     * 每个检索到的文档块是否对回答问题有用（加权计算，排名越靠前权重越高）
     */
    private double evalContextPrecision(String question, List<String> contexts) {
        if (contexts.isEmpty()) return 0.0;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contexts.size(); i++) {
            sb.append(String.format("[%d] %s\n\n", i + 1, truncate(contexts.get(i), 300)));
        }

        String prompt = """
                你是一个 RAG 系统质量评估专家。请评估检索到的文档块对回答问题的"有用程度"。

                **问题：** %s

                **检索到的文档块（按检索排名排列）：**
                %s

                **评估步骤：**
                对每个文档块判断：Y=对回答问题有用，N=对回答问题无用或噪音

                **输出格式（严格遵守）：**
                [1]: Y
                [2]: N
                ...
                SCORE: 0.XX

                注意：SCORE 应为加权平均精确率（排名靠前的有用文档权重更高），
                若所有文档都有用则为 1.0，都无用则为 0.0。

                只输出上述格式，不要其他内容。
                """.formatted(question, sb);

        try {
            String response = chatModel.generate(prompt);
            return parseScore(response);
        } catch (Exception e) {
            log.warn("[RAG-EVAL] 上下文精确率评估失败：{}", e.getMessage());
            return 0.5;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────────────────────

    /**
     * 从 LLM 输出中解析 "SCORE: 0.XX" 格式的分数
     */
    private double parseScore(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) return 0.5;

        // 匹配 SCORE: 后面的数字（支持 0.8 / 0.80 / 1.0 / 1 等格式）
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("SCORE:\\s*([0-9]*\\.?[0-9]+)")
                        .matcher(llmOutput);
        if (matcher.find()) {
            try {
                double score = Double.parseDouble(matcher.group(1));
                return Math.max(0.0, Math.min(1.0, score)); // 确保在 [0,1] 范围内
            } catch (NumberFormatException ignored) {}
        }
        log.debug("[RAG-EVAL] 无法解析分数，原始输出：{}", llmOutput.substring(0, Math.min(200, llmOutput.length())));
        return 0.5;
    }

    /** 计算综合分数（所有非 null 指标的算术平均值） */
    private double computeOverall(double faithfulness, double answerRelevance,
                                   Double contextRecall, double contextPrecision) {
        double sum = faithfulness + answerRelevance + contextPrecision;
        int count = 3;
        if (contextRecall != null) {
            sum += contextRecall;
            count++;
        }
        return Math.round(sum / count * 100.0) / 100.0;
    }

    /** 截断文本，避免超出 Token 限制 */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    /** 打印批量评估汇总统计 */
    private void logSummary(List<RagEvalResult> results) {
        if (results.isEmpty()) return;
        double avgFaithfulness    = results.stream().filter(r -> r.getFaithfulness()    != null)
                .mapToDouble(RagEvalResult::getFaithfulness).average().orElse(0);
        double avgAnswerRelevance = results.stream().filter(r -> r.getAnswerRelevance() != null)
                .mapToDouble(RagEvalResult::getAnswerRelevance).average().orElse(0);
        double avgOverall         = results.stream().filter(r -> r.getOverallScore()    != null)
                .mapToDouble(RagEvalResult::getOverallScore).average().orElse(0);

        log.info("[RAG-EVAL] ╔══════════════════════════════════════╗");
        log.info("[RAG-EVAL] ║         批量评估汇总（{}条）           ║", results.size());
        log.info("[RAG-EVAL] ╠══════════════════════════════════════╣");
        log.info("[RAG-EVAL] ║  avg_faithfulness:     {:.3f}          ║", avgFaithfulness);
        log.info("[RAG-EVAL] ║  avg_answer_relevance: {:.3f}          ║", avgAnswerRelevance);
        log.info("[RAG-EVAL] ║  avg_overall:          {:.3f}          ║", avgOverall);
        log.info("[RAG-EVAL] ╚══════════════════════════════════════╝");
    }

    /**
     * 测试用例（用于批量评估）
     *
     * @param question        用户问题（必填）
     * @param referenceAnswer 参考答案（可为 null，null 时跳过 contextRecall 计算）
     */
    public record EvalTestCase(String question, String referenceAnswer) {
        public static EvalTestCase of(String question) {
            return new EvalTestCase(question, null);
        }
        public static EvalTestCase of(String question, String referenceAnswer) {
            return new EvalTestCase(question, referenceAnswer);
        }
    }
}

