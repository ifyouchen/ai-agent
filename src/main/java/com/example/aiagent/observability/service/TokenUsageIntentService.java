package com.example.aiagent.observability.service;

import com.example.aiagent.security.entity.SysUser;
import com.example.aiagent.security.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic Token usage query handling.
 * Avoids letting the LLM invent or summarize usage numbers.
 */
@Service
@RequiredArgsConstructor
public class TokenUsageIntentService {

    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern TOKEN_QUERY_PATTERN = Pattern.compile(
            "(?i)(token|tokens|用量|消耗|费用|成本|花了多少钱|多少钱|花费)");
    private static final Pattern TODAY_ONLY_PATTERN = Pattern.compile("^(今天|今日)$");

    private final TokenUsageService tokenUsageService;
    private final SysUserMapper sysUserMapper;

    public Optional<Result> resolve(String userId, String text) {
        if (userId == null || userId.isBlank() || text == null) {
            return Optional.empty();
        }
        String normalized = normalize(text);
        if (!isTokenUsageQuery(normalized)) {
            return Optional.empty();
        }
        int days = parseDays(normalized);
        return Optional.of(new Result(days, render(userId, days)));
    }

    public Result query(String userId, int days) {
        int safeDays = Math.max(1, Math.min(30, days));
        return new Result(safeDays, render(userId, safeDays));
    }

    private boolean isTokenUsageQuery(String normalized) {
        return TOKEN_QUERY_PATTERN.matcher(normalized).find()
                || TODAY_ONLY_PATTERN.matcher(normalized).matches();
    }

    private int parseDays(String normalized) {
        if (normalized.contains("最近7天") || normalized.contains("近7天")
                || normalized.contains("最近七天") || normalized.contains("近七天")
                || normalized.contains("7天") || normalized.contains("七天")) {
            return 7;
        }
        if (normalized.contains("最近30天") || normalized.contains("近30天")
                || normalized.contains("最近三十天") || normalized.contains("近三十天")
                || normalized.contains("30天") || normalized.contains("三十天")
                || normalized.contains("一个月") || normalized.contains("最近一个月")
                || normalized.contains("本月")) {
            return 30;
        }
        return 1;
    }

    private String render(String userId, int days) {
        Map<String, Object> summary = tokenUsageService.getUserUsageSummary(userId, days);
        String username = resolveUsername(userId);
        long callCount = toLong(summary.get("callCount"));
        long inputTokens = toLong(summary.get("inputTokens"));
        long outputTokens = toLong(summary.get("outputTokens"));
        long totalTokens = toLong(summary.get("totalTokens"));
        BigDecimal costUsd = toBigDecimal(summary.get("costUsd"));

        String label = days == 1
                ? LocalDate.now(REPORT_ZONE) + "（今天）"
                : "最近 " + days + " 天";
        String lastCalledAt = summary.get("lastCalledAt") != null
                ? String.valueOf(summary.get("lastCalledAt"))
                : "无";

        StringBuilder sb = new StringBuilder();
        sb.append(label).append("的 Token 消耗统计如下：\n\n");
        sb.append("- 用户名：").append(username).append('\n');
        sb.append("- 用户 ID：").append(userId).append('\n');
        sb.append("- 统计口径：Asia/Shanghai 自然日\n");
        sb.append("- 查询区间：").append(summary.get("startAt"))
                .append(" 至 ").append(summary.get("endAt")).append('\n');
        sb.append("- 调用次数：").append(callCount).append('\n');
        sb.append("- 输入 Token：").append(inputTokens).append('\n');
        sb.append("- 输出 Token：").append(outputTokens).append('\n');
        sb.append("- 总 Token：").append(totalTokens).append('\n');
        sb.append("- 总费用：$").append(String.format(Locale.US, "%.8f", costUsd)).append('\n');
        sb.append("- 最近一次记录：").append(lastCalledAt);

        if (callCount == 0) {
            sb.append("\n\n数据库中当前用户该区间为 0 条记录。");
        }
        return sb.toString();
    }

    private String resolveUsername(String userId) {
        if (userId == null || userId.isBlank()) {
            return "未知";
        }
        try {
            return sysUserMapper.findByUserId(userId)
                    .map(SysUser::getUsername)
                    .filter(username -> username != null && !username.isBlank())
                    .orElse("未知");
        } catch (Exception ignored) {
            return "未知";
        }
    }

    private String normalize(String text) {
        return text.trim()
                .replaceAll("\\s+", "")
                .replace("Ｔ", "t")
                .replace("ｔ", "t")
                .toLowerCase(Locale.ROOT);
    }

    private long toLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        if (value == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    public record Result(int days, String answer) {}
}
