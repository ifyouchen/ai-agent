package com.example.aiagent.security.service;

import com.example.aiagent.security.entity.EmailVerificationCode;
import com.example.aiagent.security.mail.MailMessage;
import com.example.aiagent.security.mail.MailSender;
import com.example.aiagent.security.mapper.EmailVerificationCodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
 * 邮箱验证码服务。
 *
 * <p>支持注册、重置密码、修改密码三种用途。验证码生成与校验同步执行，
 * 邮件发送通过 {@link MailSender} 异步化，避免阻塞 HTTP 请求线程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern CODE_PATTERN = Pattern.compile("^\\d{6}$");

    private final EmailVerificationCodeMapper codeMapper;
    private final MailSender mailSender;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    @Value("${auth.email.code-ttl-minutes:10}")
    private long codeTtlMinutes;

    @Value("${auth.email.resend-cooldown-seconds:60}")
    private long resendCooldownSeconds;

    @Value("${auth.email.max-attempts:5}")
    private int maxAttempts;

    // ─────────────── 公开 API：按用途分派 ───────────────

    @Transactional
    public void sendRegisterCode(String email) {
        sendCode(email, EmailVerificationPurpose.REGISTER);
    }

    @Transactional
    public void sendResetPasswordCode(String email) {
        sendCode(email, EmailVerificationPurpose.RESET_PASSWORD);
    }

    @Transactional
    public void sendChangePasswordCode(String email) {
        sendCode(email, EmailVerificationPurpose.CHANGE_PASSWORD);
    }

    public void verifyRegisterCode(String email, String code) {
        verifyCode(email, code, EmailVerificationPurpose.REGISTER);
    }

    public void verifyResetPasswordCode(String email, String code) {
        verifyCode(email, code, EmailVerificationPurpose.RESET_PASSWORD);
    }

    public void verifyChangePasswordCode(String email, String code) {
        verifyCode(email, code, EmailVerificationPurpose.CHANGE_PASSWORD);
    }

    // ─────────────── 核心实现 ───────────────

    @Transactional
    public void sendCode(String email, EmailVerificationPurpose purpose) {
        String normalizedEmail = normalizeEmail(email);
        Instant now = Instant.now();
        String purposeName = purpose.name();

        codeMapper.findLatestByEmailAndPurpose(normalizedEmail, purposeName)
                .filter(code -> code.getCreatedAt() != null)
                .filter(code -> code.getCreatedAt().plusSeconds(resendCooldownSeconds).isAfter(now))
                .ifPresent(code -> {
                    throw new IllegalArgumentException("验证码发送太频繁，请稍后再试");
                });

        // 同步校验邮件服务是否已配置，避免用户点击后事件被监听器静默跳过
        JavaMailSender mailSenderBean = mailSenderProvider.getIfAvailable();
        ensureMailSenderConfigured(mailSenderBean);

        String code = String.format("%06d", random.nextInt(1_000_000));

        // 异步发送邮件，页面点击后立即返回
        mailSender.send(buildMailMessage(normalizedEmail, code, purpose));

        EmailVerificationCode record = EmailVerificationCode.builder()
                .email(normalizedEmail)
                .purpose(purposeName)
                .codeHash(passwordEncoder.encode(code))
                .expiresAt(now.plusSeconds(Math.max(codeTtlMinutes, 1L) * 60))
                .build();
        codeMapper.insert(record);
        log.info("{}邮箱验证码已发送 email={}", purpose.getDisplayName(), maskEmail(normalizedEmail));
    }

    public void verifyCode(String email, String code, EmailVerificationPurpose purpose) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedCode = code != null ? code.strip() : "";
        if (!CODE_PATTERN.matcher(normalizedCode).matches()) {
            throw new IllegalArgumentException("验证码必须为 6 位数字");
        }

        EmailVerificationCode record = codeMapper
                .findLatestByEmailAndPurpose(normalizedEmail, purpose.name())
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

    private MailMessage buildMailMessage(String email, String code, EmailVerificationPurpose purpose) {
        String displayName = purpose.getDisplayName();
        String subject = "AI Agent " + displayName + "验证码";
        String text = """
                您正在进行 AI Agent %s操作。

                验证码：%s

                该验证码 %d 分钟内有效，请勿转发给他人。
                如果不是您本人操作，请忽略这封邮件。
                """.formatted(displayName, code, Math.max(codeTtlMinutes, 1L));

        String html = buildEmailHtml(displayName, code, Math.max(codeTtlMinutes, 1L));

        return MailMessage.builder()
                .to(email)
                .subject(subject)
                .text(text)
                .html(html)
                .build();
    }

    private String buildEmailHtml(String purposeName, String code, long ttlMinutes) {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"/><style>"
                + "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#f5f5f5;margin:0;padding:20px}"
                + ".container{max-width:480px;margin:0 auto;background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.08)}"
                + ".header{background:#4D6BFE;color:#fff;padding:24px 28px}"
                + ".header h2{margin:0;font-size:18px;font-weight:600}"
                + ".content{padding:28px}"
                + ".purpose{color:#666;font-size:14px;margin-bottom:16px}"
                + ".code{font-size:32px;font-weight:700;letter-spacing:6px;color:#4D6BFE;margin:16px 0}"
                + ".tip{color:#999;font-size:13px;line-height:1.6;margin-top:20px}"
                + ".footer{padding:16px 28px;background:#fafafa;border-top:1px solid #eee;font-size:12px;color:#aaa}"
                + "</style></head><body><div class=\"container\">"
                + "<div class=\"header\"><h2>AI Agent 邮箱验证</h2></div>"
                + "<div class=\"content\"><p class=\"purpose\">您正在进行 <strong>" + purposeName + "</strong> 操作</p>"
                + "<div class=\"code\">" + code + "</div>"
                + "<p class=\"tip\">验证码 " + ttlMinutes + " 分钟内有效，请勿转发给他人。<br/>如非本人操作，请忽略此邮件。</p></div>"
                + "<div class=\"footer\">此邮件由 AI Agent 自动发送，请勿直接回复。</div>"
                + "</div></body></html>";
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

    private String normalizeEmail(String email) {
        String normalized = email != null ? email.strip().toLowerCase(Locale.ROOT) : "";
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        return normalized;
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + email.substring(Math.max(at, 0));
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
