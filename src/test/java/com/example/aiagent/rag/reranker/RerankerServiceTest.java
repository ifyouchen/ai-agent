package com.example.aiagent.rag.reranker;

import com.example.aiagent.rag.model.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * RerankerService 单元测试（Mock RestTemplate）
 *
 * 覆盖：LLM 内置 reranker 正确排序、topK 裁剪、BGE 降级处理、空候选处理
 */
@DisplayName("RerankerService - Reranker 精排服务")
@ExtendWith(MockitoExtension.class)
class RerankerServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private RerankerService rerankerService;

    @BeforeEach
    void setUp() {
        rerankerService = new RerankerService(restTemplate);
        // 使用反射注入 @Value 字段
        injectField(rerankerService, "rerankerType", "llm");
        injectField(rerankerService, "bgeUrl", "http://localhost:8090/rerank");
        injectField(rerankerService, "cohereApiKey", "");
        injectField(rerankerService, "cohereModel", "rerank-multilingual-v3.0");
    }

    // ── 基础功能（LLM 内置 Reranker）──────────────────────

    @Test
    @DisplayName("空候选列表应直接返回，不做任何处理")
    void shouldReturnEmptyForEmptyCandidates() {
        List<RetrievedChunk> result = rerankerService.rerank("查询", Collections.emptyList(), 5);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("topK 应限制返回数量")
    void shouldLimitByTopK() {
        List<RetrievedChunk> candidates = List.of(
                chunk("c1", 0.9), chunk("c2", 0.8), chunk("c3", 0.7),
                chunk("c4", 0.6), chunk("c5", 0.5)
        );

        // type=llm 时使用内置排序，直接按线性分数排
        injectField(rerankerService, "rerankerType", "llm");
        List<RetrievedChunk> result = rerankerService.rerank("查询", candidates, 3);

        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("topK 大于候选数时应返回全部候选")
    void shouldReturnAllWhenTopKExceedsCandidates() {
        List<RetrievedChunk> candidates = List.of(chunk("c1", 0.9), chunk("c2", 0.8));

        injectField(rerankerService, "rerankerType", "llm");
        List<RetrievedChunk> result = rerankerService.rerank("查询", candidates, 100);

        assertThat(result).hasSize(2);
    }

    // ── BGE 降级处理 ──────────────────────────────────────

    @Test
    @DisplayName("BGE 服务不可用时应降级为线性递减得分并正常返回")
    void shouldFallbackWhenBgeServiceUnavailable() {
        injectField(rerankerService, "rerankerType", "bge");

        // RestTemplate 抛出异常模拟服务不可用
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(),
                any(ParameterizedTypeReference.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        List<RetrievedChunk> candidates = List.of(
                chunk("c1", 0.9), chunk("c2", 0.8), chunk("c3", 0.7)
        );

        // 降级不应抛出异常，应正常返回
        List<RetrievedChunk> result = rerankerService.rerank("查询", candidates, 3);

        assertThat(result).hasSize(3);
        // 降级后得分应该是线性递减的
        assertThat(result.get(0).getRerankerScore())
                .isGreaterThan(result.get(result.size() - 1).getRerankerScore());
    }

    @Test
    @DisplayName("BGE 返回正确得分时应按得分重新排序")
    void shouldReorderByBgeScores() {
        injectField(rerankerService, "rerankerType", "bge");

        // c1 RRF 排第1，但 BGE 给 c2 更高得分
        List<RetrievedChunk> candidates = List.of(
                chunk("c1", 0.9), // BGE 得分将被赋予 0.3
                chunk("c2", 0.7)  // BGE 得分将被赋予 0.9
        );

        // 模拟 BGE 返回：c1=0.3, c2=0.9
        Map<String, Object> bgeResponse = Map.of("scores", List.of(0.3, 0.9));
        ResponseEntity<Map<String, Object>> responseEntity = ResponseEntity.ok(bgeResponse);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(responseEntity);

        List<RetrievedChunk> result = rerankerService.rerank("查询", candidates, 2);

        assertThat(result).hasSize(2);
        // c2 得分更高，应排第1
        assertThat(result.get(0).getChunkId()).isEqualTo("c2");
        assertThat(result.get(1).getChunkId()).isEqualTo("c1");
    }

    // ── 单个候选 ──────────────────────────────────────────

    @Test
    @DisplayName("单个候选应正常返回，不报错")
    void shouldHandleSingleCandidate() {
        injectField(rerankerService, "rerankerType", "llm");

        List<RetrievedChunk> candidates = List.of(chunk("c1", 0.9));
        List<RetrievedChunk> result = rerankerService.rerank("查询", candidates, 5);

        assertThat(result).hasSize(1);
    }

    // ── 辅助方法 ──────────────────────────────────────────

    private RetrievedChunk chunk(String chunkId, double rrfScore) {
        return RetrievedChunk.builder()
                .chunkId(chunkId)
                .content("内容 " + chunkId)
                .documentName("文档")
                .rrfScore(rrfScore)
                .retrievalSource(RetrievedChunk.RetrievalSource.VECTOR_ONLY)
                .build();
    }

    /** 使用反射注入 @Value 字段（测试环境无 Spring 上下文） */
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

