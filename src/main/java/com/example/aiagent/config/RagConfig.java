package com.example.aiagent.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;

@Slf4j
@Configuration
public class RagConfig {

    /**
     * Embedding 模型使用百度千帆 bge-large-zh
     *
     * 千帆兼容 OpenAI Embedding 接口，直接用 OpenAiEmbeddingModel 即可。
     * 申请地址：https://qianfan.cloud.baidu.com
     * bge-large-zh 向量维度：1024
     */
    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${qianfan.api-key}") String apiKey,
            @Value("${qianfan.base-url:https://qianfan.baidubce.com/v2}") String baseUrl,
            @Value("${qianfan.embedding.model:bge-large-zh}") String modelName) {
        log.info("[Embedding] 使用模型 model={} baseUrl={}", modelName, baseUrl);
        return OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
    }

    /**
     * PgVector 向量数据库
     * 需要提前在 PostgreSQL 中执行：CREATE EXTENSION vector;
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            DataSource dataSource,
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username}") String user,
            @Value("${spring.datasource.password}") String password) {

        ensureVectorDimension1024(dataSource);

        return PgVectorEmbeddingStore.builder()
                .host(parseHost(jdbcUrl))
                .port(parsePort(jdbcUrl))
                .database(parseDatabase(jdbcUrl))
                .user(user)
                .password(password)
                .table("knowledge_base")
                .dimension(1024)
                .createTable(true)
                .build();
    }

    private void ensureVectorDimension1024(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                     "SELECT format_type(a.atttypid, a.atttypmod) " +
                     "FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid " +
                     "WHERE c.relname = 'knowledge_base' AND a.attname = 'embedding'")) {
            if (rs.next()) {
                String type = rs.getString(1);
                if (!"vector(1024)".equals(type)) {
                    log.info("knowledge_base.embedding 当前类型为 {}，修正为 vector(1024)", type);
                    stmt.execute("ALTER TABLE knowledge_base ALTER COLUMN embedding TYPE vector(1024) USING NULL::vector(1024)");
                    log.info("knowledge_base.embedding 维度修正完成");
                }
            }
        } catch (Exception e) {
            log.debug("knowledge_base 表尚未创建，跳过维度修正: {}", e.getMessage());
        }
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
