package com.example.aiagent.billing.service;

import com.example.aiagent.billing.config.BillingProperties;
import com.example.aiagent.billing.entity.RechargeOrder;
import com.example.aiagent.billing.exception.BillingException;
import com.example.aiagent.billing.mapper.RechargeOrderMapper;
import com.example.aiagent.billing.model.PaymentPrepareResult;
import com.example.aiagent.billing.model.RechargePackage;
import com.example.aiagent.billing.payment.PaymentGateway;
import com.example.aiagent.billing.payment.PaymentGatewayRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeOrderService {

    private final BillingProperties properties;
    private final RechargePackageCatalog packageCatalog;
    private final RechargeOrderMapper orderMapper;
    private final BillingNumberGenerator numberGenerator;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final BillingWalletService walletService;

    @Transactional
    public RechargeOrder createOrder(String userId, String packageCode, String payChannel) {
        RechargePackage rechargePackage = packageCatalog.require(packageCode);
        String channel = normalizeChannel(payChannel);
        PaymentGateway gateway = gatewayRegistry.require(channel);

        RechargeOrder order = RechargeOrder.builder()
                .orderNo(numberGenerator.orderNo())
                .userId(userId)
                .packageCode(rechargePackage.code())
                .amountCents(rechargePackage.amountCents())
                .payChannel(channel)
                .status("CREATED")
                .expireAt(Instant.now().plusSeconds(properties.getOrderTtlMinutes() * 60L))
                .build();

        PaymentPrepareResult payment = gateway.prepare(order);
        order.setPayQrContent(payment.qrContent());
        order.setProviderTradeNo(payment.providerTradeNo());
        orderMapper.insert(order);
        log.info("充值订单创建成功 userId={} orderNo={} packageCode={} channel={} amountCents={}",
                userId, order.getOrderNo(), order.getPackageCode(), order.getPayChannel(), order.getAmountCents());
        return order;
    }

    public RechargeOrder getUserOrder(String userId, String orderNo) {
        RechargeOrder order = orderMapper.findByOrderNo(orderNo);
        if (order == null || !userId.equals(order.getUserId())) {
            throw BillingException.badRequest("订单不存在");
        }
        return order;
    }

    public List<RechargeOrder> listUserOrders(String userId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(50, Math.max(1, size));
        return orderMapper.listByUser(userId, safeSize, (safePage - 1) * safeSize);
    }

    /**
     * Entry point for verified payment notifications. Gateway adapters must call
     * this only after signature, merchant, order and amount checks pass.
     */
    @Transactional
    public void markPaidAfterVerifiedNotify(String orderNo, String providerTradeNo, long paidAmountCents) {
        RechargeOrder order = orderMapper.findByOrderNoForUpdate(orderNo);
        if (order == null) {
            log.warn("支付通知订单不存在 orderNo={} providerTradeNo={}", orderNo, providerTradeNo);
            throw BillingException.badRequest("订单不存在");
        }
        if ("PAID".equals(order.getStatus())) {
            log.info("支付通知重复到达，订单已入账 orderNo={} providerTradeNo={}", orderNo, providerTradeNo);
            return;
        }
        if (!order.getAmountCents().equals(paidAmountCents)) {
            log.error("支付通知金额不匹配 orderNo={} expectedCents={} actualCents={}",
                    orderNo, order.getAmountCents(), paidAmountCents);
            throw BillingException.conflict("支付金额不匹配");
        }

        int updated = orderMapper.markPaid(orderNo, providerTradeNo, Instant.now());
        if (updated == 0) {
            log.info("支付通知重复处理跳过 orderNo={} providerTradeNo={}", orderNo, providerTradeNo);
            return;
        }
        BigDecimal amountCny = BigDecimal.valueOf(order.getAmountCents()).movePointLeft(2);
        walletService.creditRecharge(order.getUserId(), amountCny, orderNo);
        log.info("支付通知入账完成 userId={} orderNo={} providerTradeNo={} amountCents={}",
                order.getUserId(), orderNo, providerTradeNo, paidAmountCents);
    }

    private String normalizeChannel(String payChannel) {
        String channel = payChannel == null ? "" : payChannel.trim().toUpperCase(Locale.ROOT);
        if (!"ALIPAY".equals(channel) && !"WECHAT".equals(channel)) {
            throw BillingException.badRequest("不支持的支付渠道");
        }
        return channel;
    }
}
