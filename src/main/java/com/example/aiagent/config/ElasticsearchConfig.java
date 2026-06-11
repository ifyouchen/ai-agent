package com.example.aiagent.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch 配置（BM25 混合检索）
 *
 * 仅在 rag.elasticsearch.enabled=true 时生效，否则整个配置类不加载，
 * 确保 ES 未启动时应用可以正常运行。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "rag.elasticsearch.enabled", havingValue = "true", matchIfMissing = false)
public class ElasticsearchConfig {

    @Value("${rag.elasticsearch.host:localhost}")
    private String host;

    @Value("${rag.elasticsearch.port:9200}")
    private int port;

    @Value("${rag.elasticsearch.username:}")
    private String username;

    @Value("${rag.elasticsearch.password:}")
    private String password;

    /**
     * 创建 ElasticsearchClient Bean。
     * 支持两种模式：
     * - 无认证（username 为空）：适用于开发环境
     * - 用户名/密码认证：适用于生产环境
     */
    @Bean
    public ElasticsearchClient elasticsearchClient() {
        log.info("初始化 Elasticsearch 客户端，连接地址：{}:{}", host, port);

        RestClientBuilder builder = RestClient.builder(new HttpHost(host, port, "http"));

        // 如果配置了用户名，启用 Basic Auth
        if (username != null && !username.isBlank()) {
            log.info("Elasticsearch 启用 Basic Auth，用户名：{}", username);
            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(username, password)
            );
            builder.setHttpClientConfigCallback(
                    httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
            );
        } else {
            log.info("Elasticsearch 无认证模式（开发环境）");
        }

        RestClient restClient = builder.build();
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        ElasticsearchClient client = new ElasticsearchClient(transport);

        log.info("Elasticsearch 客户端初始化完成");
        return client;
    }
}
