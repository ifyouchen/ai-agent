package com.example.aiagent.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfig {

    /**
     * Embedding 模型统一使用 DeepSeek
     *
     * 无论当前 profile 是 deepseek 还是 claude，Embedding 都走 DeepSeek API。
     * 原因：Embedding 只负责把文字转成向量，和对话模型无关，
     *       统一用一个服务更简单，也避免跨服务向量维度不一致的问题。
     *
     * DeepSeek 兼容 OpenAI Embedding 接口，直接用 OpenAiEmbeddingModel 即可。
     * 向量维度：1536
     */
    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${deepseek.api-key:${DEEPSEEK_API_KEY:}}") String apiKey,
            @Value("${deepseek.base-url:https://api.deepseek.com/v1}") String baseUrl) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName("deepseek-embedding")
                .build();
    }

    /**
     * PgVector 向量数据库
     * 需要提前在 PostgreSQL 中执行：CREATE EXTENSION vector;
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username}") String user,
            @Value("${spring.datasource.password}") String password) {

        // 从 jdbc url 中解析 host/port/db（简化处理）
        // 实际项目可注入 DataSource 更优雅
        return PgVectorEmbeddingStore.builder()
                .host(parseHost(jdbcUrl))
                .port(parsePort(jdbcUrl))
                .database(parseDatabase(jdbcUrl))
                .user(user)
                .password(password)
                .table("knowledge_base")
                .dimension(1536)         // DeepSeek / OpenAI embedding 维度
                .createTable(true)
                .build();
    }

    // ---- 简单的 JDBC URL 解析工具方法 ----

    private String parseHost(String jdbcUrl) {
        // jdbc:postgresql://localhost:5432/aiagent -> localhost
        String withoutPrefix = jdbcUrl.replace("jdbc:postgresql://", "");
        return withoutPrefix.split(":")[0];
    }

    private int parsePort(String jdbcUrl) {
        // jdbc:postgresql://localhost:5432/aiagent -> 5432
        String withoutPrefix = jdbcUrl.replace("jdbc:postgresql://", "");
        String portAndDb = withoutPrefix.split(":")[1];
        return Integer.parseInt(portAndDb.split("/")[0]);
    }

    private String parseDatabase(String jdbcUrl) {
        // jdbc:postgresql://localhost:5432/aiagent -> aiagent
        return jdbcUrl.substring(jdbcUrl.lastIndexOf('/') + 1);
    }
}
