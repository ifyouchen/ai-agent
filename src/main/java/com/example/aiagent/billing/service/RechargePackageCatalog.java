package com.example.aiagent.billing.service;

import com.example.aiagent.billing.exception.BillingException;
import com.example.aiagent.billing.model.RechargePackage;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class RechargePackageCatalog {

    private static final List<RechargePackage> PACKAGES = List.of(
            pkg("CNY_10", "充值 10 元", 1_000),
            pkg("CNY_30", "充值 30 元", 3_000),
            pkg("CNY_100", "充值 100 元", 10_000),
            pkg("CNY_300", "充值 300 元", 30_000)
    );

    public List<RechargePackage> list() {
        return PACKAGES;
    }

    public RechargePackage require(String code) {
        return PACKAGES.stream()
                .filter(item -> item.code().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> BillingException.badRequest("无效的充值套餐"));
    }

    private static RechargePackage pkg(String code, String name, long cents) {
        BigDecimal amount = BigDecimal.valueOf(cents).movePointLeft(2);
        return new RechargePackage(code, name, cents, amount);
    }
}
