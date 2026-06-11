package com.example.aiagent.rag.retrieval;

import com.example.aiagent.rag.model.RetrievedChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Bm25Retriever 单元测试（内嵌 Lucene，无需 Spring 容器）
 *
 * 覆盖：初始化状态检测、空索引检索、索引和删除操作不抛异常、null 查询降级
 */
@DisplayName("Bm25Retriever - 内嵌 BM25 检索器")
class Bm25RetrieverTest {

    private Bm25Retriever retriever;

    @BeforeEach
    void setUp() throws Exception {
        retriever = new Bm25Retriever();
        // 注入默认 BM25 参数（替代 Spring @Value 注入）
        ReflectionTestUtils.setField(retriever, "bm25K1", 1.2f);
        ReflectionTestUtils.setField(retriever, "bm25B", 0.75f);
        // 手动触发 @PostConstruct（无 Spring 容器时需要手动调用）
        retriever.init();
    }

    @AfterEach
    void tearDown() {
        // 清理 Lucene 资源（替代 @PreDestroy）
        retriever.shutdown();
    }

    @Test
    @DisplayName("初始化后 isAvailable() 应返回 true（内嵌 Lucene 始终可用）")
    void shouldBeAvailableAfterInit() {
        assertThat(retriever.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("空索引时 retrieve() 应返回空列表，不抛异常")
    void shouldReturnEmptyListFromEmptyIndex() {
        List<RetrievedChunk> result = retriever.retrieve("Spring Boot 是什么？", 10);

        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("indexChunk() 应成功索引切片，不抛异常")
    void shouldIndexChunkSuccessfully() {
        RetrievedChunk chunk = RetrievedChunk.builder()
                .chunkId("test-chunk-1")
                .content("测试内容，Spring Boot 框架")
                .documentName("测试文档")
                .build();

        // 不应抛出异常
        assertDoesNotThrow(() -> retriever.indexChunk(chunk));

        // 索引后，文档数应为 1
        assertThat(retriever.getIndexedDocCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("deleteByDocumentName() 应成功删除，不抛异常")
    void shouldDeleteByDocumentNameSuccessfully() {
        // 先索引一个切片
        RetrievedChunk chunk = RetrievedChunk.builder()
                .chunkId("del-chunk-1")
                .content("待删除内容")
                .documentName("待删除文档")
                .build();
        retriever.indexChunk(chunk);

        // 删除应不抛异常
        assertDoesNotThrow(() -> retriever.deleteByDocumentName("待删除文档"));
    }

    @Test
    @DisplayName("retrieve() 传入 null 查询时应返回空列表，不抛异常")
    void shouldReturnEmptyForNullQuery() {
        List<RetrievedChunk> result = retriever.retrieve(null, 10);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("索引后检索应能命中相关文档")
    void shouldRetrieveAfterIndexing() {
        RetrievedChunk chunk = RetrievedChunk.builder()
                .chunkId("spring-chunk-1")
                .content("Spring Boot 是一个基于 Spring Framework 的开源 Java 框架")
                .documentName("Spring 文档")
                .build();
        retriever.indexChunk(chunk);

        List<RetrievedChunk> results = retriever.retrieve("Spring Boot", 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getChunkId()).isEqualTo("spring-chunk-1");
    }
}

