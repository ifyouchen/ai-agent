package com.example.aiagent.security.mail;

/**
 * 邮件发送门面接口。
 *
 * <p>调用方通过该接口发送邮件，实际发送动作由异步事件监听器完成，
 * 从而避免验证码、邀请等 HTTP 请求线程被 SMTP I/O 阻塞。
 */
public interface MailSender {

    /**
     * 发送邮件。
     *
     * <p>默认实现会发布 {@link MailEvent} 到 Spring 事件总线并立即返回。
     * 邮件的真正投递在 {@code mailTaskExecutor} 线程池中排队执行。
     *
     * @param message 邮件消息
     */
    void send(MailMessage message);
}
