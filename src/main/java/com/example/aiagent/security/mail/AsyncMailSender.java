package com.example.aiagent.security.mail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 异步邮件发送器实现。
 *
 * <p>仅将 {@link MailEvent} 发布到 Spring 事件总线，调用线程立即返回，
 * 邮件 I/O 由 {@link MailEventListener} 在 {@code mailTaskExecutor} 中完成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncMailSender implements MailSender {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void send(MailMessage message) {
        if (message == null || message.getTo() == null || message.getTo().isBlank()) {
            log.warn("[MAIL] 忽略无效的邮件消息：收件人为空");
            return;
        }
        eventPublisher.publishEvent(new MailEvent(this, message));
        log.info("[MAIL] 邮件事件已发布 to={} subject={}", maskEmail(message.getTo()), message.getSubject());
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + email.substring(Math.max(at, 0));
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
