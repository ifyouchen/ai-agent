package com.example.aiagent.security.service;

import com.example.aiagent.security.entity.EmailVerificationCode;
import com.example.aiagent.security.mapper.EmailVerificationCodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 注册邮箱验证码服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final String PURPOSE_REGISTER = "REGISTER";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern CODE_PATTERN = Pattern.compile("^\\d{6}$");

    private final EmailVerificationCodeMapper codeMapper;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    @Value("${auth.email.code-ttl-minutes:10}")
    private long codeTtlMinutes;

    @Value("${auth.email.resend-cooldown-seconds:60}")
    private long resendCooldownSeconds;

    @Value("${auth.email.max-attempts:5}")
    private int maxAttempts;

    @Value("${auth.email.from:}")
    private String emailFrom;

    @Transactional
    public void sendRegisterCode(String email) {
        String normalizedEmail = normalizeEmail(email);
        Instant now = Instant.now();
        codeMapper.findLatestByEmailAndPurpose(normalizedEmail, PURPOSE_REGISTER)
                .filter(code -> code.getCreatedAt() != null)
                .filter(code -> code.getCreatedAt().plusSeconds(resendCooldownSeconds).isAfter(now))
                .ifPresent(code -> {
                    throw new IllegalArgumentException("验证码发送太频繁，请稍后再试");
                });

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        ensureMailSenderConfigured(mailSender);

        String code = String.format("%06d", random.nextInt(1_000_000));
        sendEmail(mailSender, normalizedEmail, code);

        EmailVerificationCode record = EmailVerificationCode.builder()
                .email(normalizedEmail)
                .purpose(PURPOSE_REGISTER)
                .codeHash(passwordEncoder.encode(code))
                .expiresAt(now.plusSeconds(Math.max(codeTtlMinutes, 1L) * 60))
                .build();
        codeMapper.insert(record);
        log.info("注册邮箱验证码已发送 email={}", maskEmail(normalizedEmail));
    }

    public void verifyRegisterCode(String email, String code) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedCode = code != null ? code.strip() : "";
        if (!CODE_PATTERN.matcher(normalizedCode).matches()) {
            throw new IllegalArgumentException("验证码必须为 6 位数字");
        }

        EmailVerificationCode record = codeMapper
                .findLatestByEmailAndPurpose(normalizedEmail, PURPOSE_REGISTER)
                .orElseThrow(() -> new IllegalArgumentException("请先获取邮箱验证码"));

        if (record.getUsedAt() != null) {
            throw new IllegalArgumentException("验证码已使用，请重新获取");
        }
        if (record.getExpiresAt() == null || record.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("验证码已过期，请重新获取");
        }
        if (record.getAttempts() != null && record.getAttempts() >= maxAttempts) {
            throw new IllegalArgumentException("验证码错误次数过多，请重新获取");
        }
        if (!passwordEncoder.matches(normalizedCode, record.getCodeHash())) {
            codeMapper.incrementAttempts(record.getId());
            throw new IllegalArgumentException("验证码不正确");
        }

        codeMapper.markUsed(record.getId(), Instant.now());
    }

    private String normalizeEmail(String email) {
        String normalized = email != null ? email.strip().toLowerCase(Locale.ROOT) : "";
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        return normalized;
    }

    private void ensureMailSenderConfigured(JavaMailSender mailSender) {
        if (mailSender == null) {
            throw new IllegalStateException("邮件服务未配置，请检查 SMTP 配置");
        }
        if (mailSender instanceof JavaMailSenderImpl impl
                && (impl.getHost() == null || impl.getHost().isBlank())) {
            throw new IllegalStateException("邮件服务未配置，请检查 spring.mail.host");
        }
    }

    private void sendEmail(JavaMailSender mailSender, String email, String code) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            String from = resolveFromAddress(mailSender);
            if (from != null && !from.isBlank()) {
                message.setFrom(from);
            }
            message.setTo(email);
            message.setSubject("AI Agent 注册验证码");
            message.setText("""
                    您正在注册 AI Agent 账号。

                    验证码：%s

                    该验证码 %d 分钟内有效，请勿转发给他人。
                    如果不是您本人操作，请忽略这封邮件。
                    """.formatted(code, Math.max(codeTtlMinutes, 1L)));
            mailSender.send(message);
        } catch (MailException e) {
            log.warn("注册验证码邮件发送失败 email={} reason={}", maskEmail(email), e.getMessage());
            throw new IllegalStateException("验证码发送失败，请检查邮箱服务配置");
        }
    }

    private String resolveFromAddress(JavaMailSender mailSender) {
        if (emailFrom != null && !emailFrom.isBlank()) {
            return emailFrom;
        }
        if (mailSender instanceof JavaMailSenderImpl impl
                && impl.getUsername() != null
                && !impl.getUsername().isBlank()) {
            return impl.getUsername();
        }
        return null;
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + email.substring(Math.max(at, 0));
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
