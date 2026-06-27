package com.example.aiagent.billing.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class BillingNumberGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final SecureRandom RANDOM = new SecureRandom();

    public String orderNo() {
        return next("RC");
    }

    public String ledgerNo() {
        return next("LG");
    }

    public String reservationNo() {
        return next("UR");
    }

    private String next(String prefix) {
        int suffix = RANDOM.nextInt(900_000) + 100_000;
        return prefix + FORMATTER.format(LocalDateTime.now()) + suffix;
    }
}
