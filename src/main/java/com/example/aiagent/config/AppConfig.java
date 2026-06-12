package com.example.aiagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 应用级公共 Bean 配置
 */
@Configuration
@EnableAsync        // 启用 @Async（TokenUsageService 异步写 PostgreSQL 需要）
@EnableScheduling   // 启用 @Scheduled（AlertService 定时告警需要）
public class AppConfig {

    /**
     * RestTemplate Bean
     * 供 RerankerService 调用本地 BGE Reranker 服务使用
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * 告警通知专属线程池（alertTaskExecutor）
     *
     * 设计原则：
     * - 告警 I/O（HTTP Webhook / SMTP）延迟高且不稳定，必须与业务线程池隔离
     * - 有界队列（3000）防止告警风暴时内存溢出
     * - CallerRunsPolicy：队列满时由发布者线程兜底同步执行，CRITICAL 告警不丢失
     *
     * 容量规划：
     * ┌──────────────┬──────────────────────────────────────────────────┐
     * │  核心线程数   │  2（正常负载：低频定时告警，2线程绰绰有余）        │
     * │  最大线程数   │  4（突发：同时触发多个告警阈值时短暂扩展）          │
     * │  队列容量     │  3000（按每条告警 ~200B 估算，约占 600KB 堆内存）  │
     * │  空闲存活     │  60s（扩展线程在空闲 60s 后回收到核心线程数）      │
     * └──────────────┴──────────────────────────────────────────────────┘
     */
    @Bean(name = "alertTaskExecutor")
    public Executor alertTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(3000);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("alert-worker-");
        executor.setThreadGroupName("alert-group");
        // 队列满时：由调用方线程（定时任务线程）同步执行，确保 CRITICAL 告警不丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 应用关闭时等待当前队列中的告警全部发送完毕（最长等待 30s）
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 文档解析专属线程池（documentIngestExecutor）
     *
     * <p>大文件解析（PDF/Word）耗时长（秒级到分钟级），Embedding API 也有网络延迟，
     * 必须与告警线程池和 Web 线程池隔离，避免相互阻塞。
     *
     * 容量规划：
     * ┌──────────────┬──────────────────────────────────────────────┐
     * │  核心线程数   │  2（同时支持 2 份文档并发解析）                │
     * │  最大线程数   │  5（并发上传高峰时扩展，避免 Embedding 限速）  │
     * │  队列容量     │  50（最多排队 50 个文档解析任务）              │
     * │  空闲存活     │  120s                                        │
     * └──────────────┴──────────────────────────────────────────────┘
     */
    @Bean(name = "documentIngestExecutor")
    public Executor documentIngestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(120);
        executor.setThreadNamePrefix("doc-ingest-");
        executor.setThreadGroupName("doc-ingest-group");
        // 队列满时拒绝并抛出异常，由 Controller 返回 503
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }
}
