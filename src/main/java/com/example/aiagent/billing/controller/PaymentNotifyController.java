package com.example.aiagent.billing.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentNotifyController {

    @PostMapping("/alipay/notify")
    public ResponseEntity<String> alipayNotify(@RequestParam Map<String, String> params) {
        log.warn("收到支付宝通知但真实验签适配未启用 outTradeNo={} tradeNo={} tradeStatus={}",
                params.get("out_trade_no"), params.get("trade_no"), params.get("trade_status"));
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body("gateway_not_configured");
    }

    @PostMapping("/wechat/notify")
    public ResponseEntity<Map<String, String>> wechatNotify(@RequestBody(required = false) String body) {
        int payloadLength = body == null ? 0 : body.length();
        log.warn("收到微信支付通知但 API v3 验签/解密适配未启用 payloadLength={}", payloadLength);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("code", "FAIL", "message", "gateway_not_configured"));
    }
}
