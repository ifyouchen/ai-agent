package com.example.aiagent.observability.service;

import com.example.aiagent.observability.entity.TokenUsageRecord;
import com.example.aiagent.observability.mapper.TokenUsageMapper;
import com.example.aiagent.observability.model.LlmCallContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TokenUsageMapper tokenUsageMapper;

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

            tokenUsageMapper.insert(record);
        } catch (Exception e) {
            // 写入失败不能影响主流程，但必须打完整堆栈定位根因
            log.error("Token 用量写入 PostgreSQL 失败 userId={} scenario={} model={} tokens={}/{} reason={}",
                    ctx.getUserId(), ctx.getScenario(), ctx.getModelName(),
                    ctx.getInputTokens(), ctx.getOutputTokens(), e.getMessage(), e);
        }
    }

    /**
     * 查询今日全局总费用（用于预算告警）
     */
    public BigDecimal getTodayTotalCost() {
        Instant todayStart = shanghaiDayStart(0);
        // 汇总所有模型今日费用
        return tokenUsageMapper.aggregateByModelSince(todayStart).stream()
                .map(row -> (BigDecimal) row.get("costUsd"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 查询用户今日费用（用于配额告警）
     */
    public BigDecimal getUserTodayCost(String userId) {
        Instant todayStart = shanghaiDayStart(0);
        return tokenUsageMapper.sumCostByUserSince(userId, todayStart);
    }

    /**
     * 成本报表：按模型统计近 N 天用量
     */
    public List<Map<String, Object>> getModelCostReport(int days) {
        Instant since = shanghaiNaturalDaysStart(days);
        List<Map<String, Object>> rows = tokenUsageMapper.aggregateByModelSince(since);

        List<Map<String, Object>> report = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("modelName",    row.get("modelName"));
            item.put("inputTokens",  row.get("inputTokens"));
            item.put("outputTokens", row.get("outputTokens"));
            item.put("costUsd",      row.get("costUsd"));
            report.add(item);
        }
        return report;
    }

    /**
     * 成本报表：按用户统计近 N 天用量（TopN 消费用户）
     */
    public List<Map<String, Object>> getUserCostReport(int days) {
        Instant since = shanghaiNaturalDaysStart(days);
        List<Map<String, Object>> rows = tokenUsageMapper.aggregateByUserSince(since);

        List<Map<String, Object>> report = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId",      row.get("userId"));
            item.put("totalTokens", row.get("totalTokens"));
            item.put("costUsd",     row.get("costUsd"));
            report.add(item);
        }
        return report;
    }

    /**
     * 个人成本报表：按天统计近 N 天费用趋势（用于个人折线图）
     */
    public List<Map<String, Object>> getUserDailyCostReport(String userId, int days) {
        Instant since = shanghaiNaturalDaysStart(days);
        List<Map<String, Object>> rows = tokenUsageMapper.aggregateDailyByUserSince(userId, since);
        List<Map<String, Object>> report = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("day",         String.valueOf(row.get("day")));
            item.put("costUsd",     row.get("costUsd"));
            item.put("totalTokens", row.get("totalTokens"));
            report.add(item);
        }
        return report;
    }

    /**
     * 成本报表：按天统计近 N 天费用趋势（用于折线图）
     * 返回 [{day: "2026-06-01", costUsd: 0.123, totalTokens: 5000}, ...]
     */
    public List<Map<String, Object>> getDailyCostReport(int days) {
        Instant since = shanghaiNaturalDaysStart(days);
        List<Map<String, Object>> rows = tokenUsageMapper.aggregateDailySince(since);
        List<Map<String, Object>> report = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("day",         String.valueOf(row.get("day")));
            item.put("costUsd",     row.get("costUsd"));
            item.put("totalTokens", row.get("totalTokens"));
            report.add(item);
        }
        return report;
    }

    /**
     * 当前用户近 N 个上海自然日的 Token 区间汇总。
     */
    public Map<String, Object> getUserUsageSummary(String userId, int days) {
        int safeDays = Math.max(1, days);
        Instant since = shanghaiNaturalDaysStart(safeDays);
        Map<String, Object> row = tokenUsageMapper.aggregateUserSummarySince(userId, since);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("userId", userId);
        summary.put("days", safeDays);
        summary.put("startAt", formatShanghai(since));
        summary.put("endAt", formatShanghai(Instant.now()));
        summary.put("timeZone", REPORT_ZONE.getId());
        summary.put("callCount", toLong(row != null ? row.get("callCount") : null));
        summary.put("inputTokens", toLong(row != null ? row.get("inputTokens") : null));
        summary.put("outputTokens", toLong(row != null ? row.get("outputTokens") : null));
        summary.put("totalTokens", toLong(row != null ? row.get("totalTokens") : null));
        summary.put("costUsd", toBigDecimal(row != null ? row.get("costUsd") : null));
        summary.put("lastCalledAt", formatNullableShanghai(row != null ? row.get("lastCalledAt") : null));
        return summary;
    }

    /**
     * 查询近 N 分钟的错误率（用于告警判断）
     */
    public double getRecentErrorRate(int minutes) {
        Instant since = Instant.now().minus(minutes, ChronoUnit.MINUTES);
        long errors = tokenUsageMapper.countErrorsSince(since);
        long total  = tokenUsageMapper.countTotalSince(since);
        return total == 0 ? 0.0 : (double) errors / total;
    }

    private Instant shanghaiNaturalDaysStart(int days) {
        int safeDays = Math.max(1, days);
        return shanghaiDayStart(safeDays - 1L);
    }

    private Instant shanghaiDayStart(long daysAgo) {
        return LocalDate.now(REPORT_ZONE)
                .minusDays(daysAgo)
                .atStartOfDay(REPORT_ZONE)
                .toInstant();
    }

    private String formatShanghai(Instant instant) {
        return DATE_TIME_FORMATTER.format(LocalDateTime.ofInstant(instant, REPORT_ZONE));
    }

    private String formatNullableShanghai(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) {
            return formatShanghai(instant);
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return formatShanghai(timestamp.toInstant());
        }
        if (value instanceof java.time.OffsetDateTime offsetDateTime) {
            return formatShanghai(offsetDateTime.toInstant());
        }
        return String.valueOf(value);
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }
}
