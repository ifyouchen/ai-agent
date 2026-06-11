package com.example.aiagent.observability.alert;

import org.springframework.context.ApplicationEvent;

/**
 * 告警事件（Spring ApplicationEvent）
 *
 * 流转路径：
 *   AlertNotifier.send()
 *       → ApplicationEventPublisher.publishEvent(AlertEvent)   ← 同步发布，立即返回
 *       → AlertEventListener.onAlertEvent()                    ← @Async 异步消费
 *           → 钉钉 / 企微 / 邮件 / 自定义 Webhook（真实 I/O 在此发生）
 *
 * 使用 record 保证不可变性，避免跨线程共享状态问题。
 */
public class AlertEvent extends ApplicationEvent {

    private final String alertType;
    private final String message;
    private final AlertNotifier.AlertLevel level;

    /** 告警产生时间（毫秒时间戳，创建事件时即固定，不受队列等待影响） */
    private final long occurredAt;

    public AlertEvent(Object source,
                      String alertType,
                      String message,
                      AlertNotifier.AlertLevel level) {
        super(source);
        this.alertType  = alertType;
        this.message    = message;
        this.level      = level;
        this.occurredAt = System.currentTimeMillis();
    }

    public String getAlertType()             { return alertType;  }
    public String getMessage()               { return message;    }
    public AlertNotifier.AlertLevel getLevel(){ return level;     }
    public long getOccurredAt()              { return occurredAt; }
}

