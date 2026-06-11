package com.example.aiagent.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfig {

    /**
     * 本地 Embedding 模型（ONNX 运行，无需额外 API）
     * 向量维度：384
     * 优点：免费、无网络依赖
     * 如需更高精度可替换为 OpenAI text-embedding-3-small（维度 1536）
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
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
                .dimension(384)          // 与 AllMiniLmL6V2 的维度一致
                .createTableIfNotExists(true)
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
