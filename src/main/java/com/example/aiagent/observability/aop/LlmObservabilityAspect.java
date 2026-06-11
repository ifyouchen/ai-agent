package com.example.aiagent.observability.aop;

import com.example.aiagent.observability.metrics.LlmMetricsRecorder;
import com.example.aiagent.observability.model.LlmCallContext;
import com.example.aiagent.observability.model.TokenPricing;
import com.example.aiagent.observability.service.TokenUsageService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * LLM 调用可观测性 AOP 切面
 *
 * 拦截 LangChain4j 所有 ChatLanguageModel.generate() 调用，自动：
 * 1. 注入 TraceId（从 MDC 读取，贯穿整个调用链）
 * 2. 统计耗时
 * 3. 提取 Token 用量（从 Response 的 TokenUsage 获取）
 * 4. 计算费用（根据模型定价表）
 * 5. 上报 Prometheus 指标
 * 6. 异步写入 PostgreSQL（供成本报表使用）
 * 7. 记录结构化日志
 *
 * 无需修改任何业务代码，自动生效。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class LlmObservabilityAspect {

    private final LlmMetricsRecorder metricsRecorder;
    private final TokenUsageService tokenUsageService;

    /**
     * 拦截所有 LangChain4j ChatLanguageModel 的 generate 方法
     * 切点覆盖 OpenAI / Anthropic / 本地模型等所有实现
     */
    @Around("execution(* dev.langchain4j.model.chat.ChatLanguageModel.generate(..))")
    public Object observeLlmCall(ProceedingJoinPoint pjp) throws Throwable {
        Instant startTime = Instant.now();
        long startMs = System.currentTimeMillis();

        // 从目标类名推断模型名称（如 OpenAiChatModel → openai）
        String modelName = inferModelName(pjp.getTarget().getClass().getSimpleName());
        String scenario  = MDC.get("scenario");  // 由业务层在调用前 put

        // 记录调用开始（Gauge +1）
        metricsRecorder.recordCallStart(modelName, scenario);

        LlmCallContext.LlmCallContextBuilder ctxBuilder = LlmCallContext.builder()
                .traceId(MDC.get("traceId"))
                .sessionId(MDC.get("sessionId"))
                .userId(MDC.get("userId"))
                .modelName(modelName)
                .scenario(scenario)
                .startTime(startTime);

        // 记录输入摘要（截断，避免敏感信息）
        try {
            String inputSnippet = extractInputSnippet(pjp.getArgs());
            ctxBuilder.inputSnippet(inputSnippet);
        } catch (Exception ignored) {}

        try {
            // ── 执行实际 LLM 调用 ──
            Object result = pjp.proceed();
            long durationMs = System.currentTimeMillis() - startMs;

            // 提取 Token 用量
            TokenUsage tokenUsage = extractTokenUsage(result);
            int inputTokens  = tokenUsage != null ? safeGet(tokenUsage.inputTokenCount())  : 0;
            int outputTokens = tokenUsage != null ? safeGet(tokenUsage.outputTokenCount()) : 0;

            // 计算费用
            double costUsd = TokenPricing.of(modelName).calculateCost(inputTokens, outputTokens);

            // 记录输出摘要
            String outputSnippet = extractOutputSnippet(result);

            LlmCallContext ctx = ctxBuilder
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .durationMs(durationMs)
                    .success(true)
                    .outputSnippet(outputSnippet)
                    .costUsd(costUsd)
                    .build();

            // 上报指标 + 异步持久化
            metricsRecorder.recordCallComplete(ctx);
            tokenUsageService.saveAsync(ctx);

            log.info("[LLM] model={} tokens={}/{} cost=${} duration={}ms traceId={}",
                    modelName, inputTokens, outputTokens,
                    String.format("%.6f", costUsd), durationMs, ctx.getTraceId());

            return result;

        } catch (Throwable e) {
            long durationMs = System.currentTimeMillis() - startMs;

            LlmCallContext ctx = ctxBuilder
                    .durationMs(durationMs)
                    .success(false)
                    .errorMessage(e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();

            metricsRecorder.recordCallComplete(ctx);
            tokenUsageService.saveAsync(ctx);

            log.error("[LLM] 调用失败 model={} duration={}ms error={} traceId={}",
                    modelName, durationMs, e.getMessage(), ctx.getTraceId());

            throw e;
        }
    }

    // ── 工具方法 ──────────────────────────────────────────

    /** 从类名推断模型名称 */
    private String inferModelName(String className) {
        String lower = className.toLowerCase();
        if (lower.contains("openai"))     return "openai";
        if (lower.contains("anthropic"))  return "anthropic";
        if (lower.contains("ollama"))     return "ollama";
        if (lower.contains("deepseek"))   return "deepseek";
        return lower.replace("chatlanguagemodel", "").replace("chatmodel", "");
    }

    /** 从 LangChain4j Response 提取 TokenUsage */
    @SuppressWarnings("unchecked")
    private TokenUsage extractTokenUsage(Object result) {
        try {
            if (result instanceof Response) {
                Response<?> response = (Response<?>) result;
                return response.tokenUsage();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 提取输入内容摘要（只取第一条用户消息前100字） */
    private String extractInputSnippet(Object[] args) {
        if (args == null || args.length == 0) return null;
        String raw = String.valueOf(args[0]);
        return raw.length() > 200 ? raw.substring(0, 200) + "..." : raw;
    }

    /** 提取输出内容摘要 */
    @SuppressWarnings("unchecked")
    private String extractOutputSnippet(Object result) {
        try {
            if (result instanceof Response) {
                Response<?> response = (Response<?>) result;
                Object content = response.content();
                if (content instanceof AiMessage) {
                    AiMessage msg = (AiMessage) content;
                    String text = msg.text();
                    return text != null && text.length() > 200
                            ? text.substring(0, 200) + "..."
                            : text;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private int safeGet(Integer value) {
        return value != null ? value : 0;
    }
}
