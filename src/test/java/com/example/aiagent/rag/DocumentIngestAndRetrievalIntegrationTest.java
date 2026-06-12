package com.example.aiagent.rag;

import com.example.aiagent.kb.entity.Chunk;
import com.example.aiagent.kb.entity.Document;
import com.example.aiagent.kb.entity.KnowledgeBase;
import com.example.aiagent.kb.mapper.ChunkMapper;
import com.example.aiagent.kb.mapper.DocumentMapper;
import com.example.aiagent.kb.mapper.KnowledgeBaseMapper;
import com.example.aiagent.rag.model.RetrievedChunk;
import com.example.aiagent.rag.retrieval.Bm25IndexRecoveryService;
import com.example.aiagent.rag.retrieval.Bm25Retriever;
import com.example.aiagent.rag.retrieval.HybridRagContentRetriever;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档摄入 + 检索 集成测试
 *
 * <p>验证完整的文档导入闭环：
 * <pre>
 *   上传文件 → 解析文档 → 切片 → 写入 kb_chunk 业务表
 *                                      ↓
 *                              索引到 Lucene（BM25）
 *                                      ↓
 *                              BM25 检索可命中
 * </pre>
 *
 * <p>注意：本测试不依赖外部 LLM API，只验证文档摄入 → BM25 索引 → BM25 检索链路。
 * 向量化部分（PgVector Embedding）需要 EmbeddingModel，在轻量测试中暂不覆盖。
 */
@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("文档摄入 + 检索 - 集成测试")
class DocumentIngestAndRetrievalIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("aiagent_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
        registry.add("deepseek.api-key", () -> "test-dummy-key");
        registry.add("DEEPSEEK_API_KEY", () -> "test-dummy-key");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private Bm25Retriever bm25Retriever;

    @Autowired
    private Bm25IndexRecoveryService recoveryService;

    @Autowired
    private ChunkMapper chunkMapper;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private KnowledgeBaseMapper kbMapper;

    // ── BM25 索引 + 检索闭环 ──────────────────────────────

    @Nested
    @DisplayName("BM25 索引与检索")
    class Bm25IndexAndRetrievalTests {

        @Test
        @DisplayName("索引切片后应可被 BM25 检索命中")
        void shouldRetrieveIndexedChunk() {
            String tenantId = "tenant-bm25-idx";
            long kbId = 5001L;

            // 1. 索引切片
            RetrievedChunk chunk = RetrievedChunk.builder()
                    .chunkId("bm25-test-1")
                    .content("Apache Lucene 是一个高性能的全文本搜索引擎库")
                    .documentName("lucene-intro.txt")
                    .tenantId(tenantId)
                    .kbId(kbId)
                    .chunkIndex(0)
                    .build();
            bm25Retriever.indexChunk(chunk);

            // 2. 检索
            List<RetrievedChunk> results = bm25Retriever.retrieve("Lucene 搜索引擎", 5, tenantId, kbId);

            // 3. 验证
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).getContent()).contains("Lucene");
            assertThat(results.get(0).getTenantId()).isEqualTo(tenantId);
            assertThat(results.get(0).getKbId()).isEqualTo(kbId);
        }

        @Test
        @DisplayName("索引切片 - 多租户严格隔离")
        void shouldStrictlyIsolateBm25Results() {
            // 租户 A 索引
            indexChunk("iso-a-1", "Spring Boot 微服务架构设计", "iso-tenant-A", 6001L);
            indexChunk("iso-a-2", "Spring Cloud 配置管理", "iso-tenant-A", 6001L);

            // 租户 B 索引
            indexChunk("iso-b-1", "Spring Boot 数据访问层详解", "iso-tenant-B", 6002L);

            // 租户 A 检索
            List<RetrievedChunk> resultsA = bm25Retriever.retrieve("Spring Boot", 10, "iso-tenant-A", 6001L);
            assertThat(resultsA).allMatch(c -> "iso-tenant-A".equals(c.getTenantId()) && c.getKbId() == 6001L);

            // 租户 B 检索
            List<RetrievedChunk> resultsB = bm25Retriever.retrieve("Spring Boot", 10, "iso-tenant-B", 6002L);
            assertThat(resultsB).allMatch(c -> "iso-tenant-B".equals(c.getTenantId()) && c.getKbId() == 6002L);
        }

        @Test
        @DisplayName("索引更新 - 同 chunkId 应覆盖旧内容")
        void shouldUpdateExistingChunk() {
            String chunkId = "update-test-1";
            String tenantId = "tenant-update";
            long kbId = 7001L;

            // 1. 索引旧内容
            indexChunk(chunkId, "旧版本的文档内容关于Python", tenantId, kbId);

            // 2. 索引新内容（同 chunkId）
            indexChunk(chunkId, "新版本的文档内容关于Java编程", tenantId, kbId);

            // 3. 检索应返回新内容
            List<RetrievedChunk> results = bm25Retriever.retrieve("Java编程", 5, tenantId, kbId);
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).getContent()).contains("Java编程");
        }
    }

    // ── BM25 索引恢复 ──────────────────────────────────────

    @Nested
    @DisplayName("BM25 索引恢复")
    class Bm25IndexRecoveryTests {

        @Test
        @DisplayName("从数据库恢复指定知识库的 BM25 索引")
        void shouldRecoverBm25IndexFromDatabase() {
            String tenantId = "tenant-recovery";
            long kbId = 8001L;

            // 1. 创建知识库和文档
            KnowledgeBase kb = KnowledgeBase.builder()
                    .tenantId(tenantId).name("恢复测试KB").build();
            kbMapper.insert(kb);
            kbId = kb.getId();

            Document doc = Document.builder()
                    .kbId(kbId).tenantId(tenantId).name("recovery.txt")
                    .docType("TXT").parseStatus("DONE").chunkCount(1).build();
            documentMapper.insert(doc);

            // 2. 插入切片到业务表
            Chunk chunkEntity = Chunk.builder()
                    .docId(doc.getId())
                    .kbId(kbId)
                    .tenantId(tenantId)
                    .chunkIndex(0)
                    .content("索引恢复测试：分布式系统设计原则")
                    .isActive(true)
                    .build();
            chunkMapper.insert(chunkEntity);

            // 3. 先删除 BM25 中该 kbId 的旧索引
            bm25Retriever.deleteByKbId(String.valueOf(kbId));

            // 4. 执行恢复
            int recovered = recoveryService.recoverByKbId(kbId);
            assertThat(recovered).isGreaterThan(0);

            // 5. 验证检索命中
            List<RetrievedChunk> results = bm25Retriever.retrieve("分布式系统", 5, tenantId, kbId);
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).getContent()).contains("分布式系统");
        }
    }

    // ── HybridRagContentRetriever ThreadLocal 上下文 ─────────────

    @Nested
    @DisplayName("检索上下文 ThreadLocal")
    class RetrievalContextTests {

        @Test
        @DisplayName("setContext / getContext / clearContext 应正确传递租户信息")
        void shouldPropagateRetrievalContextViaThreadLocal() {
            String tenantId = "tenant-ctx";
            Long kbId = 9001L;

            // 初始应为 null
            assertThat(HybridRagContentRetriever.getContext()).isNull();

            // 设置上下文
            HybridRagContentRetriever.RetrievalContext ctx =
                    new HybridRagContentRetriever.RetrievalContext(tenantId, kbId);
            HybridRagContentRetriever.setContext(ctx);

            try {
                // 验证上下文正确
                HybridRagContentRetriever.RetrievalContext current = HybridRagContentRetriever.getContext();
                assertThat(current).isNotNull();
                assertThat(current.tenantId()).isEqualTo(tenantId);
                assertThat(current.kbId()).isEqualTo(kbId);
            } finally {
                HybridRagContentRetriever.clearContext();
            }

            // 清除后应为 null
            assertThat(HybridRagContentRetriever.getContext()).isNull();
        }

        @Test
        @DisplayName("clearContext 不应抛异常（即使未设置过）")
        void shouldNotThrowWhenClearingUnsetContext() {
            assertThat(HybridRagContentRetriever.getContext()).isNull();
            HybridRagContentRetriever.clearContext(); // 不应抛异常
            assertThat(HybridRagContentRetriever.getContext()).isNull();
        }
    }

    // ── 文件摄入（轻量级，仅测试 BM25 链路） ──────────────

    @Nested
    @DisplayName("文档摄入 BM25 链路")
    class DocumentIngestBm25Tests {

        @Test
        @DisplayName("摄入 TXT 文件后 BM25 应能检索到")
        void shouldIngestAndRetrieveViaBm25() throws Exception {
            String tenantId = "tenant-ingest";
            String kbName = "摄入测试KB-" + System.currentTimeMillis();

            // 1. 创建知识库
            KnowledgeBase kb = kbService().createKnowledgeBase(tenantId, kbName, "测试摄入闭环", "test-user");

            // 2. 准备测试文件
            Path tempFile = Files.createTempFile("test-ingest-", ".txt");
            Files.writeString(tempFile, "Spring Boot 是一个流行的 Java 微服务框架，"
                    + "它简化了 Spring 应用的开发和部署。Spring Boot 提供了自动配置、"
                    + "起步依赖和内嵌服务器等特性。");

            try {
                // 3. 摄入文档
                int chunkCount = ingestService().ingestPath(tempFile, tenantId, kb.getId());

                // 4. 验证切片数 > 0
                assertThat(chunkCount).isGreaterThan(0);

                // 5. 验证 Document 业务表
                List<Document> docs = documentMapper.findByKbId(kb.getId());
                assertThat(docs).isNotEmpty();
                Document doc = docs.get(0);
                assertThat(doc.getParseStatus()).isEqualTo("DONE");
                assertThat(doc.getTenantId()).isEqualTo(tenantId);

                // 6. 验证 Chunk 业务表
                List<Chunk> chunks = chunkMapper.findByDocId(doc.getId());
                assertThat(chunks).isNotEmpty();
                assertThat(chunks).allMatch(c -> tenantId.equals(c.getTenantId()));
                assertThat(chunks).allMatch(c -> c.getKbId().equals(kb.getId()));

                // 7. 验证 BM25 检索命中
                List<RetrievedChunk> results = bm25Retriever.retrieve("Spring Boot 微服务", 5, tenantId, kb.getId());
                assertThat(results).isNotEmpty();
                assertThat(results).allMatch(c -> tenantId.equals(c.getTenantId()));

            } finally {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    // ── 辅助方法 ──────────────────────────────────────────

    private void indexChunk(String chunkId, String content, String tenantId, Long kbId) {
        RetrievedChunk chunk = RetrievedChunk.builder()
                .chunkId(chunkId)
                .content(content)
                .documentName("test-doc.txt")
                .tenantId(tenantId)
                .kbId(kbId)
                .chunkIndex(0)
                .build();
        bm25Retriever.indexChunk(chunk);
    }

    @Autowired
    private com.example.aiagent.kb.service.KnowledgeBaseService kbServiceRef;

    @Autowired
    private DocumentIngestService ingestServiceRef;

    private com.example.aiagent.kb.service.KnowledgeBaseService kbService() {
        return kbServiceRef;
    }

    private DocumentIngestService ingestService() {
        return ingestServiceRef;
    }
}

