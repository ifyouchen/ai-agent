package com.example.aiagent.billing.entity;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RechargeOrder {
    private Long id;
    private String orderNo;
    private String userId;
    private String packageCode;
    private Long amountCents;
    private String payChannel;
    private String status;
    private String providerTradeNo;
    private String payQrContent;
    private Instant paidAt;
    private Instant expireAt;
    private Instant createdAt;
    private Instant updatedAt;
}
