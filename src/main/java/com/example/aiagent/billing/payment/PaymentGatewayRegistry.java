package com.example.aiagent.billing.payment;

import com.example.aiagent.billing.exception.BillingException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class PaymentGatewayRegistry {

    private final List<PaymentGateway> gateways;

    public PaymentGateway require(String payChannel) {
        return gateways.stream()
                .filter(gateway -> gateway.supports(payChannel))
                .findFirst()
                .orElseThrow(() -> BillingException.badRequest("不支持的支付渠道"));
    }
}
