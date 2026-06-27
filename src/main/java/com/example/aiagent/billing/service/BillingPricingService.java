package com.example.aiagent.billing.service;

import com.example.aiagent.billing.config.BillingProperties;
import com.example.aiagent.billing.exception.BillingException;
import com.example.aiagent.billing.model.UsageChargeResult;
import com.example.aiagent.observability.model.TokenPricing;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class BillingPricingService {

    private static final int MONEY_SCALE = 6;

    private final BillingProperties properties;

    public BigDecimal estimateCny(String modelName, int inputTokens) {
        TokenPricing pricing = requirePricing(modelName);
        BigDecimal costUsd = pricing.calculateCostDecimal(
                Math.max(inputTokens, 0), properties.getDefaultMaxOutputTokens());
        BigDecimal costCny = toCny(costUsd);
        if (costCny.compareTo(properties.getMaxSingleCallCny()) > 0) {
            throw BillingException.paymentRequired("本次调用预估费用超过单次上限，请缩短输入或切换模型");
        }
        return costCny;
    }

    public UsageChargeResult actualCharge(String modelName, int inputTokens, int outputTokens) {
        TokenPricing pricing = requirePricing(modelName);
        BigDecimal costUsd = pricing.calculateCostDecimal(Math.max(inputTokens, 0), Math.max(outputTokens, 0));
        return new UsageChargeResult(costUsd, toCny(costUsd));
    }

    private TokenPricing requirePricing(String modelName) {
        TokenPricing pricing = TokenPricing.of(modelName);
        if (pricing == TokenPricing.UNKNOWN) {
            throw BillingException.badRequest("模型未配置计费价格");
        }
        return pricing;
    }

    private BigDecimal toCny(BigDecimal costUsd) {
        return costUsd
                .multiply(properties.getUsdCnyRate())
                .multiply(properties.getMarkupRate())
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
