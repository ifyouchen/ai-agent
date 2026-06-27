package com.example.aiagent.billing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Data
@Component
@ConfigurationProperties(prefix = "billing")
public class BillingProperties {

    /**
     * Disabled by default so existing deployments are not blocked before a real
     * payment gateway is configured.
     */
    private boolean enforcementEnabled = false;

    private BigDecimal usdCnyRate = new BigDecimal("7.30");

    private BigDecimal markupRate = new BigDecimal("1.20");

    private int defaultMaxOutputTokens = 4096;

    private BigDecimal maxSingleCallCny = new BigDecimal("2.000000");

    private BigDecimal lowBalanceWarningCny = new BigDecimal("5.000000");

    private int reservationTtlMinutes = 30;

    private int orderTtlMinutes = 15;
}
