package com.example.aiagent.billing.model;

import java.math.BigDecimal;

public record RechargePackage(
        String code,
        String name,
        long amountCents,
        BigDecimal amountCny
) {
}
