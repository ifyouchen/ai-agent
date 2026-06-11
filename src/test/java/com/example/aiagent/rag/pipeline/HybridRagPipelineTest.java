package com.example.aiagent.rag.pipeline;

import com.example.aiagent.rag.generation.CitationAwareGenerator;
import com.example.aiagent.rag.model.RagResponse;
import com.example.aiagent.rag.model.RetrievedChunk;
import com.example.aiagent.rag.query.QueryRewriter;
import com.example.aiagent.rag.reranker.RerankerService;
import com.example.aiagent.rag.retrieval.Bm25Retriever;
import com.example.aiagent.rag.retrieval.RrfFusionRanker;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * HybridRagPipeline 单元测试
 *
 * 覆盖：完整 Pipeline 执行、BM25 未启用时的降级、统计信息填充、空检索结果处理
 */
@DisplayName("HybridRagPipeline - 混合 RAG Pipeline")
@ExtendWith(MockitoExtension.class)
class HybridRagPipelineTest {

    @Mock private QueryRewriter        queryRewriter;
    @Mock private EmbeddingModel       embeddingModel;
    @Mock private EmbeddingStore<TextSegment> embeddingStore;
    @Mock private RrfFusionRanker      rrfFusionRanker;
    @Mock private RerankerService      rerankerService;
    @Mock private CitationAwareGenerator citationGenerator;
    @Mock private Bm25Retriever        bm25Retriever;

    private HybridRagPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new HybridRagPipeline(
                queryRewriter, embeddingModel, embeddingStore,
                rrfFusionRanker, rerankerService, citationGenerator
        );
        injectField(pipeline, "vectorTopK", 20);
        injectField(pipeline, "vectorThreshold", 0.5);
        injectField(pipeline, "rrfTopK", 10);
        injectField(pipeline, "rerankerTopK", 5);
        injectField(pipeline, "queryVariants", 2);
    }

    // ── 完整 Pipeline 执行 ────────────────────────────────

    @Test
    @DisplayName("完整 Pipeline 应返回包含答案和统计信息的 RagResponse")
    void shouldExecuteFullPipeline() {
        String query = "什么是 Spring Boot？";
        setupMocks(query);

        RagResponse response = pipeline.execute(query);

        assertThat(response).isNotNull();
        assertThat(response.getAnswer()).isEqualTo("Spring Boot 是一个框架 [1]");
        assertThat(response.getStats()).isNotNull();
        assertThat(response.getRewrittenQueries()).isNotNull();
    }

    @Test
    @DisplayName("Pipeline 应调用查询改写（HyDE + 多角度）")
    void shouldInvokeQueryRewriting() {
        String query = "什么是 Spring Boot？";
        setupMocks(query);

        pipeline.execute(query);

        verify(queryRewriter).generateHypotheticalDocument(query);
        verify(queryRewriter).rewriteMultiPerspective(eq(query), anyInt());
    }

    @Test
    @DisplayName("BM25 未启用时应跳过 BM25 检索，仍正常完成 Pipeline")
    void shouldSkipBm25WhenNotAvailable() {
        // bm25Retriever 不注入（null）
        String query = "测试查询";
        setupMocks(query);

        RagResponse response = pipeline.execute(query);

        assertThat(response).isNotNull();
        // bm25Retriever 未注入，不应被调用
        verifyNoInteractions(bm25Retriever);
    }

    @Test
    @DisplayName("BM25 启用时应同时执行 BM25 检索")
    void shouldExecuteBm25WhenAvailable() {
        // 注入 bm25Retriever
        injectField(pipeline, "bm25Retriever", bm25Retriever);
        when(bm25Retriever.isAvailable()).thenReturn(true);
        when(bm25Retriever.retrieve(anyString(), anyInt())).thenReturn(Collections.emptyList());

        String query = "测试查询";
        setupMocks(query);

        pipeline.execute(query);

        verify(bm25Retriever).retrieve(eq(query), anyInt());
    }

    @Test
    @DisplayName("向量检索返回空结果时 Pipeline 应正常完成")
    void shouldHandleEmptyVectorResults() {
        String query = "无结果的查询";

        when(queryRewriter.generateHypotheticalDocument(query)).thenReturn("假设文档");
        when(queryRewriter.rewriteMultiPerspective(eq(query), anyInt()))
                .thenReturn(List.of(query));
        when(embeddingModel.embed(anyString()))
                .thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f})));
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(Collections.emptyList()));
        when(rrfFusionRanker.fuse(any(), anyInt())).thenReturn(Collections.emptyList());
        when(rerankerService.rerank(anyString(), any(), anyInt())).thenReturn(Collections.emptyList());

        RagResponse emptyResponse = RagResponse.builder()
                .answer("暂无相关信息")
                .citations(Collections.emptyList())
                .stats(RagResponse.RetrievalStats.builder().build())
                .build();
        when(citationGenerator.generateWithCitations(eq(query), any()))
                .thenReturn(emptyResponse);

        RagResponse response = pipeline.execute(query);

        assertThat(response).isNotNull();
        assertThat(response.getAnswer()).isEqualTo("暂无相关信息");
    }

    @Test
    @DisplayName("Pipeline 完成后 response 应包含 rewrittenQueries 统计")
    void shouldPopulateRewrittenQueriesInStats() {
        String query = "测试查询";
        setupMocks(query);

        RagResponse response = pipeline.execute(query);

        assertThat(response.getRewrittenQueries()).isNotNull().isNotEmpty();
        assertThat(response.getRewrittenQueries()).contains(query);
    }

    // ── 辅助方法 ──────────────────────────────────────────

    private void setupMocks(String query) {
        when(queryRewriter.generateHypotheticalDocument(query)).thenReturn("假设文档内容");
        when(queryRewriter.rewriteMultiPerspective(eq(query), anyInt()))
                .thenReturn(List.of(query, "改写版本"));
        when(embeddingModel.embed(anyString()))
                .thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f, 0.3f})));
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(Collections.emptyList()));

        List<RetrievedChunk> rrfResult = List.of(
                RetrievedChunk.builder()
                        .chunkId("c1").content("内容").documentName("文档").rrfScore(0.9).build()
        );
        when(rrfFusionRanker.fuse(any(), anyInt())).thenReturn(rrfResult);
        when(rerankerService.rerank(anyString(), any(), anyInt())).thenReturn(rrfResult);

        RagResponse mockResponse = RagResponse.builder()
                .answer("Spring Boot 是一个框架 [1]")
                .citations(Collections.emptyList())
                .stats(RagResponse.RetrievalStats.builder()
                        .afterReranking(1)
                        .generationTimeMs(100)
                        .build())
                .build();
        when(citationGenerator.generateWithCitations(eq(query), any()))
                .thenReturn(mockResponse);
    }

    private void injectField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("字段注入失败: " + fieldName, e);
        }
    }
}

