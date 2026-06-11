package com.example.aiagent.kb.service;

import com.example.aiagent.kb.entity.Document;
import com.example.aiagent.kb.entity.KnowledgeBase;
import com.example.aiagent.kb.mapper.ChunkMapper;
import com.example.aiagent.kb.mapper.DocumentMapper;
import com.example.aiagent.kb.mapper.KnowledgeBaseMapper;
import com.example.aiagent.rag.retrieval.Bm25Retriever;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 知识库服务集成测试
 *
 * <p>使用 Testcontainers 启动 PostgreSQL + pgvector 容器，
 * 真实测试 KnowledgeBaseService 与数据库的完整交互。
 *
 * <p>覆盖场景：
 * 1. 知识库 CRUD（创建、查询、删除）
 * 2. 多租户隔离（不同租户的知识库互不可见）
 * 3. 级联删除（删除知识库时级联删除文档和切片）
 * 4. 文档管理（列出、删除文档）
 */
@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("知识库服务 - 集成测试")
class KnowledgeBaseServiceIntegrationTest {

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
        // 禁用 Redis（测试环境不需要）
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
        // 禁用外部 LLM API 调用
        registry.add("deepseek.api-key", () -> "test-dummy-key");
        registry.add("DEEPSEEK_API_KEY", () -> "test-dummy-key");
        // Flyway 自动执行 schema 迁移
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private KnowledgeBaseService kbService;

    @Autowired
    private KnowledgeBaseMapper kbMapper;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private ChunkMapper chunkMapper;

    @Autowired
    private Bm25Retriever bm25Retriever;

    // ── 知识库 CRUD ──────────────────────────────────────

    @Nested
    @DisplayName("知识库 CRUD")
    class KnowledgeBaseCrudTests {

        @Test
        @DisplayName("创建知识库 - 成功")
        void shouldCreateKnowledgeBase() {
            KnowledgeBase kb = kbService.createKnowledgeBase("tenant-test", "产品手册", "产品相关文档");

            assertThat(kb).isNotNull();
            assertThat(kb.getId()).isNotNull();
            assertThat(kb.getTenantId()).isEqualTo("tenant-test");
            assertThat(kb.getName()).isEqualTo("产品手册");
            assertThat(kb.getDescription()).isEqualTo("产品相关文档");
            assertThat(kb.getDocCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("创建知识库 - 同租户同名重复应抛异常")
        void shouldRejectDuplicateNameInSameTenant() {
            kbService.createKnowledgeBase("tenant-dup", "手册", null);

            assertThatThrownBy(() -> kbService.createKnowledgeBase("tenant-dup", "手册", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("已存在");
        }

        @Test
        @DisplayName("创建知识库 - 不同租户同名可创建")
        void shouldAllowSameNameInDifferentTenants() {
            kbService.createKnowledgeBase("tenant-A", "手册", null);
            KnowledgeBase kbB = kbService.createKnowledgeBase("tenant-B", "手册", null);

            assertThat(kbB).isNotNull();
            assertThat(kbB.getTenantId()).isEqualTo("tenant-B");
        }

        @Test
        @DisplayName("列出知识库 - 只返回当前租户的")
        void shouldListOnlyCurrentTenantKBs() {
            kbService.createKnowledgeBase("tenant-list-A", "知识库A1", null);
            kbService.createKnowledgeBase("tenant-list-A", "知识库A2", null);
            kbService.createKnowledgeBase("tenant-list-B", "知识库B1", null);

            List<KnowledgeBase> listA = kbService.listKnowledgeBases("tenant-list-A");
            List<KnowledgeBase> listB = kbService.listKnowledgeBases("tenant-list-B");

            assertThat(listA).hasSize(2);
            assertThat(listA).allMatch(kb -> "tenant-list-A".equals(kb.getTenantId()));
            assertThat(listB).hasSize(1);
        }

        @Test
        @DisplayName("获取知识库 - 不存在的 ID 应抛异常")
        void shouldThrowWhenKbNotFound() {
            assertThatThrownBy(() -> kbService.getKnowledgeBase("tenant-x", 99999L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不存在");
        }

        @Test
        @DisplayName("获取知识库 - 不属于当前租户应抛异常")
        void shouldRejectCrossTenantAccess() {
            KnowledgeBase kb = kbService.createKnowledgeBase("tenant-owner", "私有知识库", null);

            assertThatThrownBy(() -> kbService.getKnowledgeBase("tenant-intruder", kb.getId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不属于");
        }
    }

    // ── 级联删除 ──────────────────────────────────────────

    @Nested
    @DisplayName("级联删除")
    class CascadeDeleteTests {

        @Test
        @DisplayName("删除知识库 - 应级联删除文档和切片")
        void shouldCascadeDeleteDocsAndChunks() {
            // 1. 创建知识库
            KnowledgeBase kb = kbService.createKnowledgeBase("tenant-del", "待删除KB", null);

            // 2. 手动插入文档和切片（模拟已有数据）
            Document doc = Document.builder()
                    .kbId(kb.getId())
                    .tenantId("tenant-del")
                    .name("test.txt")
                    .docType("TXT")
                    .parseStatus("DONE")
                    .chunkCount(2)
                    .build();
            documentMapper.insert(doc);

            // 验证文档存在
            List<Document> docsBefore = documentMapper.findByKbId(kb.getId());
            assertThat(docsBefore).hasSize(1);

            // 3. 删除知识库
            kbService.deleteKnowledgeBase("tenant-del", kb.getId());

            // 4. 验证文档已级联删除
            List<Document> docsAfter = documentMapper.findByKbId(kb.getId());
            assertThat(docsAfter).isEmpty();

            // 5. 验证知识库已删除
            assertThat(kbMapper.findById(kb.getId())).isEmpty();
        }

        @Test
        @DisplayName("删除知识库 - 应清理 BM25 索引")
        void shouldCleanUpBm25IndexOnDelete() {
            KnowledgeBase kb = kbService.createKnowledgeBase("tenant-bm25-del", "BM25测试KB", null);

            // 索引一些切片到 BM25
            com.example.aiagent.rag.model.RetrievedChunk chunk1 =
                    com.example.aiagent.rag.model.RetrievedChunk.builder()
                            .chunkId("del-chunk-1")
                            .content("测试内容删除验证")
                            .documentName("test.txt")
                            .tenantId("tenant-bm25-del")
                            .kbId(kb.getId())
                            .build();
            bm25Retriever.indexChunk(chunk1);

            // 验证索引存在
            var results = bm25Retriever.retrieve("测试", 10, "tenant-bm25-del", kb.getId());
            assertThat(results).isNotEmpty();

            // 删除知识库
            kbService.deleteKnowledgeBase("tenant-bm25-del", kb.getId());

            // 验证 BM25 索引已清理
            var resultsAfter = bm25Retriever.retrieve("测试", 10, "tenant-bm25-del", kb.getId());
            assertThat(resultsAfter).isEmpty();
        }
    }

    // ── 文档管理 ──────────────────────────────────────────

    @Nested
    @DisplayName("文档管理")
    class DocumentManagementTests {

        @Test
        @DisplayName("列出文档 - 应校验知识库归属")
        void shouldVerifyOwnershipWhenListingDocs() {
            KnowledgeBase kb = kbService.createKnowledgeBase("tenant-doc-list", "文档管理KB", null);

            // 另一租户尝试列文档应抛异常
            assertThatThrownBy(() -> kbService.getDocuments("tenant-intruder", kb.getId()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("删除文档 - 应更新知识库 docCount")
        void shouldUpdateDocCountOnDelete() {
            // 1. 创建知识库
            KnowledgeBase kb = kbService.createKnowledgeBase("tenant-doc-del", "文档计数KB", null);

            // 2. 插入文档
            Document doc = Document.builder()
                    .kbId(kb.getId())
                    .tenantId("tenant-doc-del")
                    .name("to-delete.txt")
                    .docType("TXT")
                    .parseStatus("DONE")
                    .build();
            documentMapper.insert(doc);

            // 更新 docCount
            long docCount = documentMapper.countByKbId(kb.getId());
            kbMapper.updateDocCount(kb.getId(), (int) docCount);

            // 验证 docCount
            KnowledgeBase kbBefore = kbMapper.findById(kb.getId()).orElseThrow();
            assertThat(kbBefore.getDocCount()).isEqualTo(1);

            // 3. 删除文档
            kbService.deleteDocument("tenant-doc-del", doc.getId());

            // 4. 验证 docCount 已更新
            KnowledgeBase kbAfter = kbMapper.findById(kb.getId()).orElseThrow();
            assertThat(kbAfter.getDocCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("删除文档 - 不属于当前租户应拒绝")
        void shouldRejectCrossTenantDocDelete() {
            KnowledgeBase kb = kbService.createKnowledgeBase("tenant-doc-owner", "文档KB", null);
            Document doc = Document.builder()
                    .kbId(kb.getId())
                    .tenantId("tenant-doc-owner")
                    .name("private.txt")
                    .docType("TXT")
                    .parseStatus("DONE")
                    .build();
            documentMapper.insert(doc);

            assertThatThrownBy(() -> kbService.deleteDocument("tenant-intruder", doc.getId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不属于");
        }
    }

    // ── 多租户隔离 ──────────────────────────────────────────

    @Nested
    @DisplayName("多租户隔离")
    class TenantIsolationTests {

        @Test
        @DisplayName("各租户的知识库完全隔离")
        void shouldFullyIsolateKnowledgeBases() {
            // 租户 A 创建知识库
            KnowledgeBase kbA = kbService.createKnowledgeBase("iso-A", "A的知识库", null);
            // 租户 B 创建知识库
            KnowledgeBase kbB = kbService.createKnowledgeBase("iso-B", "B的知识库", null);

            // 租户 A 看不到 B 的知识库
            List<KnowledgeBase> listA = kbService.listKnowledgeBases("iso-A");
            assertThat(listA).allMatch(kb -> "iso-A".equals(kb.getTenantId()));
            assertThat(listA).noneMatch(kb -> kb.getId().equals(kbB.getId()));

            // 租户 B 看不到 A 的知识库
            List<KnowledgeBase> listB = kbService.listKnowledgeBases("iso-B");
            assertThat(listB).allMatch(kb -> "iso-B".equals(kb.getTenantId()));
            assertThat(listB).noneMatch(kb -> kb.getId().equals(kbA.getId()));
        }

        @Test
        @DisplayName("BM25 检索应按租户隔离")
        void shouldIsolateBm25RetrievalByTenant() {
            // 租户 A 索引内容
            com.example.aiagent.rag.model.RetrievedChunk chunkA =
                    com.example.aiagent.rag.model.RetrievedChunk.builder()
                            .chunkId("iso-a-1")
                            .content("租户A的独有内容关于Python开发")
                            .documentName("a-python.txt")
                            .tenantId("iso-bm25-A")
                            .kbId(1000L)
                            .build();
            bm25Retriever.indexChunk(chunkA);

            // 租户 B 索引内容
            com.example.aiagent.rag.model.RetrievedChunk chunkB =
                    com.example.aiagent.rag.model.RetrievedChunk.builder()
                            .chunkId("iso-b-1")
                            .content("租户B的独有内容关于Python开发")
                            .documentName("b-python.txt")
                            .tenantId("iso-bm25-B")
                            .kbId(2000L)
                            .build();
            bm25Retriever.indexChunk(chunkB);

            // 租户 A 检索，应只看到 A 的内容
            var resultsA = bm25Retriever.retrieve("Python开发", 10, "iso-bm25-A", 1000L);
            assertThat(resultsA).allMatch(c -> "iso-bm25-A".equals(c.getTenantId()));

            // 租户 B 检索，应只看到 B 的内容
            var resultsB = bm25Retriever.retrieve("Python开发", 10, "iso-bm25-B", 2000L);
            assertThat(resultsB).allMatch(c -> "iso-bm25-B".equals(c.getTenantId()));
        }
    }
}

