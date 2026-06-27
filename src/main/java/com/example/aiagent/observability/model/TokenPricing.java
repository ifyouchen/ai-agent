package com.example.aiagent.observability.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 模型定价表（USD / 1M tokens）
 * 定期根据官网价格更新
 */
@Getter
@RequiredArgsConstructor
public enum TokenPricing {

    DEEPSEEK_CHAT("deepseek-chat", 0.14, 0.28),
    DEEPSEEK_REASONER("deepseek-reasoner", 0.55, 2.19),
    DEEPSEEK_V4_FLASH("deepseek-v4-flash", 0.14, 0.28),
    DEEPSEEK_V4_PRO("deepseek-v4-pro", 0.55, 2.19),

    CLAUDE_OPUS_4_8("claude-opus-4-8", 5.0, 25.0),
    CLAUDE_SONNET_4_6("claude-sonnet-4-6", 3.0, 15.0),
    CLAUDE_HAIKU_4_5("claude-haiku-4-5", 1.0, 5.0),

    GPT_4O("gpt-4o", 2.5, 10.0),
    GPT_4O_MINI("gpt-4o-mini", 0.15, 0.6),

    UNKNOWN("unknown", 0.0, 0.0);

    /** 模型 ID */
    private final String modelId;

    /** 输入价格（USD / 1M tokens） */
    private final double inputPricePerMillion;

    /** 输出价格（USD / 1M tokens） */
    private final double outputPricePerMillion;

    /** 根据模型名称查找定价（找不到返回 UNKNOWN） */
    public static TokenPricing of(String modelName) {
        if (modelName == null) return UNKNOWN;
        String lower = modelName.toLowerCase();
        for (TokenPricing p : values()) {
            if (lower.contains(p.modelId.toLowerCase())) {
                return p;
            }
        }
        return UNKNOWN;
    }

    /** 计算本次调用费用（USD） */
    public double calculateCost(int inputTokens, int outputTokens) {
        return (inputTokens * inputPricePerMillion
                + outputTokens * outputPricePerMillion) / 1_000_000.0;
    }

    /** 计算本次调用费用（USD），用于账务链路，避免 double 作为最终金额。 */
    public BigDecimal calculateCostDecimal(int inputTokens, int outputTokens) {
        BigDecimal input = BigDecimal.valueOf(Math.max(inputTokens, 0))
                .multiply(BigDecimal.valueOf(inputPricePerMillion));
        BigDecimal output = BigDecimal.valueOf(Math.max(outputTokens, 0))
                .multiply(BigDecimal.valueOf(outputPricePerMillion));
        return input.add(output)
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
    }
}
