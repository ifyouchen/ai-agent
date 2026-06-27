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
public class UsageReservation {
    private Long id;
    private String reservationNo;
    private String traceId;
    private String sessionId;
    private String userId;
    private String modelName;
    private Integer inputTokensEst;
    private Integer outputTokensEst;
    private BigDecimal reservedCny;
    private BigDecimal actualCny;
    private String status;
    private Instant expiresAt;
    private Instant settledAt;
    private Instant createdAt;
    private Instant updatedAt;

    public boolean enabled() {
        return reservationNo != null && !reservationNo.isBlank();
    }

    public static UsageReservation disabled() {
        return UsageReservation.builder()
                .reservationNo("")
                .reservedCny(BigDecimal.ZERO)
                .status("DISABLED")
                .build();
    }
}
