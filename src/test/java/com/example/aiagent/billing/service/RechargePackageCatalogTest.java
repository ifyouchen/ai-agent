package com.example.aiagent.billing.service;

import com.example.aiagent.billing.exception.BillingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RechargePackageCatalog")
class RechargePackageCatalogTest {

    private final RechargePackageCatalog catalog = new RechargePackageCatalog();

    @Test
    @DisplayName("返回固定充值套餐")
    void shouldListRechargePackages() {
        assertThat(catalog.list()).hasSize(4);
        assertThat(catalog.require("CNY_100").amountCents()).isEqualTo(10_000);
        assertThat(catalog.require("CNY_100").amountCny()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("无效套餐应拒绝")
    void shouldRejectInvalidPackage() {
        assertThatThrownBy(() -> catalog.require("CNY_999"))
                .isInstanceOf(BillingException.class)
                .hasMessageContaining("无效的充值套餐");
    }
}
