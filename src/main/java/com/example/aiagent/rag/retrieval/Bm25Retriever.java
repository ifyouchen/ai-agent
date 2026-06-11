package com.example.aiagent.rag.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchPhraseQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import com.example.aiagent.rag.model.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * BM25 检索器（基于 Elasticsearch）
 *
 * 配合向量检索做混合检索，互补各自的优势：
 * - 向量检索：语义相似，处理同义词/模糊匹配好
 * - BM25 检索：精确关键词匹配，处理专业术语/缩写好
 *
 * 设计原则：所有 ES 相关异常都在内部 catch，ES 不可用时降级返回空列表，不影响主流程。
 */
@Slf4j
@Component
public class Bm25Retriever {

    /** ES 客户端，required=false：ES 未启用时 Bean 不存在，此处注入 null，不报错 */
    @Autowired(required = false)
    private ElasticsearchClient elasticsearchClient;

    @Value("${rag.elasticsearch.index-name:rag-documents}")
    private String indexName;

    /**
     * 检查 BM25 检索是否可用（ES 客户端已注入）
     */
    public boolean isAvailable() {
        return elasticsearchClient != null;
    }

    /**
     * BM25 检索
     *
     * 查询策略：
     * - multi_match（best_fields）：在 content 和 documentName 字段上检索，documentName 权重 x2
     * - match_phrase（boost=2.0）：短语精确匹配，提升短语完整命中的排名
     * - 两者通过 bool.should 组合，任意匹配即可召回
     *
     * @param query 查询文本
     * @param topK  返回结果数
     * @return 检索结果列表，ES 不可用时返回空列表
     */
    public List<RetrievedChunk> retrieve(String query, int topK) {
        if (!isAvailable()) {
            log.debug("[BM25] Elasticsearch 未启用，跳过 BM25 检索");
            return Collections.emptyList();
        }

        try {
            // multi_match 查询：content 权重1.0，documentName 权重2.0
            Query multiMatchQuery = Query.of(q -> q
                    .multiMatch(MultiMatchQuery.of(mm -> mm
                            .query(query)
                            .fields("content^1.0", "documentName^2.0")
                            .type(TextQueryType.BestFields)
                    ))
            );

            // match_phrase 查询：短语精确匹配，boost=2.0
            Query matchPhraseQuery = Query.of(q -> q
                    .matchPhrase(MatchPhraseQuery.of(mp -> mp
                            .field("content")
                            .query(query)
                            .boost(2.0f)
                    ))
            );

            // bool.should 组合两个查询
            Query boolQuery = Query.of(q -> q
                    .bool(BoolQuery.of(b -> b
                            .should(multiMatchQuery)
                            .should(matchPhraseQuery)
                    ))
            );

            SearchResponse<Map> response = elasticsearchClient.search(s -> s
                    .index(indexName)
                    .query(boolQuery)
                    .size(topK),
                    Map.class
            );

            List<RetrievedChunk> results = response.hits().hits().stream()
                    .map(this::hitToChunk)
                    .collect(Collectors.toList());

            log.debug("[BM25] 检索完成，查询：'{}', 命中：{} 条", query, results.size());
            return results;

        } catch (Exception e) {
            log.warn("[BM25] 检索异常，降级返回空列表。原因：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 将文档切片索引到 Elasticsearch
     * 文档入库时调用，同步建立 BM25 倒排索引
     *
     * @param chunk 要索引的切片
     */
    public void indexChunk(RetrievedChunk chunk) {
        if (!isAvailable()) {
            log.debug("[BM25] Elasticsearch 未启用，跳过切片索引");
            return;
        }

        try {
            Map<String, Object> doc = new HashMap<>();
            doc.put("chunkId",       chunk.getChunkId());
            doc.put("content",       chunk.getContent());
            doc.put("documentName",  chunk.getDocumentName());
            doc.put("documentPath",  chunk.getDocumentPath());
            doc.put("pageNumber",    chunk.getPageNumber());
            doc.put("chunkIndex",    chunk.getChunkIndex());

            // 扩展字段（从 metadata 中提取，兼容多租户知识库）
            if (chunk.getMetadata() != null) {
                doc.put("kbId",     chunk.getMetadata().getOrDefault("kbId", null));
                doc.put("tenantId", chunk.getMetadata().getOrDefault("tenantId", null));
            }

            String docId = chunk.getChunkId() != null ? chunk.getChunkId()
                    : chunk.getDocumentName() + "_" + chunk.getChunkIndex();

            elasticsearchClient.index(IndexRequest.of(i -> i
                    .index(indexName)
                    .id(docId)
                    .document(doc)
            ));

            log.debug("[BM25] 切片已索引到 ES，chunkId={}", docId);

        } catch (Exception e) {
            log.warn("[BM25] 切片索引异常，chunkId={}，原因：{}", chunk.getChunkId(), e.getMessage());
        }
    }

    /**
     * 删除指定文档名称下的所有切片（文档删除时调用）
     *
     * @param documentName 文档名称
     */
    public void deleteByDocumentName(String documentName) {
        if (!isAvailable()) {
            log.debug("[BM25] Elasticsearch 未启用，跳过切片删除");
            return;
        }

        try {
            elasticsearchClient.deleteByQuery(DeleteByQueryRequest.of(d -> d
                    .index(indexName)
                    .query(q -> q
                            .term(t -> t
                                    .field("documentName.keyword")
                                    .value(documentName)
                            )
                    )
            ));

            log.info("[BM25] 已从 ES 删除文档切片，documentName={}", documentName);

        } catch (Exception e) {
            log.warn("[BM25] 删除 ES 切片异常，documentName={}，原因：{}", documentName, e.getMessage());
        }
    }

    /** 将 ES Hit 转换为 RetrievedChunk */
    @SuppressWarnings("unchecked")
    private RetrievedChunk hitToChunk(Hit<Map> hit) {
        Map<String, Object> source = hit.source() != null ? hit.source() : Collections.emptyMap();

        return RetrievedChunk.builder()
                .chunkId(     getStr(source, "chunkId"))
                .content(     getStr(source, "content"))
                .documentName(getStr(source, "documentName"))
                .documentPath(getStr(source, "documentPath"))
                .pageNumber(  getInt(source, "pageNumber"))
                .chunkIndex(  getInt(source, "chunkIndex"))
                .bm25Score(   hit.score() != null ? hit.score() : 0.0)
                .retrievalSource(RetrievedChunk.RetrievalSource.BM25_ONLY)
                .build();
    }

    private String getStr(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private Integer getInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return null; }
    }
}
