package com.example.aiagent.billing.model;

import java.math.BigDecimal;

public record UsageChargeResult(
        BigDecimal costUsd,
        BigDecimal costCny
) {
    public static final UsageChargeResult ZERO =
            new UsageChargeResult(BigDecimal.ZERO, BigDecimal.ZERO);
}
