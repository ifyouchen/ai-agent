package com.example.aiagent.billing.entity;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingLedger {
    private Long id;
    private String ledgerNo;
    private String userId;
    private String type;
    private BigDecimal amountCny;
    private BigDecimal balanceAfterCny;
    private String refType;
    private String refId;
    private String idempotencyKey;
    private String remark;
    private Instant createdAt;
}
