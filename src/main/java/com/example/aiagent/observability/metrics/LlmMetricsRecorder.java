package com.example.aiagent.observability.metrics;

import com.example.aiagent.observability.model.LlmCallContext;
import io.micrometer.core.instrument.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prometheus 指标采集器
 *
 * 采集的指标：
 *   llm_calls_total          - LLM 调用次数（按模型、场景、状态分组）
 *   llm_call_duration_seconds - 调用延迟（Histogram，支持 P50/P95/P99）
 *   llm_input_tokens_total   - 输入 Token 累计
 *   llm_output_tokens_total  - 输出 Token 累计
 *   llm_cost_usd_total       - 费用累计（USD）
 *   llm_active_calls         - 当前并发调用数（Gauge）
 *
 * Grafana 看板核心 PromQL：
 *   # 错误率（5分钟）
 *   rate(llm_calls_total{status="error"}[5m]) / rate(llm_calls_total[5m])
 *
 *   # P99 延迟
 *   histogram_quantile(0.99, rate(llm_call_duration_seconds_bucket[5m]))
 *
 *   # 每小时费用
 *   increase(llm_cost_usd_total[1h])
 *
 *   # 各模型 Token 消耗占比
 *   sum by(model) (rate(llm_input_tokens_total[1h]))
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmMetricsRecorder {

    private final MeterRegistry meterRegistry;

    /** 当前并发调用数（Gauge） */
    private final AtomicInteger activeCalls = new AtomicInteger(0);

    /**
     * 调用开始时增加并发计数
     */
    public void recordCallStart(String modelName, String scenario) {
        int current = activeCalls.incrementAndGet();
        Gauge.builder("llm_active_calls", activeCalls, AtomicInteger::get)
                .tag("model", modelName)
                .tag("scenario", scenario != null ? scenario : "unknown")
                .description("当前并发 LLM 调用数")
                .register(meterRegistry);
        log.debug("LLM 调用开始，当前并发数：{}", current);
    }

    /**
     * 调用完成后记录所有指标
     */
    public void recordCallComplete(LlmCallContext ctx) {
        activeCalls.decrementAndGet();

        String model    = ctx.getModelName() != null ? ctx.getModelName() : "unknown";
        String scenario = ctx.getScenario() != null ? ctx.getScenario() : "unknown";
        String status   = ctx.isSuccess() ? "success" : "error";

        // ── 调用次数 Counter ──────────────────────────────
        Counter.builder("llm_calls_total")
                .tag("model",    model)
                .tag("scenario", scenario)
                .tag("status",   status)
                .description("LLM 调用总次数")
                .register(meterRegistry)
                .increment();

        // ── 调用延迟 Histogram ────────────────────────────
        // SLO bucket：250ms / 1s / 2s / 5s / 10s / 30s
        // 用于计算 P99 以及统计超 SLO 的比例
        Timer.builder("llm_call_duration_seconds")
                .tag("model",    model)
                .tag("scenario", scenario)
                .tag("status",   status)
                .description("LLM 调用延迟")
                .serviceLevelObjectives(
                        Duration.ofMillis(250),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(30))
                .publishPercentiles(0.5, 0.90, 0.95, 0.99)
                .register(meterRegistry)
                .record(Duration.ofMillis(ctx.getDurationMs()));

        if (!ctx.isSuccess()) return;  // 失败时不记录 Token / 费用

        // ── 输入 Token Counter ────────────────────────────
        Counter.builder("llm_input_tokens_total")
                .tag("model",    model)
                .tag("scenario", scenario)
                .description("输入 Token 总数")
                .register(meterRegistry)
                .increment(ctx.getInputTokens());

        // ── 输出 Token Counter ────────────────────────────
        Counter.builder("llm_output_tokens_total")
                .tag("model",    model)
                .tag("scenario", scenario)
                .description("输出 Token 总数")
                .register(meterRegistry)
                .increment(ctx.getOutputTokens());

        // ── 费用 Counter（USD）────────────────────────────
        Counter.builder("llm_cost_usd_total")
                .tag("model",    model)
                .tag("scenario", scenario)
                .description("LLM 调用费用（USD）")
                .register(meterRegistry)
                .increment(ctx.getCostUsd());

        log.debug("指标已记录 model={} tokens={}/{} cost=${} duration={}ms",
                model, ctx.getInputTokens(), ctx.getOutputTokens(),
                String.format("%.6f", ctx.getCostUsd()), ctx.getDurationMs());
    }
}
