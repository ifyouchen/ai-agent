package com.example.aiagent.observability.alert;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 告警通知发送器（Spring 异步事件版）
 * <p>
 * 架构：
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  AlertService / 任何调用方                                       │
 * │    alertNotifier.send(type, msg, level)                         │
 * │         │  仅发布事件，同步返回，不阻塞调用线程                    │
 * │         ▼                                                       │
 * │  ApplicationEventPublisher.publishEvent(AlertEvent)             │
 * │         │  Spring 事件总线                                       │
 * │         ▼                                                       │
 * │  AlertEventListener.onAlertEvent()  ← @Async("alertTaskExecutor")│
 * │         │  从专属线程池 + 有界队列（容量 3000）中消费              │
 * │         ▼                                                       │
 * │    doSend()  →  钉钉 / 企微 / 邮件 / 自定义 Webhook             │
 * └─────────────────────────────────────────────────────────────────┘
 * <p>
 * 该类职责：
 * 1. send()：发布 AlertEvent（轻量，同步）
 * 2. doSend()：真正执行各渠道 I/O（由 AlertEventListener 异步调用）
 * <p>
 * 支持四种通知渠道（可同时启用）：
 * 1. 钉钉机器人 Webhook（Markdown 格式）
 * 2. 企业微信机器人 Webhook（文本格式）
 * 3. 邮件告警（SMTP，HTML 格式，支持多收件人）
 * 4. 自定义 Webhook（通用 HTTP 回调）
 */
@Slf4j
@Component
public class AlertNotifier {

    private final RestTemplate restTemplate;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * JavaMailSender：required=false，未配置 SMTP 时不影响启动
     */
    @Autowired(required = false)
    private JavaMailSender mailSender;

    // ---- 钉钉 ----
    @Value("${llm.observability.alert.dingtalk.enabled:false}")
    private boolean dingtalkEnabled;

    @Value("${llm.observability.alert.dingtalk.webhook:}")
    private String dingtalkWebhook;

    // ---- 企业微信 ----
    @Value("${llm.observability.alert.wecom.enabled:false}")
    private boolean wecomEnabled;

    @Value("${llm.observability.alert.wecom.webhook:}")
    private String wecomWebhook;

    // ---- 邮件 ----
    @Value("${llm.observability.alert.email.enabled:false}")
    private boolean emailEnabled;

    /**
     * 收件人，多个用英文逗号分隔
     */
    @Value("${llm.observability.alert.email.to:}")
    private String emailTo;

    @Value("${llm.observability.alert.email.from:ai-agent@example.com}")
    private String emailFrom;

    // ---- 自定义 Webhook ----
    @Value("${llm.observability.alert.custom.enabled:false}")
    private boolean customEnabled;

    @Value("${llm.observability.alert.custom.webhook:}")
    private String customWebhook;

    @Value("${spring.application.name:ai-agent}")
    private String appName;

    public AlertNotifier(RestTemplate restTemplate,
                         ApplicationEventPublisher eventPublisher) {
        this.restTemplate = restTemplate;
        this.eventPublisher = eventPublisher;
    }

    // ─────────────────────────────────────────────────────────────
    // 公开 API：仅发布事件，立即返回（不阻塞调用线程）
    // ─────────────────────────────────────────────────────────────

    /**
     * 发布告警事件。
     * 调用线程立即返回，实际 I/O 由 AlertEventListener 在专属异步线程池中执行。
     *
     * @param alertType 告警类型，如 LLM_HIGH_ERROR_RATE
     * @param message   告警详情
     * @param level     告警级别：WARNING / CRITICAL
     */
    public void send(String alertType, String message, AlertLevel level) {
        // 无论是否有外部渠道，始终先输出日志（Loki/ELK 可采集，且不走队列，实时可见）
        if (level == AlertLevel.CRITICAL) {
            log.error("[ALERT][{}] {}", alertType, message);
        } else {
            log.warn("[ALERT][{}] {}", alertType, message);
        }

        // 发布事件到 Spring 事件总线，AlertEventListener 异步消费
        eventPublisher.publishEvent(new AlertEvent(this, alertType, message, level));
    }

    // ─────────────────────────────────────────────────────────────
    // 包级可见：由 AlertEventListener 在异步线程中调用
    // ─────────────────────────────────────────────────────────────

    /**
     * 实际执行各渠道发送（在 alertTaskExecutor 线程池中运行）。
     * 由 AlertEventListener 调用，不对外暴露。
     */
    void doSend(AlertEvent event) {
        String alertType = event.getAlertType();
        String message = event.getMessage();
        AlertLevel level = event.getLevel();

        // 将事件产生时间格式化（而非消费时间，更准确反映告警发生时刻）
        String time = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(event.getOccurredAt()), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"));

        String levelEmoji = level == AlertLevel.CRITICAL ? "🔴" : "🟡";
        String title = String.format("%s [%s] %s", levelEmoji, appName, alertType);
        String fullMessage = String.format("%s\n时间：%s\n详情：%s", title, time, message);

        if (dingtalkEnabled && !dingtalkWebhook.isBlank()) {
            sendDingtalk(title, fullMessage);
        }
        if (wecomEnabled && !wecomWebhook.isBlank()) {
            sendWecom(fullMessage);
        }
        if (emailEnabled && !emailTo.isBlank()) {
            sendEmail(title, message, level, time);
        }
        if (customEnabled && !customWebhook.isBlank()) {
            sendCustomWebhook(alertType, message, level);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 私有发送实现
    // ─────────────────────────────────────────────────────────────

    // ── 钉钉：Markdown 消息 ────────────────────────────────────

    private void sendDingtalk(String title, String content) {
        try {
            Map<String, Object> body = Map.of(
                    "msgtype", "markdown",
                    "markdown", Map.of(
                            "title", title,
                            "text", content.replace("\n", "\n\n")
                    ),
                    "at", Map.of("isAtAll", false)
            );
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForObject(dingtalkWebhook, new HttpEntity<>(body, headers), Map.class);
            log.debug("[ALERT] 钉钉通知发送成功");
        } catch (Exception e) {
            log.error("[ALERT] 钉钉通知发送失败: {}", e.getMessage());
        }
    }

    // ── 企业微信：文本消息 ─────────────────────────────────────

    private void sendWecom(String content) {
        try {
            Map<String, Object> body = Map.of(
                    "msgtype", "text",
                    "text", Map.of(
                            "content", content,
                            "mentioned_list", List.of()
                    )
            );
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForObject(wecomWebhook, new HttpEntity<>(body, headers), Map.class);
            log.debug("[ALERT] 企微通知发送成功");
        } catch (Exception e) {
            log.error("[ALERT] 企微通知发送失败: {}", e.getMessage());
        }
    }

    // ── 邮件：HTML 格式 ────────────────────────────────────────

    private void sendEmail(String title, String message, AlertLevel level, String time) {
        if (mailSender == null) {
            log.warn("[ALERT] 邮件渠道已启用，但 JavaMailSender 未初始化，请检查 spring.mail.host 配置");
            return;
        }
        try {
            String[] toAddresses = Arrays.stream(emailTo.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);

            if (toAddresses.length == 0) {
                log.warn("[ALERT] 邮件收件人为空，跳过发送");
                return;
            }

            String bgColor = level == AlertLevel.CRITICAL ? "#d32f2f" : "#f57f17";
            String badgeText = level == AlertLevel.CRITICAL ? "CRITICAL" : "WARNING";
            String html = buildEmailHtml(title, message, badgeText, bgColor, time);

            MimeMessage mimeMsg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMsg, true, "UTF-8");
            helper.setFrom(emailFrom);
            helper.setTo(toAddresses);
            helper.setSubject(String.format("[%s][%s] %s", badgeText, appName, title));
            helper.setText(html, true);
            mailSender.send(mimeMsg);
            log.info("[ALERT] 邮件告警已发送至 {} 个收件人", toAddresses.length);
        } catch (Exception e) {
            log.error("[ALERT] 邮件告警发送失败: {}", e.getMessage());
        }
    }

    /**
     * 构建告警邮件 HTML（用字符串拼接，避免 Text Block + %s 与 CSS 函数的编译冲突）
     */
    private String buildEmailHtml(String title, String message,
                                  String badgeText, String headerBgColor, String time) {
        String messageHtml = message.replace("\n", "<br/>");
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"/><style>"
                + "body{font-family:-apple-system,sans-serif;background:#f5f5f5;margin:0;padding:20px}"
                + ".container{max-width:600px;margin:0 auto;background:#fff;border-radius:8px;"
                + "overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.12)}"
                + ".header{background:" + headerBgColor + ";color:#fff;padding:20px 24px}"
                + ".header h2{margin:0;font-size:18px;font-weight:600}"
                + ".badge{display:inline-block;background:rgba(255,255,255,0.25);"
                + "padding:2px 8px;border-radius:4px;font-size:12px;margin-bottom:8px}"
                + ".content{padding:24px}"
                + ".meta{color:#666;font-size:13px;margin-bottom:16px}"
                + ".message{background:#f9f9f9;border-left:4px solid " + headerBgColor + ";"
                + "padding:12px 16px;border-radius:4px;font-size:14px;line-height:1.6;color:#333}"
                + ".footer{padding:16px 24px;background:#fafafa;border-top:1px solid #eee;"
                + "font-size:12px;color:#999}"
                + "</style></head><body><div class=\"container\">"
                + "<div class=\"header\"><div class=\"badge\">" + badgeText + "</div>"
                + "<h2>" + title + "</h2></div>"
                + "<div class=\"content\">"
                + "<p class=\"meta\">⏰ 告警时间：" + time + " &nbsp;|&nbsp; 应用：" + appName + "</p>"
                + "<div class=\"message\">" + messageHtml + "</div></div>"
                + "<div class=\"footer\">此邮件由 AI Agent 监控系统自动发送，请勿直接回复。</div>"
                + "</div></body></html>";
    }

    // ── 自定义 Webhook：标准 JSON ──────────────────────────────

    private void sendCustomWebhook(String alertType, String message, AlertLevel level) {
        try {
            Map<String, Object> body = Map.of(
                    "app", appName,
                    "alertType", alertType,
                    "level", level.name(),
                    "message", message,
                    "timestamp", System.currentTimeMillis()
            );
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForObject(customWebhook, new HttpEntity<>(body, headers), Map.class);
            log.debug("[ALERT] 自定义 Webhook 发送成功");
        } catch (Exception e) {
            log.error("[ALERT] 自定义 Webhook 发送失败: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────

    public enum AlertLevel {
        WARNING,   // 黄色：需要关注
        CRITICAL   // 红色：需要立即处理
    }
}
