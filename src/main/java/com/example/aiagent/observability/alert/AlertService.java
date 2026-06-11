package com.example.aiagent.observability.alert;

import com.example.aiagent.observability.service.TokenUsageService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 应用层告警服务
 *
 * 三类告警：
 * 1. 错误率告警：近5分钟错误率超过 5%
 * 2. P99 延迟告警：P99 延迟超过 30 秒
 * 3. 成本预算告警：日费用超过预算上限
 *
 * 生产环境建议同时配置 Alertmanager 规则（Prometheus 侧），
 * 本类作为应用层兜底，也可直接对接钉钉/企微机器人。
 *
 * Prometheus 告警规则参考（prometheus-rules.yml）：
 * ─────────────────────────────────────────────
 * groups:
 *   - name: llm_alerts
 *     rules:
 *       - alert: LlmHighErrorRate
 *         expr: rate(llm_calls_total{status="error"}[5m]) /
 *               rate(llm_calls_total[5m]) > 0.05
 *         for: 2m
 *         labels:
 *           severity: critical
 *         annotations:
 *           summary: "LLM 错误率过高 {{ $value | humanizePercentage }}"
 *
 *       - alert: LlmHighP99Latency
 *         expr: histogram_quantile(0.99,
 *               rate(llm_call_duration_seconds_bucket[5m])) > 30
 *         for: 5m
 *         labels:
 *           severity: warning
 *         annotations:
 *           summary: "LLM P99 延迟过高 {{ $value }}s"
 *
 *       - alert: LlmDailyCostOverBudget
 *         expr: increase(llm_cost_usd_total[24h]) > 100
 *         labels:
 *           severity: warning
 *         annotations:
 *           summary: "LLM 日费用超预算 ${{ $value }}"
 * ─────────────────────────────────────────────
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final TokenUsageService tokenUsageService;
    private final MeterRegistry meterRegistry;

    @Value("${llm.observability.alert.error-rate-threshold:0.05}")
    private double errorRateThreshold;

    @Value("${llm.observability.alert.p99-latency-threshold-ms:30000}")
    private long p99LatencyThresholdMs;

    @Value("${llm.observability.alert.daily-budget-usd:100.0}")
    private double dailyBudgetUsd;

    /**
     * 每分钟检查错误率
     */
    @Scheduled(fixedRateString = "${llm.observability.alert.error-rate-check-interval-ms:60000}")
    public void checkErrorRate() {
        double errorRate = tokenUsageService.getRecentErrorRate(5);
        if (errorRate > errorRateThreshold) {
            sendAlert("LLM_HIGH_ERROR_RATE",
                    String.format("近5分钟 LLM 错误率 %.1f%% 超过阈值 %.1f%%",
                            errorRate * 100, errorRateThreshold * 100));
        }
    }

    /**
     * 每2分钟检查 P99 延迟
     *
     * 注意：meterRegistry.find().timer() 返回 @Nullable Timer，不是 Optional，
     * 需要先判空再使用。P99 通过 takeSnapshot().percentile() 读取。
     */
    @Scheduled(fixedRateString = "${llm.observability.alert.latency-check-interval-ms:120000}")
    public void checkP99Latency() {
        try {
            // find().timer() 返回可能为 null 的 Timer，需要手动判空
            io.micrometer.core.instrument.Timer timer =
                    meterRegistry.find("llm_call_duration_seconds").timer();

            if (timer == null) {
                log.debug("P99 延迟检查跳过：指标尚未产生数据");
                return;
            }

            // takeSnapshot() 获取快照，percentile() 返回的是秒，乘以 1000 换算成毫秒
            double p99Ms = timer.takeSnapshot()
                    .percentile(0.99, TimeUnit.MILLISECONDS);

            if (p99Ms > p99LatencyThresholdMs) {
                sendAlert("LLM_HIGH_P99_LATENCY",
                        String.format("LLM P99 延迟 %.0fms 超过阈值 %dms",
                                p99Ms, p99LatencyThresholdMs));
            }
        } catch (Exception e) {
            log.debug("P99 延迟检查失败: {}", e.getMessage());
        }
    }

    /**
     * 每30分钟检查日费用预算
     *
     * 注意：Counter.count() 是累计值（应用启动到现在的总量），
     * 不能直接当"今日费用"用。真正的今日费用应从 MySQL 查询。
     * 这里保留 Prometheus 侧的快速检查作为辅助，精确数据用 TokenUsageService。
     */
    @Scheduled(fixedRateString = "${llm.observability.alert.budget-check-interval-ms:1800000}")
    public void checkDailyBudget() {
        try {
            // 从 MySQL 查询今日精确费用（比从 Prometheus Counter 读取更准确）
            double todayCost = tokenUsageService.getTodayTotalCost().doubleValue();

            if (todayCost > dailyBudgetUsd) {
                sendAlert("LLM_BUDGET_EXCEEDED",
                        String.format("今日 LLM 费用 $%.2f 已超预算 $%.2f",
                                todayCost, dailyBudgetUsd));
            } else if (todayCost > dailyBudgetUsd * 0.8) {
                sendAlert("LLM_BUDGET_WARNING",
                        String.format("今日 LLM 费用 $%.2f 已达预算 80%%（预算 $%.2f）",
                                todayCost, dailyBudgetUsd));
            }
        } catch (Exception e) {
            log.debug("预算检查失败: {}", e.getMessage());
        }
    }

    /**
     * 发送告警通知
     * 实际项目接入钉钉/企微/邮件/PagerDuty
     */
    private void sendAlert(String alertType, String message) {
        log.warn("[ALERT][{}] {}", alertType, message);
        // TODO: 接入企业通知渠道
        // dingTalkClient.sendAlert(alertType, message);
        // wechatWorkClient.sendAlert(alertType, message);
    }
}
