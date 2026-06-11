package com.example.aiagent.observability.config;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 可观测性基础配置
 *
 * 1. 为所有 Prometheus 指标添加全局标签（应用名、环境）
 * 2. 防止高基数标签（如 userId）被误加到 metrics，导致 Prometheus 内存爆炸
 * 3. 配置独立的异步线程池（写 PostgreSQL 不占用业务线程）
 */
@Configuration
public class ObservabilityConfig {

    @Value("${spring.application.name:ai-agent}")
    private String appName;

    @Value("${spring.profiles.active:default}")
    private String environment;

    /**
     * 全局公共标签：所有指标都带上应用名和环境
     * 便于多应用部署时在 Grafana 中过滤
     */
    @Bean
    public MeterFilter globalTagsFilter() {
        return MeterFilter.commonTags(List.of(
                Tag.of("application", appName),
                Tag.of("environment", environment)
        ));
    }

    /**
     * 防止高基数标签进入 Prometheus
     * traceId / sessionId / userId 绝对不能作为 metric tag！
     * 每个唯一值都会创建一个新的时间序列，导致内存溢出
     */
    @Bean
    public MeterFilter highCardinalityFilter() {
        return MeterFilter.deny(id -> {
            // 如果有人误把 trace/session/user 级别的标签加到 metrics 里，直接拒绝
            return id.getTags().stream().map(Tag::getKey).anyMatch(key ->
                    key.equals("traceId") || key.equals("sessionId")
                            || key.contains("requestId") || key.contains("messageId"));
        });
    }

    /**
     * 可观测性专用异步线程池（写 PostgreSQL 用，不占用业务线程池）
     */
    @Bean("observabilityExecutor")
    public Executor observabilityExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("obs-");
        executor.setRejectedExecutionHandler((r, e) -> {
            // 队列满了就直接丢弃（写 PostgreSQL 失败不能影响业务）
        });
        executor.initialize();
        return executor;
    }
}
