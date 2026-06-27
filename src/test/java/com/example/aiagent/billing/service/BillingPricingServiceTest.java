package com.example.aiagent.billing.service;

import com.example.aiagent.billing.config.BillingProperties;
import com.example.aiagent.billing.exception.BillingException;
import com.example.aiagent.billing.model.UsageChargeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BillingPricingService")
class BillingPricingServiceTest {

    private final BillingPricingService service = new BillingPricingService(new BillingProperties());

    @Test
    @DisplayName("按 USD 成本、汇率和倍率折算 CNY")
    void shouldCalculateActualCharge() {
        UsageChargeResult result = service.actualCharge("deepseek-v4-flash", 1000, 2000);

        assertThat(result.costUsd()).isEqualByComparingTo(new BigDecimal("0.00070000"));
        assertThat(result.costCny()).isEqualByComparingTo(new BigDecimal("0.006132"));
    }

    @Test
    @DisplayName("未知模型不允许进入账务链路")
    void shouldRejectUnknownModel() {
        assertThatThrownBy(() -> service.actualCharge("unknown-model", 100, 100))
                .isInstanceOf(BillingException.class)
                .hasMessageContaining("模型未配置计费价格");
    }
}
