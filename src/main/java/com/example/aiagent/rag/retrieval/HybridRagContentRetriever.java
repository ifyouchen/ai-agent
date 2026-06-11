package com.example.aiagent.rag.retrieval;

import com.example.aiagent.rag.model.RetrievedChunk;
import com.example.aiagent.rag.pipeline.HybridRagPipeline;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 混合 RAG 内容检索器（LangChain4j ContentRetriever 适配器）
 *
 * <p>解决问题：
 * LangChain4j 的 {@link dev.langchain4j.service.AiServices} 在构造 Agent 时，
 * 只接受 {@link ContentRetriever} 接口的内容检索实现。
 * 而项目的 {@link HybridRagPipeline} 是独立的 Service Bean，
 * 本类作为适配器，将 Pipeline 包装为 LangChain4j 可直接使用的 ContentRetriever。
 *
 * <p>检索流程（4 步混合 RAG，不含 LLM 生成）：
 * <pre>
 *   用户查询
 *     → [1] 查询改写（HyDE + 多角度改写）
 *     → [2] 混合检索（向量检索 + BM25）
 *     → [3] RRF 融合排序
 *     → [4] Reranker 精排
 *     → 返回 Content 列表（携带文档来源 Metadata，由 Agent 自行调用 LLM 生成答案）
 * </pre>
 *
 * <p>注意：此处仅执行步骤 1-4，不触发 Step 5（LLM 生成答案）。
 * 原先调用 {@code execute()} 会在检索阶段额外消耗一次 LLM 调用来生成答案然后丢弃，
 * 现已改用 {@code retrieveOnly()} 修复该性能浪费问题。
 *
 * <p>与原始 EmbeddingStoreContentRetriever 的对比：
 * <table>
 *   <tr><th>特性</th><th>EmbeddingStoreContentRetriever</th><th>HybridRagContentRetriever</th></tr>
 *   <tr><td>检索方式</td><td>仅向量检索</td><td>向量 + BM25 双路 + RRF 融合</td></tr>
 *   <tr><td>查询改写</td><td>无</td><td>HyDE + 多角度改写</td></tr>
 *   <tr><td>精排</td><td>无</td><td>4 种 Reranker（含降级）</td></tr>
 *   <tr><td>引用溯源</td><td>无</td><td>Metadata 携带文档名/路径/页码</td></tr>
 * </table>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridRagContentRetriever implements ContentRetriever {

    private final HybridRagPipeline hybridRagPipeline;

    /**
     * 实现 ContentRetriever 接口：将用户查询委托给 HybridRagPipeline 执行（仅检索阶段）
     *
     * <p>调用 {@code retrieveOnly()} 执行 Step 1-4，不触发 LLM 答案生成（Step 5）。
     * Agent 框架会将检索到的 Content 注入 Prompt，然后由 Agent 自己调用 LLM 生成答案。
     *
     * @param query LangChain4j 查询对象（包含用户文本）
     * @return 排序后的文档片段列表（LangChain4j Content 格式）
     */
    @Override
    public List<Content> retrieve(Query query) {
        String userText = query.text();
        log.debug("[HybridRAG] 开始检索，query='{}'", userText);

        try {
            // 执行完整的混合 RAG Pipeline（步骤 1-4）
            // 从 Pipeline 响应中提取经过精排的文档片段
            List<RetrievedChunk> rerankedChunks = retrieveChunks(userText);

            if (rerankedChunks.isEmpty()) {
                log.debug("[HybridRAG] 未检索到相关内容");
                return Collections.emptyList();
            }

            // 将 RetrievedChunk 转换为 LangChain4j 的 Content 格式
            List<Content> contents = rerankedChunks.stream()
                    .map(this::toContent)
                    .toList();

            log.debug("[HybridRAG] 检索完成，返回 {} 个片段", contents.size());
            return contents;

        } catch (Exception e) {
            // 检索失败不影响 Agent 正常对话，降级为空检索结果
            log.error("[HybridRAG] 检索异常，降级为空结果: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 执行 Pipeline 的检索阶段（步骤 1-4），提取精排后的 chunk 列表
     *
     * <p>直接调用 {@link HybridRagPipeline#retrieveOnly(String)}，
     * 仅执行查询改写 + 混合检索 + RRF 融合 + Reranker 精排，
     * 不触发 LLM 生成答案（Step 5），避免每次检索浪费一次 LLM 调用。
     */
    private List<RetrievedChunk> retrieveChunks(String userText) {
        return hybridRagPipeline.retrieveOnly(userText);
    }

    /**
     * 将 RetrievedChunk 转换为 LangChain4j Content
     *
     * Content 中的 Metadata 会被 LangChain4j 序列化注入 Prompt，
     * 格式示例：
     * <pre>
     * 文档：产品手册.pdf（第 3 页）
     * [文档内容片段...]
     * </pre>
     */
    private Content toContent(RetrievedChunk chunk) {
        // 构建元数据（文档溯源信息）
        Metadata metadata = new Metadata();

        if (chunk.getDocumentName() != null) {
            metadata.put("documentName", chunk.getDocumentName());
        }
        if (chunk.getDocumentPath() != null) {
            metadata.put("documentPath", chunk.getDocumentPath());
        }
        if (chunk.getPageNumber() != null) {
            metadata.put("pageNumber", String.valueOf(chunk.getPageNumber()));
        }
        if (chunk.getChunkId() != null) {
            metadata.put("chunkId", chunk.getChunkId());
        }

        // 构建带来源注释的文本（帮助 LLM 正确引用来源）
        StringBuilder contentText = new StringBuilder();
        if (chunk.getDocumentName() != null) {
            contentText.append("【来源：").append(chunk.getDocumentName());
            if (chunk.getPageNumber() != null) {
                contentText.append(" 第").append(chunk.getPageNumber()).append("页");
            }
            contentText.append("】\n");
        }
        contentText.append(chunk.getContent() != null ? chunk.getContent() : "");

        return Content.from(TextSegment.from(contentText.toString(), metadata));
    }
}

