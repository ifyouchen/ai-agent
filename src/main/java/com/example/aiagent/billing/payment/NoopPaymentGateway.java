package com.example.aiagent.billing.payment;

import com.example.aiagent.billing.entity.RechargeOrder;
import com.example.aiagent.billing.model.PaymentPrepareResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NoopPaymentGateway implements PaymentGateway {

    @Override
    public boolean supports(String payChannel) {
        return "ALIPAY".equalsIgnoreCase(payChannel) || "WECHAT".equalsIgnoreCase(payChannel);
    }

    @Override
    public PaymentPrepareResult prepare(RechargeOrder order) {
        log.warn("支付网关未配置，订单仅创建不发起真实支付 orderNo={} channel={} amountCents={}",
                order.getOrderNo(), order.getPayChannel(), order.getAmountCents());
        String content = "PAYMENT_GATEWAY_NOT_CONFIGURED:" + order.getOrderNo();
        return PaymentPrepareResult.pending(content);
    }
}
