package com.example.aiagent.billing.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateRechargeOrderRequest {
    @NotBlank
    private String packageCode;

    @NotBlank
    private String payChannel;
}
