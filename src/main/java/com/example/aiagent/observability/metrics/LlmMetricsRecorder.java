package com.example.aiagent.observability.metrics;

import com.example.aiagent.observability.model.LlmCallContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prometheus 指标采集器
 *
 * <p>修复：将 Micrometer 指标注册与使用分离。
 * 原实现在每次 {@code recordCallComplete()} 调用时都执行 {@code .register(meterRegistry)}，
 * 会导致同名+同tag 的指标对象重复创建，长时间运行存在内存泄漏风险。
 *
 * <p>修复方案：
 * <ul>
 *   <li>Gauge（activeCalls）：在 Bean 初始化时注册一次，后续只操作 AtomicInteger</li>
 *   <li>Counter / Timer：按 model+scenario+status 标签组合懒加载并缓存到 ConcurrentHashMap，
 *       保证每种标签组合只注册一次</li>
 * </ul>
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

    /** 当前并发调用数（Gauge，全局唯一，启动时注册一次） */
    private final AtomicInteger activeCalls = new AtomicInteger(0);

    // ── 指标缓存（key = "model:scenario:status"）──────────────────
    private final ConcurrentHashMap<String, Counter> callCounters    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer>   callTimers      = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> inputTokenCounters  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> outputTokenCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> costCounters    = new ConcurrentHashMap<>();

    /**
     * Bean 初始化时注册全局 Gauge（只注册一次）
     * Gauge 持有 AtomicInteger 引用，自动反映最新并发数，无需每次重新注册
     */
    @PostConstruct
    public void initGauge() {
        Gauge.builder("llm_active_calls", activeCalls, AtomicInteger::get)
                .description("当前并发 LLM 调用数")
                .register(meterRegistry);
        log.debug("LLM 指标 Gauge 已初始化");
    }

    /**
     * 调用开始时增加并发计数
     */
    public void recordCallStart(String modelName, String scenario) {
        int current = activeCalls.incrementAndGet();
        log.debug("LLM 调用开始，当前并发数：{}", current);
    }

    /**
     * 调用完成后记录所有指标
     */
    public void recordCallComplete(LlmCallContext ctx) {
        activeCalls.decrementAndGet();

        String model    = ctx.getModelName() != null ? ctx.getModelName() : "unknown";
        String scenario = ctx.getScenario()  != null ? ctx.getScenario()  : "unknown";
        String status   = ctx.isSuccess() ? "success" : "error";

        // ── 调用次数 Counter ──────────────────────────────
        String callKey = model + ":" + scenario + ":" + status;
        callCounters.computeIfAbsent(callKey, k ->
                Counter.builder("llm_calls_total")
                        .tag("model",    model)
                        .tag("scenario", scenario)
                        .tag("status",   status)
                        .description("LLM 调用总次数")
                        .register(meterRegistry)
        ).increment();

        // ── 调用延迟 Histogram ────────────────────────────
        // SLO bucket：250ms / 1s / 2s / 5s / 10s / 30s
        String timerKey = model + ":" + scenario + ":" + status;
        callTimers.computeIfAbsent(timerKey, k ->
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
        ).record(Duration.ofMillis(ctx.getDurationMs()));

        if (!ctx.isSuccess()) return;  // 失败时不记录 Token / 费用

        String tokenKey = model + ":" + scenario;

        // ── 输入 Token Counter ────────────────────────────
        inputTokenCounters.computeIfAbsent(tokenKey, k ->
                Counter.builder("llm_input_tokens_total")
                        .tag("model",    model)
                        .tag("scenario", scenario)
                        .description("输入 Token 总数")
                        .register(meterRegistry)
        ).increment(ctx.getInputTokens());

        // ── 输出 Token Counter ────────────────────────────
        outputTokenCounters.computeIfAbsent(tokenKey, k ->
                Counter.builder("llm_output_tokens_total")
                        .tag("model",    model)
                        .tag("scenario", scenario)
                        .description("输出 Token 总数")
                        .register(meterRegistry)
        ).increment(ctx.getOutputTokens());

        // ── 费用 Counter（USD）────────────────────────────
        costCounters.computeIfAbsent(tokenKey, k ->
                Counter.builder("llm_cost_usd_total")
                        .tag("model",    model)
                        .tag("scenario", scenario)
                        .description("LLM 调用费用（USD）")
                        .register(meterRegistry)
        ).increment(ctx.getCostUsd());

        log.debug("指标已记录 model={} tokens={}/{} cost=${} duration={}ms",
                model, ctx.getInputTokens(), ctx.getOutputTokens(),
                String.format("%.6f", ctx.getCostUsd()), ctx.getDurationMs());
    }
}
