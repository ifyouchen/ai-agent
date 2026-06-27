package com.example.aiagent.billing.controller;

import com.example.aiagent.billing.entity.BillingLedger;
import com.example.aiagent.billing.entity.BillingWallet;
import com.example.aiagent.billing.entity.RechargeOrder;
import com.example.aiagent.billing.mapper.BillingLedgerMapper;
import com.example.aiagent.billing.model.CreateRechargeOrderRequest;
import com.example.aiagent.billing.model.RechargePackage;
import com.example.aiagent.billing.service.BillingWalletService;
import com.example.aiagent.billing.service.RechargeOrderService;
import com.example.aiagent.billing.service.RechargePackageCatalog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class BillingController {

    private final RechargePackageCatalog packageCatalog;
    private final BillingWalletService walletService;
    private final RechargeOrderService orderService;
    private final BillingLedgerMapper ledgerMapper;

    @GetMapping("/api/v1/billing/packages")
    public ResponseEntity<List<RechargePackage>> packages() {
        return ResponseEntity.ok(packageCatalog.list());
    }

    @GetMapping("/api/v1/billing/wallet")
    public ResponseEntity<BillingWallet> wallet(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(walletService.getOrCreateWallet(userId));
    }

    @GetMapping("/api/v1/billing/ledger")
    public ResponseEntity<List<BillingLedger>> ledger(@AuthenticationPrincipal String userId,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(50, Math.max(1, size));
        return ResponseEntity.ok(ledgerMapper.listByUser(userId, safeSize, (safePage - 1) * safeSize));
    }

    @PostMapping("/api/v1/billing/recharge-orders")
    public ResponseEntity<Map<String, Object>> createRechargeOrder(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody CreateRechargeOrderRequest request) {
        RechargeOrder order = orderService.createOrder(userId, request.getPackageCode(), request.getPayChannel());
        log.info("用户创建充值订单 userId={} orderNo={} channel={}", userId, order.getOrderNo(), order.getPayChannel());
        return ResponseEntity.ok(orderResponse(order));
    }

    @GetMapping("/api/v1/billing/recharge-orders")
    public ResponseEntity<List<RechargeOrder>> rechargeOrders(@AuthenticationPrincipal String userId,
                                                              @RequestParam(defaultValue = "1") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(orderService.listUserOrders(userId, page, size));
    }

    @GetMapping("/api/v1/billing/recharge-orders/{orderNo}")
    public ResponseEntity<Map<String, Object>> rechargeOrder(@AuthenticationPrincipal String userId,
                                                             @PathVariable String orderNo) {
        return ResponseEntity.ok(orderResponse(orderService.getUserOrder(userId, orderNo)));
    }

    private Map<String, Object> orderResponse(RechargeOrder order) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", order.getOrderNo());
        result.put("packageCode", order.getPackageCode());
        result.put("amountCents", order.getAmountCents());
        result.put("payChannel", order.getPayChannel());
        result.put("status", order.getStatus());
        result.put("payQrContent", order.getPayQrContent());
        result.put("expireAt", order.getExpireAt());
        result.put("createdAt", order.getCreatedAt());
        return result;
    }
}
