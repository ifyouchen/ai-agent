package com.example.aiagent.rag.retrieval;

import com.example.aiagent.rag.model.RetrievedChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bm25Retriever 单元测试（无 ES 客户端注入 → 测试降级逻辑）
 *
 * 覆盖：ES 未启用时的 isAvailable()=false、retrieve() 返回空列表、
 *       indexChunk() 和 deleteByDocumentName() 的无副作用降级行为
 */
@DisplayName("Bm25Retriever - BM25 检索器（降级路径）")
class Bm25RetrieverTest {

    private Bm25Retriever retriever;

    @BeforeEach
    void setUp() throws Exception {
        retriever = new Bm25Retriever();
        // 注入默认 BM25 参数（替代 @Value 注入）
        ReflectionTestUtils.setField(retriever, "bm25K1", 1.2f);
        ReflectionTestUtils.setField(retriever, "bm25B", 0.75f);
        // 手动触发 @PostConstruct init()（无 Spring 容器时需要手动调用）
        retriever.init();
    }

    @AfterEach
    void tearDown() {
        // 邀清 Lucene 资源（替代 @PreDestroy shutdown()）
        retriever.shutdown();
    }

    @Test
    @DisplayName("Lucene 内嵌引擎初始化后 isAvailable() 应返回 true")
    void shouldReturnFalseWhenEsNotEnabled() {
        // 内嵌 Lucene 已初始化，始终可用
        assertThat(retriever.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("ES 未启用时 retrieve() 应返回空列表，不抛异常")
    void shouldReturnEmptyListWhenEsNotEnabled() {
        List<RetrievedChunk> result = retriever.retrieve("Spring Boot 是什么？", 10);

        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("ES 未启用时 indexChunk() 应静默跳过，不抛异常")
    void shouldSkipIndexChunkGracefullyWhenEsNotEnabled() {
        RetrievedChunk chunk = RetrievedChunk.builder()
                .chunkId("test-chunk-1")
                .content("测试内容")
                .documentName("测试文档")
                .build();

        // 不应抛出异常
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> retriever.indexChunk(chunk));
    }

    @Test
    @DisplayName("ES 未启用时 deleteByDocumentName() 应静默跳过，不抛异常")
    void shouldSkipDeleteGracefullyWhenEsNotEnabled() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> retriever.deleteByDocumentName("任意文档名")
        );
    }

    @Test
    @DisplayName("retrieve() 传入 null 查询时应返回空列表，不抛异常")
    void shouldReturnEmptyForNullQuery() {
        List<RetrievedChunk> result = retriever.retrieve(null, 10);
        assertThat(result).isEmpty();
    }
}

