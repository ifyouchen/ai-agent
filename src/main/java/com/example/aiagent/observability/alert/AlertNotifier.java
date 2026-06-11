package com.example.aiagent.observability.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 告警通知发送器
 *
 * 支持三种通知渠道（可同时启用）：
 * 1. 钉钉机器人 Webhook
 * 2. 企业微信机器人 Webhook
 * 3. 自定义 Webhook（通用 HTTP 回调）
 *
 * 配置示例：
 * ---
 * llm:
 *   observability:
 *     alert:
 *       dingtalk:
 *         enabled: true
 *         webhook: https://oapi.dingtalk.com/robot/send?access_token=xxx
 *         secret: SECxxx   # 可选，加签安全验证
 *       wecom:
 *         enabled: false
 *         webhook: https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx
 *       custom:
 *         enabled: false
 *         webhook: https://your-server/alert-hook
 */
@Slf4j
@Component
public class AlertNotifier {

    private final RestTemplate restTemplate;

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

    // ---- 自定义 Webhook ----
    @Value("${llm.observability.alert.custom.enabled:false}")
    private boolean customEnabled;

    @Value("${llm.observability.alert.custom.webhook:}")
    private String customWebhook;

    @Value("${spring.application.name:ai-agent}")
    private String appName;

    public AlertNotifier(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 发送告警到所有已启用的渠道
     *
     * @param alertType 告警类型，如 LLM_HIGH_ERROR_RATE
     * @param message   告警详情
     * @param level     告警级别：WARNING / CRITICAL
     */
    public void send(String alertType, String message, AlertLevel level) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"));
        String levelEmoji = level == AlertLevel.CRITICAL ? "🔴" : "🟡";
        String title = String.format("%s [%s] %s", levelEmoji, appName, alertType);
        String fullMessage = String.format("%s\n时间：%s\n详情：%s", title, time, message);

        if (dingtalkEnabled && !dingtalkWebhook.isBlank()) {
            sendDingtalk(title, fullMessage);
        }
        if (wecomEnabled && !wecomWebhook.isBlank()) {
            sendWecom(fullMessage);
        }
        if (customEnabled && !customWebhook.isBlank()) {
            sendCustomWebhook(alertType, message, level);
        }

        // 无论是否有外部渠道，始终输出日志（Loki/ELK 可采集）
        if (level == AlertLevel.CRITICAL) {
            log.error("[ALERT][{}] {}", alertType, message);
        } else {
            log.warn("[ALERT][{}] {}", alertType, message);
        }
    }

    // ---- 钉钉：Markdown 消息 ----
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

            restTemplate.postForObject(
                    dingtalkWebhook,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            log.debug("[ALERT] 钉钉通知发送成功");
        } catch (Exception e) {
            log.error("[ALERT] 钉钉通知发送失败: {}", e.getMessage());
        }
    }

    // ---- 企业微信：文本消息 ----
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

            restTemplate.postForObject(
                    wecomWebhook,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            log.debug("[ALERT] 企微通知发送成功");
        } catch (Exception e) {
            log.error("[ALERT] 企微通知发送失败: {}", e.getMessage());
        }
    }

    // ---- 自定义 Webhook：标准 JSON ----
    private void sendCustomWebhook(String alertType, String message, AlertLevel level) {
        try {
            Map<String, Object> body = Map.of(
                    "app",       appName,
                    "alertType", alertType,
                    "level",     level.name(),
                    "message",   message,
                    "timestamp", System.currentTimeMillis()
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            restTemplate.postForObject(
                    customWebhook,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            log.debug("[ALERT] 自定义 Webhook 发送成功");
        } catch (Exception e) {
            log.error("[ALERT] 自定义 Webhook 发送失败: {}", e.getMessage());
        }
    }

    public enum AlertLevel {
        WARNING,   // 黄色：需要关注
        CRITICAL   // 红色：需要立即处理
    }
}
