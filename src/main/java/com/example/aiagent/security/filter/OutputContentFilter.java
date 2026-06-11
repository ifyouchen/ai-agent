package com.example.aiagent.security.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.*;

/**
 * 输出内容安全过滤器
 *
 * 防止 LLM 输出中泄露敏感信息：
 * 1. 手机号脱敏：138****5678
 * 2. 身份证脱敏：110***********1234
 * 3. 银行卡脱敏：**** **** **** 1234
 * 4. 邮箱脱敏：u***@example.com
 * 5. 内网 IP 脱敏：192.168.1.***
 * 6. 密码/密钥隐藏：password=***REDACTED***
 */
@Slf4j
@Component
public class OutputContentFilter {

    // 中国大陆手机号
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(?<![\\d])(1[3-9]\\d{9})(?![\\d])");

    // 18位身份证
    private static final Pattern ID_CARD_PATTERN =
            Pattern.compile("(?<![\\d])([1-9]\\d{5})(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?![\\d])");

    // 银行卡号（16-19位）
    private static final Pattern BANK_CARD_PATTERN =
            Pattern.compile("(?<![\\d])(\\d{4})[\\s-]?(\\d{4})[\\s-]?(\\d{4})[\\s-]?(\\d{4,7})(?![\\d])");

    // 邮箱
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    // 内网 IP
    private static final Pattern INTERNAL_IP_PATTERN =
            Pattern.compile("(?<![\\d])((?:10|172\\.(?:1[6-9]|2\\d|3[01])|192\\.168)\\.\\d{1,3}\\.)(\\d{1,3})(?![\\d])");

    // 密码/密钥
    private static final Pattern SECRET_PATTERN =
            Pattern.compile("(?i)(password|passwd|pwd|api[_-]?key|secret[_-]?key|access[_-]?token|private[_-]?key)\\s*[:=]\\s*(\\S{6,})",
                    Pattern.CASE_INSENSITIVE);

    public record FilterResult(
            String filteredContent,
            List<String> detectedTypes,
            boolean hasViolation
    ) {}

    /**
     * 对 LLM 输出内容进行脱敏处理
     */
    public FilterResult filter(String content) {
        if (content == null || content.isBlank()) {
            return new FilterResult(content, List.of(), false);
        }

        List<String> detected = new ArrayList<>();
        String result = content;

        // 手机号：138****5678（保留前3位和后4位）
        if (PHONE_PATTERN.matcher(result).find()) {
            result = PHONE_PATTERN.matcher(result).replaceAll(m -> {
                String phone = m.group(1);
                return phone.substring(0, 3) + "****" + phone.substring(7);
            });
            detected.add("手机号");
        }

        // 身份证：110***********5678（保留前3位和后4位）
        if (ID_CARD_PATTERN.matcher(result).find()) {
            result = ID_CARD_PATTERN.matcher(result).replaceAll(m -> {
                String id = m.group(0);
                return id.substring(0, 3) + "***********" + id.substring(id.length() - 4);
            });
            detected.add("身份证号");
        }

        // 银行卡：**** **** **** 1234（只保留后4位）
        if (BANK_CARD_PATTERN.matcher(result).find()) {
            result = BANK_CARD_PATTERN.matcher(result).replaceAll(m ->
                    "**** **** **** " + m.group(4).substring(0, Math.min(4, m.group(4).length())));
            detected.add("银行卡号");
        }

        // 邮箱：u***@example.com（用户名只保留首字母）
        if (EMAIL_PATTERN.matcher(result).find()) {
            result = EMAIL_PATTERN.matcher(result).replaceAll(m -> {
                String email = m.group(0);
                int at = email.indexOf('@');
                return email.charAt(0) + "***" + email.substring(at);
            });
            detected.add("邮箱地址");
        }

        // 内网 IP：192.168.1.***
        if (INTERNAL_IP_PATTERN.matcher(result).find()) {
            result = INTERNAL_IP_PATTERN.matcher(result).replaceAll(m -> m.group(1) + "***");
            detected.add("内网IP");
        }

        // 密码/密钥：password=***REDACTED***
        if (SECRET_PATTERN.matcher(result).find()) {
            result = SECRET_PATTERN.matcher(result).replaceAll(m ->
                    m.group(1) + "=***REDACTED***");
            detected.add("密码/密钥");
            log.warn("[SECURITY] 输出内容中检测到密码/密钥，已脱敏");
        }

        if (!detected.isEmpty()) {
            log.info("[SECURITY] 输出内容脱敏，检测到：{}", detected);
        }

        return new FilterResult(result, detected, false);
    }
}
