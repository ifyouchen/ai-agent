package com.example.aiagent.rag.retrieval;

import com.example.aiagent.rag.model.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RrfFusionRanker 单元测试
 *
 * 覆盖：单路融合、双路融合去重、RRF 得分正确性、topK 裁剪、空输入容错
 */
@DisplayName("RrfFusionRanker - RRF 融合排序")
class RrfFusionRankerTest {

    private RrfFusionRanker ranker;

    @BeforeEach
    void setUp() {
        ranker = new RrfFusionRanker();
    }

    // ── 基础功能 ──────────────────────────────────────────

    @Test
    @DisplayName("单路检索结果直接按 RRF 排序返回")
    void shouldFuseSingleList() {
        List<RetrievedChunk> vectorList = List.of(
                chunk("c1", 0.9),
                chunk("c2", 0.8),
                chunk("c3", 0.7)
        );

        Map<String, List<RetrievedChunk>> input = Map.of("vector", vectorList);
        List<RetrievedChunk> result = ranker.fuse(input, 3);

        assertThat(result).hasSize(3);
        // 排名越靠前，RRF 得分越高
        assertThat(result.get(0).getRrfScore()).isGreaterThan(result.get(1).getRrfScore());
        assertThat(result.get(1).getRrfScore()).isGreaterThan(result.get(2).getRrfScore());
    }

    @Test
    @DisplayName("双路检索同时命中同一文档应累加 RRF 得分")
    void shouldAccumulateScoreForDocumentInBothLists() {
        // c1 在两路中都出现，c2/c3 只在一路中出现
        List<RetrievedChunk> vectorList = List.of(
                chunk("c1", 0.9),
                chunk("c2", 0.8)
        );
        List<RetrievedChunk> bm25List = List.of(
                chunk("c1", 0.5),
                chunk("c3", 0.4)
        );

        Map<String, List<RetrievedChunk>> input = new LinkedHashMap<>();
        input.put("vector", vectorList);
        input.put("bm25",   bm25List);

        List<RetrievedChunk> result = ranker.fuse(input, 3);

        // c1 在两路均排第1，得分 = 1/(60+1) + 1/(60+1) > c2 或 c3 的单路得分
        RetrievedChunk c1 = result.stream().filter(c -> "c1".equals(c.getChunkId())).findFirst().orElseThrow();
        RetrievedChunk c2 = result.stream().filter(c -> "c2".equals(c.getChunkId())).findFirst().orElseThrow();

        assertThat(c1.getRrfScore()).isGreaterThan(c2.getRrfScore());
    }

    @Test
    @DisplayName("双路均命中的文档 retrievalSource 应为 BOTH")
    void shouldMarkRetrievalSourceAsBoth() {
        List<RetrievedChunk> vectorList = List.of(chunk("c1", 0.9));
        List<RetrievedChunk> bm25List   = List.of(chunk("c1", 0.5));

        Map<String, List<RetrievedChunk>> input = new LinkedHashMap<>();
        input.put("vector", vectorList);
        input.put("bm25",   bm25List);

        List<RetrievedChunk> result = ranker.fuse(input, 5);

        RetrievedChunk c1 = result.stream().filter(c -> "c1".equals(c.getChunkId())).findFirst().orElseThrow();
        assertThat(c1.getRetrievalSource()).isEqualTo(RetrievedChunk.RetrievalSource.BOTH);
    }

    @Test
    @DisplayName("topK 应限制返回数量")
    void shouldLimitByTopK() {
        List<RetrievedChunk> vectorList = List.of(
                chunk("c1", 0.9), chunk("c2", 0.8), chunk("c3", 0.7),
                chunk("c4", 0.6), chunk("c5", 0.5)
        );

        List<RetrievedChunk> result = ranker.fuse(Map.of("vector", vectorList), 3);

        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("topK 大于候选数时返回全部候选")
    void shouldReturnAllWhenTopKExceedsSize() {
        List<RetrievedChunk> vectorList = List.of(chunk("c1", 0.9), chunk("c2", 0.8));

        List<RetrievedChunk> result = ranker.fuse(Map.of("vector", vectorList), 100);

        assertThat(result).hasSize(2);
    }

    // ── RRF 公式验证 ──────────────────────────────────────

    @Test
    @DisplayName("RRF 得分应符合公式 1/(k+rank)，k=60")
    void shouldCalculateRrfScoreCorrectly() {
        // 只有一路，排名分别为 1,2（rank 从 0 开始，公式里 rank+1）
        List<RetrievedChunk> vectorList = List.of(chunk("c1", 0.9), chunk("c2", 0.8));
        List<RetrievedChunk> result = ranker.fuse(Map.of("vector", vectorList), 2);

        double expectedC1 = 1.0 / (60 + 1);   // rank=0 → 1/(60+1)
        double expectedC2 = 1.0 / (60 + 2);   // rank=1 → 1/(60+2)

        RetrievedChunk c1 = result.stream().filter(c -> "c1".equals(c.getChunkId())).findFirst().orElseThrow();
        RetrievedChunk c2 = result.stream().filter(c -> "c2".equals(c.getChunkId())).findFirst().orElseThrow();

        assertThat(c1.getRrfScore()).isCloseTo(expectedC1, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(c2.getRrfScore()).isCloseTo(expectedC2, org.assertj.core.data.Offset.offset(1e-9));
    }

    // ── 边界条件 ──────────────────────────────────────────

    @Test
    @DisplayName("空检索列表应返回空结果")
    void shouldReturnEmptyForEmptyInput() {
        List<RetrievedChunk> result = ranker.fuse(Map.of("vector", Collections.emptyList()), 5);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("空 Map 输入应返回空结果")
    void shouldReturnEmptyForEmptyMap() {
        List<RetrievedChunk> result = ranker.fuse(Collections.emptyMap(), 5);
        assertThat(result).isEmpty();
    }

    // ── 辅助方法 ──────────────────────────────────────────

    private RetrievedChunk chunk(String chunkId, double vectorScore) {
        return RetrievedChunk.builder()
                .chunkId(chunkId)
                .content("内容 " + chunkId)
                .documentName("文档")
                .vectorScore(vectorScore)
                .retrievalSource(RetrievedChunk.RetrievalSource.VECTOR_ONLY)
                .build();
    }
}

