package com.example.aiagent.rag.query;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * QueryRewriter 单元测试（Mock ChatLanguageModel）
 *
 * 覆盖：HyDE 文档生成、多角度改写（含原始问题）、关键词提取、数量限制
 */
@DisplayName("QueryRewriter - 查询改写器")
@ExtendWith(MockitoExtension.class)
class QueryRewriterTest {

    @Mock
    private ChatLanguageModel chatModel;

    private QueryRewriter rewriter;

    @BeforeEach
    void setUp() {
        rewriter = new QueryRewriter(chatModel);
    }

    // ── HyDE 假设文档生成 ─────────────────────────────────

    @Test
    @DisplayName("HyDE 应返回 LLM 生成的假设文档")
    void shouldGenerateHypotheticalDocument() {
        when(chatModel.generate(anyString()))
                .thenReturn("Spring Boot 是一个基于 Spring 框架的快速开发框架，通过自动配置减少样板代码...");

        String hydeDoc = rewriter.generateHypotheticalDocument("什么是 Spring Boot？");

        assertThat(hydeDoc).isNotBlank();
        assertThat(hydeDoc).contains("Spring Boot");
    }

    @Test
    @DisplayName("HyDE 返回的文档不应为空")
    void shouldReturnNonEmptyHydeDoc() {
        when(chatModel.generate(anyString())).thenReturn("假设性答案文档内容");

        String result = rewriter.generateHypotheticalDocument("测试问题");
        assertThat(result).isEqualTo("假设性答案文档内容");
    }

    // ── 多角度改写 ────────────────────────────────────────

    @Test
    @DisplayName("多角度改写应始终包含原始问题")
    void shouldAlwaysIncludeOriginalQuery() {
        when(chatModel.generate(anyString()))
                .thenReturn("Spring Boot 的定义是什么？\nSpring Boot 有哪些特性？");

        String original = "什么是 Spring Boot？";
        List<String> queries = rewriter.rewriteMultiPerspective(original, 2);

        assertThat(queries).contains(original);
    }

    @Test
    @DisplayName("改写变体数量不应超过 numVariants 限制")
    void shouldLimitVariantsCount() {
        // LLM 返回 3 行，但 numVariants=2，最多取 2 个变体
        when(chatModel.generate(anyString()))
                .thenReturn("改写1\n改写2\n改写3");

        List<String> queries = rewriter.rewriteMultiPerspective("原始问题", 2);

        // 结果 = 原始问题 + 最多2个变体 = 最多3个
        assertThat(queries.size()).isLessThanOrEqualTo(3);
        assertThat(queries).contains("原始问题");
    }

    @Test
    @DisplayName("LLM 返回空内容时应只包含原始问题")
    void shouldReturnOnlyOriginalWhenLlmReturnsEmpty() {
        when(chatModel.generate(anyString())).thenReturn("");

        List<String> queries = rewriter.rewriteMultiPerspective("原始问题", 2);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0)).isEqualTo("原始问题");
    }

    @Test
    @DisplayName("改写结果不应包含空字符串条目")
    void shouldFilterBlankLines() {
        when(chatModel.generate(anyString()))
                .thenReturn("有效改写1\n\n   \n有效改写2");

        List<String> queries = rewriter.rewriteMultiPerspective("原始问题", 3);

        assertThat(queries).doesNotContain("").doesNotContain("   ");
        queries.forEach(q -> assertThat(q.trim()).isNotEmpty());
    }

    // ── 关键词提取 ────────────────────────────────────────

    @Test
    @DisplayName("关键词提取数量不应超过 8 个")
    void shouldLimitKeywordsToEight() {
        when(chatModel.generate(anyString()))
                .thenReturn("Spring\nBoot\n自动配置\n微服务\n框架\n依赖注入\n注解\n应用程序\n额外关键词");

        List<String> keywords = rewriter.extractKeywords("Spring Boot 如何实现自动配置？");

        assertThat(keywords).hasSizeLessThanOrEqualTo(8);
    }

    @Test
    @DisplayName("关键词提取结果不应包含空条目")
    void shouldFilterBlankKeywords() {
        when(chatModel.generate(anyString()))
                .thenReturn("关键词1\n\n关键词2\n  ");

        List<String> keywords = rewriter.extractKeywords("测试问题");

        assertThat(keywords).doesNotContain("").doesNotContain("  ");
        keywords.forEach(k -> assertThat(k.trim()).isNotEmpty());
    }

    @Test
    @DisplayName("LLM 返回空时关键词列表应为空")
    void shouldReturnEmptyKeywordsWhenLlmReturnsEmpty() {
        when(chatModel.generate(anyString())).thenReturn("  ");

        List<String> keywords = rewriter.extractKeywords("测试");

        assertThat(keywords).isEmpty();
    }
}

