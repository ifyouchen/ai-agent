package com.example.aiagent.observability.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 告警事件异步消费器
 *
 * 负责从 Spring 事件总线接收 AlertEvent，并在专属线程池中执行实际 I/O（HTTP/SMTP）。
 *
 * 线程池配置（alertTaskExecutor，见 AppConfig）：
 * ┌──────────────────────┬────────────────────────────────────────┐
 * │  核心线程数           │  2（平时低负载，维持少量线程）           │
 * │  最大线程数           │  4（突发高负载时扩展）                  │
 * │  队列容量             │  3000（有界队列，防止内存溢出）          │
 * │  队列满策略           │  CallerRunsPolicy → 降级为同步发送      │
 * │  线程前缀             │  alert-worker-                         │
 * └──────────────────────┴────────────────────────────────────────┘
 *
 * 队列满时行为：
 * - 使用 CallerRunsPolicy：由发布事件的线程（定时任务线程）直接执行发送
 * - 这会阻塞该定时任务一次，但保证告警不丢失（CRITICAL 级别尤为重要）
 * - 若希望直接丢弃，可在 AppConfig 中改为 DiscardOldestPolicy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertEventListener {

    private final AlertNotifier alertNotifier;

    /**
     * 异步消费告警事件
     *
     * @Async("alertTaskExecutor") 确保在专属线程池执行，不占用 Spring 默认异步线程池。
     * @EventListener 注册到 Spring 事件总线，自动接收 AlertEvent。
     *
     * @param event 告警事件（不可变，线程安全）
     */
    @Async("alertTaskExecutor")
    @EventListener
    public void onAlertEvent(AlertEvent event) {
        long waitMs = System.currentTimeMillis() - event.getOccurredAt();
        if (waitMs > 5_000) {
            // 队列积压超 5 秒，输出警告（可据此调整队列容量或线程数）
            log.warn("[ALERT-QUEUE] 告警排队等待 {}ms，队列可能积压，type={}",
                    waitMs, event.getAlertType());
        }
        log.debug("[ALERT-QUEUE] 消费告警事件，type={} level={} waitMs={}",
                event.getAlertType(), event.getLevel(), waitMs);
        alertNotifier.doSend(event);
    }
}

