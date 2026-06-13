package com.example.aiagent.security.mail;

import org.springframework.context.ApplicationEvent;

/**
 * 邮件发送事件。
 *
 * <p>由 {@link MailSender} 发布到 Spring 事件总线，
 * {@link MailEventListener} 在 {@code mailTaskExecutor} 线程池中异步消费并真正发送邮件。
 */
public class MailEvent extends ApplicationEvent {

    private final MailMessage message;

    public MailEvent(Object source, MailMessage message) {
        super(source);
        this.message = message;
    }

    public MailMessage getMessage() {
        return message;
    }
}
