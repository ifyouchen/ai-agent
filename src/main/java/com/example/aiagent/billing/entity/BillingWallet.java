package com.example.aiagent.billing.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class BillingWallet {
    private Long id;
    private String userId;
    private BigDecimal availableBalanceCny;
    private BigDecimal frozenBalanceCny;
    private BigDecimal totalRechargedCny;
    private BigDecimal totalConsumedCny;
    private String status;
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;
}
