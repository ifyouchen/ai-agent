package com.example.aiagent.billing.payment;

import com.example.aiagent.billing.entity.RechargeOrder;
import com.example.aiagent.billing.model.PaymentPrepareResult;

public interface PaymentGateway {

    boolean supports(String payChannel);

    PaymentPrepareResult prepare(RechargeOrder order);
}
