package com.example.aiagent.rag.generation;

import com.example.aiagent.rag.model.RagResponse;
import com.example.aiagent.rag.model.RetrievedChunk;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * CitationAwareGenerator 单元测试（Mock ChatLanguageModel）
 *
 * 覆盖：引用标记解析、越界引用忽略、无引用答案、空上下文处理、内容截断
 */
@DisplayName("CitationAwareGenerator - 带引用溯源的答案生成器")
@ExtendWith(MockitoExtension.class)
class CitationAwareGeneratorTest {

    @Mock
    private ChatLanguageModel chatModel;

    private CitationAwareGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new CitationAwareGenerator(chatModel);
    }

    // ── 引用解析 ──────────────────────────────────────────

    @Test
    @DisplayName("答案中的 [1][2] 引用标记应被正确解析为 Citation 列表")
    void shouldExtractCitationsFromAnswer() {
        when(chatModel.generate(anyString()))
                .thenReturn("根据文档 [1]，Spring Boot 通过自动配置简化开发。另外 [2] 中提到了微服务架构。");

        List<RetrievedChunk> contexts = List.of(
                chunkWithDoc("chunk-1", "Spring Boot 文档", 1),
                chunkWithDoc("chunk-2", "微服务指南", 2)
        );

        RagResponse response = generator.generateWithCitations("什么是 Spring Boot？", contexts);

        assertThat(response.getAnswer()).isNotBlank();
        assertThat(response.getCitations()).hasSize(2);
        assertThat(response.getCitations().get(0).getNumber()).isEqualTo(1);
        assertThat(response.getCitations().get(1).getNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("同一引用编号出现多次应去重，只返回一个 Citation")
    void shouldDeduplicateCitations() {
        when(chatModel.generate(anyString()))
                .thenReturn("第一处引用 [1]，第二处也引用 [1] 同一文档。");

        List<RetrievedChunk> contexts = List.of(chunkWithDoc("chunk-1", "唯一文档", 1));

        RagResponse response = generator.generateWithCitations("问题", contexts);

        assertThat(response.getCitations()).hasSize(1);
        assertThat(response.getCitations().get(0).getNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("越界引用编号（大于文档数）应被忽略")
    void shouldIgnoreOutOfBoundsCitationNumbers() {
        // 只有 2 个文档，但答案引用了 [3]
        when(chatModel.generate(anyString()))
                .thenReturn("文档一说 [1]，文档二说 [2]，不存在的引用 [3]。");

        List<RetrievedChunk> contexts = List.of(
                chunkWithDoc("c1", "文档1", 1),
                chunkWithDoc("c2", "文档2", 2)
        );

        RagResponse response = generator.generateWithCitations("问题", contexts);

        // [3] 应被忽略，只有 [1][2]
        assertThat(response.getCitations()).hasSize(2);
        assertThat(response.getCitations()).noneMatch(c -> c.getNumber() == 3);
    }

    @Test
    @DisplayName("零引用标记的答案应返回空 Citation 列表")
    void shouldReturnEmptyCitationsWhenNoMarkersInAnswer() {
        when(chatModel.generate(anyString()))
                .thenReturn("根据现有文档，暂无相关信息。");

        List<RetrievedChunk> contexts = List.of(chunkWithDoc("c1", "文档1", 1));

        RagResponse response = generator.generateWithCitations("不相关的问题", contexts);

        assertThat(response.getCitations()).isEmpty();
        assertThat(response.getAnswer()).isNotBlank();
    }

    // ── 摘要截断 ──────────────────────────────────────────

    @Test
    @DisplayName("超过150字的文档内容应在 Citation.excerpt 中截断并加省略号")
    void shouldTruncateExcerptAt150Chars() {
        when(chatModel.generate(anyString())).thenReturn("答案 [1]");

        String longContent = "这是一段非常长的文档内容，".repeat(20); // 远超150字
        RetrievedChunk longChunk = RetrievedChunk.builder()
                .chunkId("c1")
                .content(longContent)
                .documentName("长文档")
                .pageNumber(1)
                .rerankerScore(0.9)
                .build();

        RagResponse response = generator.generateWithCitations("问题", List.of(longChunk));

        assertThat(response.getCitations()).hasSize(1);
        String excerpt = response.getCitations().get(0).getExcerpt();
        assertThat(excerpt).endsWith("...");
        assertThat(excerpt.length()).isLessThanOrEqualTo(153); // 150 + "..." = 153
    }

    @Test
    @DisplayName("不超过150字的内容应完整保留在 Citation.excerpt 中")
    void shouldKeepShortContentInExcerpt() {
        when(chatModel.generate(anyString())).thenReturn("答案 [1]");

        String shortContent = "短内容，不超过150字。";
        RetrievedChunk shortChunk = RetrievedChunk.builder()
                .chunkId("c1")
                .content(shortContent)
                .documentName("短文档")
                .rerankerScore(0.8)
                .build();

        RagResponse response = generator.generateWithCitations("问题", List.of(shortChunk));

        assertThat(response.getCitations().get(0).getExcerpt()).isEqualTo(shortContent);
    }

    // ── Citation 元信息 ───────────────────────────────────

    @Test
    @DisplayName("Citation 应携带完整的文档元信息")
    void shouldPopulateCitationMetadata() {
        when(chatModel.generate(anyString())).thenReturn("答案 [1]");

        RetrievedChunk chunk = RetrievedChunk.builder()
                .chunkId("chunk-abc")
                .content("文档内容")
                .documentName("企业手册")
                .documentPath("/docs/manual.pdf")
                .pageNumber(5)
                .rerankerScore(0.95)
                .build();

        RagResponse response = generator.generateWithCitations("问题", List.of(chunk));

        RagResponse.Citation citation = response.getCitations().get(0);
        assertThat(citation.getChunkId()).isEqualTo("chunk-abc");
        assertThat(citation.getDocumentName()).isEqualTo("企业手册");
        assertThat(citation.getDocumentPath()).isEqualTo("/docs/manual.pdf");
        assertThat(citation.getPageNumber()).isEqualTo(5);
        assertThat(citation.getRelevanceScore()).isCloseTo(0.95, org.assertj.core.data.Offset.offset(1e-9));
    }

    // ── 统计信息 ──────────────────────────────────────────

    @Test
    @DisplayName("响应应包含有效的统计信息（afterReranking 等）")
    void shouldIncludeStats() {
        when(chatModel.generate(anyString())).thenReturn("答案 [1]");

        List<RetrievedChunk> contexts = List.of(chunkWithDoc("c1", "文档1", 1));
        RagResponse response = generator.generateWithCitations("问题", contexts);

        assertThat(response.getStats()).isNotNull();
        assertThat(response.getStats().getAfterReranking()).isEqualTo(1);
        assertThat(response.getStats().getGenerationTimeMs()).isGreaterThanOrEqualTo(0);
    }

    // ── 边界条件 ──────────────────────────────────────────

    @Test
    @DisplayName("空上下文列表时应正常生成答案（无 Citations）")
    void shouldHandleEmptyContexts() {
        when(chatModel.generate(anyString())).thenReturn("暂无相关信息。");

        RagResponse response = generator.generateWithCitations("问题", Collections.emptyList());

        assertThat(response.getAnswer()).isEqualTo("暂无相关信息。");
        assertThat(response.getCitations()).isEmpty();
    }

    // ── 辅助方法 ──────────────────────────────────────────

    private RetrievedChunk chunkWithDoc(String chunkId, String docName, int page) {
        return RetrievedChunk.builder()
                .chunkId(chunkId)
                .content("内容来自 " + docName + " 第" + page + "段")
                .documentName(docName)
                .pageNumber(page)
                .rerankerScore(0.8)
                .build();
    }
}

