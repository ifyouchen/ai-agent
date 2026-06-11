package com.example.aiagent.rag.evaluation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * RAG 评估 API
 *
 * 所有接口需要 ADMIN 角色，防止普通用户触发高成本的 LLM 评估调用。
 *
 * 接口列表：
 * ┌────────────────────────────────────────────┬──────────────────────────────────────┐
 * │  POST /api/v1/rag/eval/single              │  单条问题评估（自动运行 RAG Pipeline）  │
 * │  POST /api/v1/rag/eval/batch               │  批量测试集评估                        │
 * └────────────────────────────────────────────┴──────────────────────────────────────┘
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag/eval")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class RagEvaluationController {

    private final RagEvaluationService evalService;

    // ─── 单条评估 ──────────────────────────────────────────────

    /**
     * 单条 RAG 评估
     *
     * 请求体示例：
     * {
     *   "question": "公司的退款政策是什么？",
     *   "referenceAnswer": "7天无理由退款，需保持商品完好..."  // 可选
     * }
     *
     * 响应示例：
     * {
     *   "question": "...",
     *   "generatedAnswer": "...",
     *   "faithfulness": 0.89,
     *   "answerRelevance": 0.92,
     *   "contextRecall": 0.85,
     *   "contextPrecision": 0.78,
     *   "overallScore": 0.86,
     *   "evalTimeMs": 3200
     * }
     */
    @PostMapping("/single")
    public ResponseEntity<RagEvalResult> evaluateSingle(@RequestBody SingleEvalRequest request) {
        log.info("[RAG-EVAL] 单条评估请求，问题：'{}'", request.question());

        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        RagEvalResult result = evalService.evaluate(request.question(), request.referenceAnswer());
        return ResponseEntity.ok(result);
    }

    /**
     * 批量测试集评估
     *
     * 请求体示例：
     * {
     *   "testCases": [
     *     { "question": "问题1", "referenceAnswer": "参考答案1" },
     *     { "question": "问题2" }
     *   ]
     * }
     *
     * 响应包含：每条问题的评估结果 + 末尾的汇总统计。
     *
     * ⚠️ 注意：每条评估会调用多次 LLM，批量评估成本较高，建议测试集不超过 50 条。
     */
    @PostMapping("/batch")
    public ResponseEntity<BatchEvalResponse> evaluateBatch(@RequestBody BatchEvalRequest request) {
        if (request.testCases() == null || request.testCases().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.testCases().size() > 100) {
            return ResponseEntity.badRequest().build();
        }

        log.info("[RAG-EVAL] 批量评估请求，共 {} 条", request.testCases().size());

        List<RagEvaluationService.EvalTestCase> cases = request.testCases().stream()
                .map(tc -> RagEvaluationService.EvalTestCase.of(tc.question(), tc.referenceAnswer()))
                .toList();

        List<RagEvalResult> results = evalService.batchEvaluate(cases);

        // 计算汇总统计
        Map<String, Object> summary = computeSummary(results);

        return ResponseEntity.ok(new BatchEvalResponse(results, summary));
    }

    // ─── 工具方法 ──────────────────────────────────────────────

    private Map<String, Object> computeSummary(List<RagEvalResult> results) {
        if (results.isEmpty()) return Map.of();

        double avgFaithfulness = results.stream()
                .filter(r -> r.getFaithfulness() != null)
                .mapToDouble(RagEvalResult::getFaithfulness)
                .average().orElse(0);

        double avgAnswerRelevance = results.stream()
                .filter(r -> r.getAnswerRelevance() != null)
                .mapToDouble(RagEvalResult::getAnswerRelevance)
                .average().orElse(0);

        double avgContextPrecision = results.stream()
                .filter(r -> r.getContextPrecision() != null)
                .mapToDouble(RagEvalResult::getContextPrecision)
                .average().orElse(0);

        double avgOverall = results.stream()
                .filter(r -> r.getOverallScore() != null)
                .mapToDouble(RagEvalResult::getOverallScore)
                .average().orElse(0);

        long totalEvalMs = results.stream().mapToLong(RagEvalResult::getEvalTimeMs).sum();

        return Map.of(
                "totalCases",          results.size(),
                "avgFaithfulness",     Math.round(avgFaithfulness     * 1000) / 1000.0,
                "avgAnswerRelevance",  Math.round(avgAnswerRelevance  * 1000) / 1000.0,
                "avgContextPrecision", Math.round(avgContextPrecision * 1000) / 1000.0,
                "avgOverallScore",     Math.round(avgOverall          * 1000) / 1000.0,
                "totalEvalTimeMs",     totalEvalMs
        );
    }

    // ─── Request / Response Records ────────────────────────────

    public record SingleEvalRequest(String question, String referenceAnswer) {}

    public record BatchTestCase(String question, String referenceAnswer) {}

    public record BatchEvalRequest(List<BatchTestCase> testCases) {}

    public record BatchEvalResponse(List<RagEvalResult> results, Map<String, Object> summary) {}
}

