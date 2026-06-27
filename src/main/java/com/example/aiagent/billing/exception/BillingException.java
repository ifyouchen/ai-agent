package com.example.aiagent.billing.exception;

import org.springframework.http.HttpStatus;

public class BillingException extends RuntimeException {

    private final HttpStatus status;

    public BillingException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static BillingException badRequest(String message) {
        return new BillingException(HttpStatus.BAD_REQUEST, message);
    }

    public static BillingException paymentRequired(String message) {
        return new BillingException(HttpStatus.PAYMENT_REQUIRED, message);
    }

    public static BillingException conflict(String message) {
        return new BillingException(HttpStatus.CONFLICT, message);
    }
}
