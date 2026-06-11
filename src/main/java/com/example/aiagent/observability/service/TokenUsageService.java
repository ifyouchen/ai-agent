package com.example.aiagent.observability.service;

import com.example.aiagent.observability.entity.TokenUsageRecord;
import com.example.aiagent.observability.model.LlmCallContext;
import com.example.aiagent.observability.repository.TokenUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Token 用量统计服务
 * 负责异步持久化和聚合查询（成本报表）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageService {

    private final TokenUsageRepository repository;

    /**
     * 异步写入 PostgreSQL（不阻塞 LLM 调用主链路）
     */
    @Async("observabilityExecutor")
    public void saveAsync(LlmCallContext ctx) {
        try {
            TokenUsageRecord record = TokenUsageRecord.builder()
                    .traceId(ctx.getTraceId())
                    .sessionId(ctx.getSessionId())
                    .userId(ctx.getUserId())
                    .modelName(ctx.getModelName())
                    .scenario(ctx.getScenario())
                    .inputTokens(ctx.getInputTokens())
                    .outputTokens(ctx.getOutputTokens())
                    .totalTokens(ctx.getInputTokens() + ctx.getOutputTokens())
                    .costUsd(BigDecimal.valueOf(ctx.getCostUsd()))
                    .durationMs(ctx.getDurationMs())
                    .success(ctx.isSuccess())
                    .errorMessage(ctx.getErrorMessage())
                    .inputSnippet(ctx.getInputSnippet())
                    .outputSnippet(ctx.getOutputSnippet())
                    .calledAt(ctx.getStartTime() != null ? ctx.getStartTime() : Instant.now())
                    .build();

            repository.save(record);
        } catch (Exception e) {
            // 写入失败不能影响主流程，只记录日志
            log.error("Token 用量写入 PostgreSQL 失败: {}", e.getMessage());
        }
    }

    /**
     * 查询今日全局总费用（用于预算告警）
     */
    public BigDecimal getTodayTotalCost() {
        Instant todayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        // 汇总所有模型今日费用
        return repository.aggregateByModelSince(todayStart).stream()
                .map(row -> (BigDecimal) row[3])
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 查询用户今日费用（用于配额告警）
     */
    public BigDecimal getUserTodayCost(String userId) {
        Instant todayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        return repository.sumCostByUserSince(userId, todayStart);
    }

    /**
     * 成本报表：按模型统计近 N 天用量
     */
    public List<Map<String, Object>> getModelCostReport(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Object[]> rows = repository.aggregateByModelSince(since);

        List<Map<String, Object>> report = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("modelName",    row[0]);
            item.put("inputTokens",  row[1]);
            item.put("outputTokens", row[2]);
            item.put("costUsd",      row[3]);
            report.add(item);
        }
        return report;
    }

    /**
     * 成本报表：按用户统计近 N 天用量（TopN 消费用户）
     */
    public List<Map<String, Object>> getUserCostReport(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Object[]> rows = repository.aggregateByUserSince(since);

        List<Map<String, Object>> report = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId",      row[0]);
            item.put("totalTokens", row[1]);
            item.put("costUsd",     row[2]);
            report.add(item);
        }
        return report;
    }

    /**
     * 查询近 N 分钟的错误率（用于告警判断）
     */
    public double getRecentErrorRate(int minutes) {
        Instant since = Instant.now().minus(minutes, ChronoUnit.MINUTES);
        long errors = repository.countErrorsSince(since);
        long total  = repository.countTotalSince(since);
        return total == 0 ? 0.0 : (double) errors / total;
    }
}
