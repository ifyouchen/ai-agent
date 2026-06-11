package com.example.aiagent.rag.retrieval;

import com.example.aiagent.rag.model.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    /** 不注入 ES 客户端，模拟 ES 未启用 */
    private Bm25Retriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new Bm25Retriever();
        // elasticsearchClient 字段保持 null（不注入）→ isAvailable()=false
    }

    @Test
    @DisplayName("ES 未启用时 isAvailable() 应返回 false")
    void shouldReturnFalseWhenEsNotEnabled() {
        assertThat(retriever.isAvailable()).isFalse();
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

