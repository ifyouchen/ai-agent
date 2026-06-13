package com.example.aiagent.security.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 邮箱验证码记录。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailVerificationCode {

    private Long id;
    private String email;
    private String purpose;
    private String codeHash;
    private Instant expiresAt;
    private Instant usedAt;
    private Integer attempts;
    private Instant createdAt;
}
