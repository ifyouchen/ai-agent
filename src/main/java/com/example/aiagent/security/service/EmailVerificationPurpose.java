package com.example.aiagent.security.service;

/**
 * 邮箱验证码用途。
 */
public enum EmailVerificationPurpose {
    REGISTER("注册账号"),
    RESET_PASSWORD("重置密码");

    private final String displayName;

    EmailVerificationPurpose(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
