package com.example.aiagent.security.mail;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 邮件事件异步监听器。
 *
 * <p>运行在 {@code mailTaskExecutor} 线程池上，消费 {@link MailEvent} 并调用 SMTP 发送邮件。
 * 该线程池配置了容量为 1000 的有界队列，队列满时由调用方线程兜底执行（CallerRunsPolicy），
 * 保证验证码、邀请等重要邮件不丢失。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailEventListener {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${auth.email.from:}")
    private String emailFrom;

    @Async("mailTaskExecutor")
    @EventListener
    public void onMailEvent(MailEvent event) {
        MailMessage msg = event.getMessage();
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("邮件服务未配置，无法发送邮件 to={}", maskEmail(msg.getTo()));
            return;
        }

        try {
            if (msg.getHtml() != null && !msg.getHtml().isBlank()) {
                sendHtml(mailSender, msg);
            } else {
                sendText(mailSender, msg);
            }
            log.info("邮件发送成功 to={} subject={}", maskEmail(msg.getTo()), msg.getSubject());
        } catch (MailException e) {
            log.error("邮件发送失败 to={} subject={} reason={}",
                    maskEmail(msg.getTo()), msg.getSubject(), e.getMessage(), e);
        }
    }

    private void sendText(JavaMailSender mailSender, MailMessage msg) {
        SimpleMailMessage simple = new SimpleMailMessage();
        String from = resolveFromAddress(mailSender, msg.getFrom());
        if (from != null && !from.isBlank()) {
            simple.setFrom(from);
        }
        simple.setTo(msg.getTo());
        simple.setSubject(msg.getSubject());
        simple.setText(msg.getText() != null ? msg.getText() : "");
        mailSender.send(simple);
    }

    private void sendHtml(JavaMailSender mailSender, MailMessage msg) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            String from = resolveFromAddress(mailSender, msg.getFrom());
            if (from != null && !from.isBlank()) {
                helper.setFrom(from);
            }
            helper.setTo(msg.getTo());
            helper.setSubject(msg.getSubject());
            helper.setText(msg.getHtml(), true);
            mailSender.send(mime);
        } catch (Exception e) {
            throw new MailException("HTML 邮件构造失败", e) {
                private static final long serialVersionUID = 1L;
            };
        }
    }

    private String resolveFromAddress(JavaMailSender mailSender, String explicitFrom) {
        if (explicitFrom != null && !explicitFrom.isBlank()) {
            return explicitFrom;
        }
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
