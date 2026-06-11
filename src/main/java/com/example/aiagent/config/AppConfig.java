package com.example.aiagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

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
}
