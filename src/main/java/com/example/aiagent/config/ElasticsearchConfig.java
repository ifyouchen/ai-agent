package com.example.aiagent.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PostConstruct;
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
 *
 * 索引 Mapping 说明：
 * ┌─────────────────┬────────────────────────────────────────────────────┐
 * │  字段            │  说明                                              │
 * ├─────────────────┼────────────────────────────────────────────────────┤
 * │  content        │  text，ik_max_word 分词（中文精准 BM25）            │
 * │  documentName   │  text + keyword，text 用于检索，keyword 用于聚合    │
 * │  documentPath   │  keyword                                           │
 * │  chunkId        │  keyword（精确 ID）                                │
 * │  kbId           │  keyword（知识库隔离过滤）                          │
 * │  tenantId       │  keyword（多租户隔离过滤）                          │
 * │  pageNumber     │  integer                                           │
 * │  chunkIndex     │  integer                                           │
 * └─────────────────┴────────────────────────────────────────────────────┘
 *
 * 中文分词：优先使用 ik_max_word（需安装 analysis-ik 插件），
 * 回退到 standard 分词（仅英文效果好）。
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

    @Value("${rag.elasticsearch.index-name:rag-documents}")
    private String indexName;

    /**
     * 是否使用 IK 中文分词器（需安装 elasticsearch-analysis-ik 插件）
     * 设为 false 时使用 standard 分词器，无需额外插件
     */
    @Value("${rag.elasticsearch.ik-analyzer:true}")
    private boolean useIkAnalyzer;

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

    /**
     * 应用启动后自动初始化 ES 索引（幂等，已存在则跳过创建）
     *
     * 执行顺序：
     * 1. 检查索引是否存在
     * 2. 不存在 → 创建索引（设置 Mapping 和 Settings）
     * 3. 已存在 → 检查 Mapping 是否完整，必要时更新（仅可扩展字段，不可删除字段）
     *
     * 注意：IK 分词器需安装插件。若 IK 不可用，回退到 standard 分词器继续启动。
     */
    @PostConstruct
    public void initIndex() {
        try {
            ElasticsearchClient client = elasticsearchClient();
            boolean exists = client.indices()
                    .exists(ExistsRequest.of(e -> e.index(indexName)))
                    .value();

            if (!exists) {
                createIndex(client);
            } else {
                log.info("[ES] 索引 {} 已存在，跳过创建", indexName);
                ensureMapping(client);
            }
        } catch (Exception e) {
            log.warn("[ES] 索引初始化失败（不影响启动，BM25 检索将不可用）：{}", e.getMessage());
        }
    }

    /**
     * 创建索引，含 Settings（分片、副本、分析器）和 Mappings（字段定义）
     */
    private void createIndex(ElasticsearchClient client) throws Exception {
        String analyzer = useIkAnalyzer ? "ik_max_word" : "standard";
        String searchAnalyzer = useIkAnalyzer ? "ik_smart" : "standard";

        log.info("[ES] 创建索引 {}，分词器：{}", indexName, analyzer);

        // 构建 Settings + Mappings JSON（用 String.format 拼接，避免 Text Block + %s 的编译问题）
        String settingsAndMappings = String.format(
                "{"
                + "\"settings\":{"
                +   "\"number_of_shards\":1,"
                +   "\"number_of_replicas\":1,"
                +   "\"analysis\":{"
                +     "\"analyzer\":{"
                +       "\"text_analyzer\":{"
                +         "\"type\":\"custom\","
                +         "\"tokenizer\":\"%s\","
                +         "\"filter\":[\"lowercase\",\"stop\"]"
                +       "}"
                +     "}"
                +   "}"
                + "},"
                + "\"mappings\":{"
                +   "\"properties\":{"
                +     "\"chunkId\":{\"type\":\"keyword\"},"
                +     "\"content\":{\"type\":\"text\",\"analyzer\":\"%s\",\"search_analyzer\":\"%s\"},"
                +     "\"documentName\":{\"type\":\"text\",\"analyzer\":\"%s\",\"search_analyzer\":\"%s\","
                +       "\"fields\":{\"keyword\":{\"type\":\"keyword\",\"ignore_above\":512}}},"
                +     "\"documentPath\":{\"type\":\"keyword\"},"
                +     "\"pageNumber\":{\"type\":\"integer\"},"
                +     "\"chunkIndex\":{\"type\":\"integer\"},"
                +     "\"kbId\":{\"type\":\"keyword\"},"
                +     "\"tenantId\":{\"type\":\"keyword\"}"
                +   "}"
                + "}"
                + "}",
                analyzer, analyzer, searchAnalyzer, analyzer, searchAnalyzer);

        try {
            client.indices().create(CreateIndexRequest.of(c -> c
                    .index(indexName)
                    .withJson(new java.io.StringReader(settingsAndMappings))
            ));
            log.info("[ES] 索引 {} 创建成功", indexName);
        } catch (Exception e) {
            // IK 插件不存在时，回退到 standard 分词器重试
            if (useIkAnalyzer && e.getMessage() != null && e.getMessage().contains("ik_max_word")) {
                log.warn("[ES] IK 分词器不可用，回退到 standard 分词器重试。" +
                        "安装 IK：./bin/elasticsearch-plugin install analysis-ik");
                useIkAnalyzer = false;
                createIndex(client);
            } else {
                throw e;
            }
        }
    }

    /**
     * 确保已有索引包含所有必要的字段 Mapping（追加新字段，不覆盖旧字段）
     */
    private void ensureMapping(ElasticsearchClient client) {
        try {
            String additionalMapping = "{"
                    + "\"properties\": {"
                    + "\"kbId\":     { \"type\": \"keyword\" },"
                    + "\"tenantId\": { \"type\": \"keyword\" }"
                    + "}"
                    + "}";
            client.indices().putMapping(PutMappingRequest.of(m -> m
                    .index(indexName)
                    .withJson(new java.io.StringReader(additionalMapping))
            ));
            log.debug("[ES] 索引 {} Mapping 已确认/更新", indexName);
        } catch (Exception e) {
            log.debug("[ES] Mapping 更新跳过（字段可能已存在）：{}", e.getMessage());
        }
    }
}
