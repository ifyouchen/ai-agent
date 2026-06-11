package com.example.aiagent.rag.retrieval;

import com.example.aiagent.rag.model.RetrievedChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bm25Retriever 多租户隔离测试
 *
 * <p>验证：
 * 1. 按 tenantId 过滤：不同租户的切片互不可见
 * 2. 按 kbId 过滤：不同知识库的切片互不可见
 * 3. 按 tenantId + kbId 联合过滤
 * 4. 无过滤时返回所有切片（兼容旧调用）
 */
@DisplayName("Bm25Retriever - 多租户隔离")
class Bm25RetrieverTenantIsolationTest {

    private Bm25Retriever retriever;

    @BeforeEach
    void setUp() throws Exception {
        retriever = new Bm25Retriever();
        ReflectionTestUtils.setField(retriever, "bm25K1", 1.2f);
        ReflectionTestUtils.setField(retriever, "bm25B", 0.75f);
        retriever.init();
    }

    @AfterEach
    void tearDown() {
        retriever.shutdown();
    }

    @Nested
    @DisplayName("按 tenantId 过滤")
    class TenantFilterTests {

        @Test
        @DisplayName("不同租户的切片互不可见")
        void shouldIsolateByTenantId() {
            // 索引租户 A 的切片
            indexChunk("chunk-a1", "Spring Boot 微服务开发", "tenant-A", 100L);
            indexChunk("chunk-a2", "Spring Cloud 配置中心", "tenant-A", 100L);

            // 索引租户 B 的切片
            indexChunk("chunk-b1", "Spring Boot 数据访问层", "tenant-B", 200L);

            // 租户 A 检索，应只看到自己的切片
            List<RetrievedChunk> resultsA = retriever.retrieve("Spring Boot", 10, "tenant-A", null);
            assertThat(resultsA).allMatch(chunk -> "tenant-A".equals(chunk.getTenantId()));

            // 租户 B 检索，应只看到自己的切片
            List<RetrievedChunk> resultsB = retriever.retrieve("Spring Boot", 10, "tenant-B", null);
            assertThat(resultsB).allMatch(chunk -> "tenant-B".equals(chunk.getTenantId()));
        }

        @Test
        @DisplayName("不存在的租户检索应返回空结果")
        void shouldReturnEmptyForNonExistentTenant() {
            indexChunk("chunk-1", "Spring Boot 框架介绍", "tenant-A", 100L);

            List<RetrievedChunk> results = retriever.retrieve("Spring Boot", 10, "tenant-UNKNOWN", null);
            assertThat(results).isEmpty();
        }
    }

    @Nested
    @DisplayName("按 kbId 过滤")
    class KbFilterTests {

        @Test
        @DisplayName("不同知识库的切片互不可见")
        void shouldIsolateByKbId() {
            // 索引知识库 100 的切片
            indexChunk("chunk-100-1", "退款政策：7 天无理由退货", "tenant-A", 100L);
            indexChunk("chunk-100-2", "退货流程说明文档", "tenant-A", 100L);

            // 索引知识库 200 的切片
            indexChunk("chunk-200-1", "退款政策：15 天质量退货", "tenant-A", 200L);

            // 知识库 100 检索，应只看到自己的切片
            List<RetrievedChunk> results100 = retriever.retrieve("退款", 10, "tenant-A", 100L);
            assertThat(results100).allMatch(chunk -> chunk.getKbId() == 100L);

            // 知识库 200 检索，应只看到自己的切片
            List<RetrievedChunk> results200 = retriever.retrieve("退款", 10, "tenant-A", 200L);
            assertThat(results200).allMatch(chunk -> chunk.getKbId() == 200L);
        }
    }

    @Nested
    @DisplayName("按 tenantId + kbId 联合过滤")
    class CombinedFilterTests {

        @Test
        @DisplayName("同租户不同知识库应严格隔离")
        void shouldIsolateByTenantAndKb() {
            // tenant-A, kb-100
            indexChunk("chunk-a100", "A公司的退款政策", "tenant-A", 100L);
            // tenant-A, kb-200
            indexChunk("chunk-a200", "A公司的配送政策", "tenant-A", 200L);
            // tenant-B, kb-100
            indexChunk("chunk-b100", "B公司的退款政策", "tenant-B", 100L);

            // tenant-A + kb-100 检索，应只返回 A公司的退款政策
            List<RetrievedChunk> results = retriever.retrieve("退款政策", 10, "tenant-A", 100L);
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getChunkId()).isEqualTo("chunk-a100");
            assertThat(results.get(0).getTenantId()).isEqualTo("tenant-A");
            assertThat(results.get(0).getKbId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("无过滤时应返回所有匹配切片（兼容旧调用）")
        void shouldReturnAllWithoutFilter() {
            indexChunk("chunk-1", "Spring Boot 开发", "tenant-A", 100L);
            indexChunk("chunk-2", "Spring Cloud 微服务", "tenant-B", 200L);

            // 无过滤检索
            List<RetrievedChunk> results = retriever.retrieve("Spring", 10, null, null);
            assertThat(results).hasSize(2);
        }
    }

    @Nested
    @DisplayName("删除操作的租户隔离")
    class DeleteIsolationTests {

        @Test
        @DisplayName("deleteByKbId 只删除指定知识库的切片")
        void shouldOnlyDeleteByKbId() {
            indexChunk("chunk-100", "知识库100的内容", "tenant-A", 100L);
            indexChunk("chunk-200", "知识库200的内容", "tenant-A", 200L);

            // 删除知识库 100
            retriever.deleteByKbId("100");

            // 知识库 100 的切片应被删除
            List<RetrievedChunk> results100 = retriever.retrieve("内容", 10, "tenant-A", 100L);
            assertThat(results100).isEmpty();

            // 知识库 200 的切片应保留
            List<RetrievedChunk> results200 = retriever.retrieve("内容", 10, "tenant-A", 200L);
            assertThat(results200).isNotEmpty();
        }

        @Test
        @DisplayName("deleteByTenantId 只删除指定租户的切片")
        void shouldOnlyDeleteByTenantId() {
            indexChunk("chunk-a", "租户A的内容", "tenant-A", 100L);
            indexChunk("chunk-b", "租户B的内容", "tenant-B", 200L);

            // 删除租户 A
            retriever.deleteByTenantId("tenant-A");

            // 租户 A 的切片应被删除
            List<RetrievedChunk> resultsA = retriever.retrieve("内容", 10, "tenant-A", null);
            assertThat(resultsA).isEmpty();

            // 租户 B 的切片应保留
            List<RetrievedChunk> resultsB = retriever.retrieve("内容", 10, "tenant-B", null);
            assertThat(resultsB).isNotEmpty();
        }
    }

    // ── 辅助方法 ─────────────────────────────────────────

    private void indexChunk(String chunkId, String content, String tenantId, Long kbId) {
        RetrievedChunk chunk = RetrievedChunk.builder()
                .chunkId(chunkId)
                .content(content)
                .documentName("测试文档")
                .tenantId(tenantId)
                .kbId(kbId)
                .chunkIndex(0)
                .build();
        retriever.indexChunk(chunk);
    }
}

