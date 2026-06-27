package com.example.aiagent.billing.model;

public record PaymentPrepareResult(
        String payUrl,
        String qrContent,
        String providerTradeNo
) {
    public static PaymentPrepareResult pending(String qrContent) {
        return new PaymentPrepareResult(null, qrContent, null);
    }
}
