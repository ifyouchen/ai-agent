package com.example.aiagent.security.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 邮箱验证码请求 DTO。
 *
 * <p>purpose 支持 register（默认）、reset_password、change_password。
 */
public record EmailCodeRequest(
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        String email,

        @Pattern(regexp = "^(register|reset_password|change_password)$",
                message = "purpose 必须是 register、reset_password 或 change_password")
        String purpose
) {}
