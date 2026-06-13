package com.example.aiagent.security.mail;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用邮件消息值对象。
 *
 * <p>支持纯文本与 HTML 两种形式，调用方无需关心底层 JavaMailSender 的实现细节。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailMessage {

    /** 收件人邮箱 */
    private String to;

    /** 发件人邮箱（可选，未指定时使用 spring.mail.username） */
    private String from;

    /** 邮件主题 */
    private String subject;

    /** 纯文本内容（与 html 至少填一个） */
    private String text;

    /** HTML 内容（与 text 至少填一个） */
    private String html;
}
