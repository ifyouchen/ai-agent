package com.example.aiagent.rag.retrieval;

import com.example.aiagent.rag.model.RetrievedChunk;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * BM25 检索器 —— 基于 Apache Lucene 内嵌实现
 *
 * <p>相比外部 Elasticsearch 方案的优势：
 * <ul>
 *   <li>无需外部依赖服务，开箱即用（内存倒排索引）</li>
 *   <li>JVM 进程内运行，延迟极低（无网络 RTT）</li>
 *   <li>支持 BM25Similarity 原生算法，效果与 ES BM25 等价</li>
 *   <li>支持 MultiFieldQueryParser：content 和 documentName 同时检索</li>
 * </ul>
 *
 * <p>多租户隔离：检索和索引均支持按 tenantId + kbId 过滤，
 * 确保不同租户、不同知识库的文档切片互不干扰。
 *
 * <p>持久化说明：当前使用 {@link ByteBuffersDirectory}（内存索引），
 * 应用重启后需重建索引。生产环境可替换为 {@link org.apache.lucene.store.FSDirectory}
 * 并指向持久化目录，或在 {@link #indexChunk} 时将切片同步写回数据库，
 * 启动时从数据库恢复。
 *
 * <p>线程安全：使用 {@link ReentrantReadWriteLock} 保证并发读写安全。
 */
@Slf4j
@Component
public class Bm25Retriever {

    // ── Lucene 字段名常量 ────────────────────────────────────────

    private static final String FIELD_CHUNK_ID       = "chunkId";
    private static final String FIELD_CONTENT        = "content";
    private static final String FIELD_DOCUMENT_NAME  = "documentName";
    private static final String FIELD_DOCUMENT_PATH  = "documentPath";
    private static final String FIELD_PAGE_NUMBER    = "pageNumber";
    private static final String FIELD_CHUNK_INDEX    = "chunkIndex";
    private static final String FIELD_KB_ID          = "kbId";
    private static final String FIELD_TENANT_ID      = "tenantId";

    // ── 搜索字段权重（documentName 权重更高） ───────────────────

    private static final Map<String, Float> FIELD_BOOSTS = Map.of(
            FIELD_CONTENT,       1.0f,
            FIELD_DOCUMENT_NAME, 2.0f
    );

    @Value("${rag.bm25.k1:1.2}")
    private float bm25K1;

    @Value("${rag.bm25.b:0.75}")
    private float bm25B;

    @Value("${rag.bm25.analyzer:smartcn}")
    private String bm25Analyzer;

    /**
     * BM25 索引存储目录（可选持久化）
     * <ul>
     *   <li>留空（默认）→ ByteBuffersDirectory（内存，重启后由 Recovery 服务重建）</li>
     *   <li>填路径 → MMapDirectory（磁盘持久化，重启后索引直接可用，Recovery 可跳过重建）</li>
     * </ul>
     */
    @Value("${rag.bm25.index-dir:}")
    private String indexDir;

    // ── Lucene 核心组件 ──────────────────────────────────────────

    private Directory       directory;
    private Analyzer        analyzer;
    private IndexWriter     indexWriter;
    private DirectoryReader directoryReader;
    private IndexSearcher   indexSearcher;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // ── 生命周期 ──────────────────────────────────────────────────

    @PostConstruct
    public void init() throws IOException {
        analyzer = createAnalyzer();

        // 根据配置选择存储后端：磁盘持久化（MMapDirectory）或内存（ByteBuffersDirectory）
        if (indexDir != null && !indexDir.isBlank()) {
            var idxPath = Paths.get(indexDir.trim());
            Files.createDirectories(idxPath);
            directory = MMapDirectory.open(idxPath);
            log.info("[BM25-Lucene] 使用磁盘索引，路径：{}", idxPath.toAbsolutePath());
        } else {
            directory = new ByteBuffersDirectory();
            log.info("[BM25-Lucene] 使用内存索引（重启后需重建）");
        }

        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setSimilarity(new BM25Similarity(bm25K1, bm25B));
        // OpenMode.CREATE_OR_APPEND：有已有索引则追加，没有则新建
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        indexWriter = new IndexWriter(directory, config);
        indexWriter.commit();  // 确保初始索引存在，能被 DirectoryReader 打开

        directoryReader = DirectoryReader.open(directory);
        indexSearcher   = new IndexSearcher(directoryReader);
        indexSearcher.setSimilarity(new BM25Similarity(bm25K1, bm25B));

        log.info("[BM25-Lucene] 初始化完成，analyzer={}，k1={}, b={}，已有文档数={}",
                bm25Analyzer, bm25K1, bm25B, directoryReader.numDocs());
    }

    @PreDestroy
    public void shutdown() {
        try {
            if (directoryReader != null) directoryReader.close();
            if (indexWriter    != null) indexWriter.close();
            if (analyzer       != null) analyzer.close();
            if (directory      != null) directory.close();
            log.info("[BM25-Lucene] 索引已关闭");
        } catch (IOException e) {
            log.warn("[BM25-Lucene] 关闭时异常：{}", e.getMessage());
        }
    }

    // ── 公共 API ──────────────────────────────────────────────────

    /**
     * 检查 BM25 检索是否可用（内嵌实现始终可用）
     */
    public boolean isAvailable() {
        return directory != null && indexWriter != null;
    }

    /**
     * 检查是否已有持久化索引数据（供 Recovery 服务判断是否跳过重建）
     *
     * <p>仅磁盘索引（indexDir 非空）且索引非空时才有意义；内存索引始终返回 false。
     *
     * @return true 表示索引中已有文档，Recovery 可跳过重建
     */
    public boolean hasExistingIndex() {
        if (indexDir == null || indexDir.isBlank()) return false; // 内存索引，每次都需重建
        lock.readLock().lock();
        try {
            return directoryReader.numDocs() > 0;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * BM25 检索（带多租户隔离过滤）
     *
     * <p>查询策略：
     * <ul>
     *   <li>MultiFieldQueryParser：同时在 content（权重1.0）和 documentName（权重2.0）上检索</li>
     *   <li>Lucene BM25Similarity：与 ES 默认算法等价</li>
     *   <li>查询词 escape：防止特殊字符导致 ParseException</li>
     *   <li>tenantId/kbId 过滤：通过 BooleanQuery 组合，确保只检索指定租户和知识库的切片</li>
     * </ul>
     *
     * @param query    查询文本
     * @param topK     返回结果数
     * @param tenantId 租户 ID（null 表示不过滤）
     * @param kbId     知识库 ID（null 表示不过滤）
     * @return 检索结果列表，索引为空或解析失败时返回空列表
     */
    public List<RetrievedChunk> retrieve(String query, int topK, String tenantId, Long kbId) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        lock.readLock().lock();
        try {
            refreshReaderIfNeeded();

            String[] fields = { FIELD_CONTENT, FIELD_DOCUMENT_NAME };
            MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer, FIELD_BOOSTS);
            parser.setDefaultOperator(QueryParser.Operator.OR);

            Query textQuery = parser.parse(QueryParser.escape(query));

            // 构建带租户过滤的组合查询
            Query finalQuery = buildFilteredQuery(textQuery, tenantId, kbId);

            TopDocs topDocs = indexSearcher.search(finalQuery, Math.max(topK, 1));

            List<RetrievedChunk> results = new ArrayList<>();
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = indexSearcher.storedFields().document(sd.doc);
                results.add(docToChunk(doc, sd.score));
            }

            log.debug("[BM25-Lucene] 检索完成，query='{}', tenantId={}, kbId={}, hits={}",
                    query, tenantId, kbId, results.size());
            return results;

        } catch (org.apache.lucene.queryparser.classic.ParseException e) {
            log.warn("[BM25-Lucene] 查询解析失败，query='{}', 原因：{}", query, e.getMessage());
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("[BM25-Lucene] 检索异常，降级返回空列表。原因：{}", e.getMessage());
            return Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * BM25 检索（无租户过滤，兼容旧调用）
     *
     * @deprecated 请使用 {@link #retrieve(String, int, String, Long)} 传入 tenantId 和 kbId
     */
    @Deprecated
    public List<RetrievedChunk> retrieve(String query, int topK) {
        return retrieve(query, topK, null, null);
    }

    /**
     * 将文档切片索引到 Lucene（同步写入，供文档导入时调用）
     *
     * @param chunk 要索引的切片
     */
    public void indexChunk(RetrievedChunk chunk) {
        if (chunk == null || chunk.getContent() == null) return;

        lock.writeLock().lock();
        try {
            String docId = resolveDocId(chunk);

            // 先删除同 chunkId 的旧文档（幂等更新）
            indexWriter.deleteDocuments(new Term(FIELD_CHUNK_ID, docId));

            Document doc = buildDocument(chunk, docId);
            indexWriter.addDocument(doc);
            indexWriter.commit();

            log.debug("[BM25-Lucene] 切片已索引，chunkId={}, tenantId={}, kbId={}",
                    docId, chunk.getTenantId(), chunk.getKbId());
        } catch (IOException e) {
            log.warn("[BM25-Lucene] 切片索引异常，chunkId={}，原因：{}", chunk.getChunkId(), e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 删除指定文档名称下的所有切片（文档删除时调用）
     *
     * @param documentName 文档名称
     */
    public void deleteByDocumentName(String documentName) {
        if (documentName == null || documentName.isBlank()) return;

        lock.writeLock().lock();
        try {
            indexWriter.deleteDocuments(new Term(FIELD_DOCUMENT_NAME + ".raw", documentName));
            indexWriter.commit();
            log.info("[BM25-Lucene] 已删除文档切片，documentName={}", documentName);
        } catch (IOException e) {
            log.warn("[BM25-Lucene] 删除切片异常，documentName={}，原因：{}", documentName, e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 删除指定租户、知识库、文档名称下的切片，避免同名文档跨知识库误删。
     */
    public void deleteByDocumentName(String tenantId, Long kbId, String documentName) {
        if (documentName == null || documentName.isBlank()) return;

        lock.writeLock().lock();
        try {
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            builder.add(new TermQuery(new Term(FIELD_DOCUMENT_NAME + ".raw", documentName)),
                    BooleanClause.Occur.MUST);
            if (tenantId != null && !tenantId.isBlank()) {
                builder.add(new TermQuery(new Term(FIELD_TENANT_ID, tenantId)),
                        BooleanClause.Occur.MUST);
            }
            if (kbId != null) {
                builder.add(new TermQuery(new Term(FIELD_KB_ID, String.valueOf(kbId))),
                        BooleanClause.Occur.MUST);
            }
            indexWriter.deleteDocuments(builder.build());
            indexWriter.commit();
            log.info("[BM25-Lucene] 已删除文档切片，tenantId={}, kbId={}, documentName={}",
                    tenantId, kbId, documentName);
        } catch (IOException e) {
            log.warn("[BM25-Lucene] 删除切片异常，tenantId={}, kbId={}, documentName={}，原因：{}",
                    tenantId, kbId, documentName, e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 删除指定知识库下的所有切片
     *
     * @param kbId 知识库 ID
     */
    public void deleteByKbId(String kbId) {
        if (kbId == null || kbId.isBlank()) return;

        lock.writeLock().lock();
        try {
            indexWriter.deleteDocuments(new Term(FIELD_KB_ID, kbId));
            indexWriter.commit();
            log.info("[BM25-Lucene] 已删除知识库索引，kbId={}", kbId);
        } catch (IOException e) {
            log.warn("[BM25-Lucene] 删除知识库索引异常，kbId={}，原因：{}", kbId, e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 删除指定租户下的所有切片
     *
     * @param tenantId 租户 ID
     */
    public void deleteByTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return;

        lock.writeLock().lock();
        try {
            indexWriter.deleteDocuments(new Term(FIELD_TENANT_ID, tenantId));
            indexWriter.commit();
            log.info("[BM25-Lucene] 已删除租户索引，tenantId={}", tenantId);
        } catch (IOException e) {
            log.warn("[BM25-Lucene] 删除租户索引异常，tenantId={}，原因：{}", tenantId, e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取当前索引中的文档数量（用于监控/调试）
     */
    public int getIndexedDocCount() {
        lock.readLock().lock();
        try {
            refreshReaderIfNeeded();
            return directoryReader.numDocs();
        } catch (Exception e) {
            log.warn("[BM25-Lucene] 获取文档数失败：{}", e.getMessage());
            return -1;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ── 私有辅助方法 ──────────────────────────────────────────────

    /**
     * 构建带租户过滤的 Lucene 组合查询
     *
     * <p>使用 BooleanQuery 将文本检索与 tenantId/kbId 的精确过滤组合：
     * <pre>
     *   +textQuery        (MUST - 文本相关性检索)
     *   +tenantId:xxx     (MUST - 精确匹配租户)
     *   +kbId:yyy         (MUST - 精确匹配知识库)
     * </pre>
     *
     * @param textQuery 文本检索查询
     * @param tenantId  租户 ID（null 则不添加租户过滤）
     * @param kbId      知识库 ID（null 则不添加知识库过滤）
     * @return 组合后的 Lucene Query
     */
    private Query buildFilteredQuery(Query textQuery, String tenantId, Long kbId) {
        if (tenantId == null && kbId == null) {
            return textQuery;  // 无过滤，直接返回文本查询
        }

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(textQuery, BooleanClause.Occur.MUST);

        if (tenantId != null) {
            builder.add(new TermQuery(new Term(FIELD_TENANT_ID, tenantId)), BooleanClause.Occur.MUST);
        }
        if (kbId != null) {
            builder.add(new TermQuery(new Term(FIELD_KB_ID, String.valueOf(kbId))), BooleanClause.Occur.MUST);
        }

        return builder.build();
    }

    private Analyzer createAnalyzer() {
        if ("standard".equalsIgnoreCase(bm25Analyzer)) {
            return new StandardAnalyzer();
        }
        return new SmartChineseAnalyzer();
    }

    /**
     * 构建 Lucene Document
     * - StoredField：存储原始值，检索命中后取回
     * - TextField：用于全文分析检索
     * - StringField (KEYWORD)：用于精确匹配和删除
     */
    private Document buildDocument(RetrievedChunk chunk, String docId) {
        Document doc = new Document();

        // 关键字段（不分词，用于删除和精确过滤）
        doc.add(new StringField(FIELD_CHUNK_ID, docId, Field.Store.YES));

        // 全文检索字段
        doc.add(new TextField(FIELD_CONTENT, safeStr(chunk.getContent()), Field.Store.YES));
        doc.add(new TextField(FIELD_DOCUMENT_NAME, safeStr(chunk.getDocumentName()), Field.Store.YES));

        // 额外存储字段（不分词，仅存储以便检索后取回）
        doc.add(new StoredField(FIELD_DOCUMENT_PATH, safeStr(chunk.getDocumentPath())));
        if (chunk.getPageNumber() != null) {
            doc.add(new StoredField(FIELD_PAGE_NUMBER, chunk.getPageNumber()));
        }
        if (chunk.getChunkIndex() != null) {
            doc.add(new StoredField(FIELD_CHUNK_INDEX, chunk.getChunkIndex()));
        }

        // 多租户/知识库隔离字段（精确匹配，用于过滤）
        // 优先使用 RetrievedChunk 的直接字段，兼容旧的 metadata 方式
        String tenantId = chunk.getTenantId();
        if (tenantId == null && chunk.getMetadata() != null) {
            tenantId = chunk.getMetadata().get("tenantId");
        }
        if (tenantId != null) {
            doc.add(new StringField(FIELD_TENANT_ID, tenantId, Field.Store.YES));
        }

        Long kbId = chunk.getKbId();
        if (kbId == null && chunk.getMetadata() != null) {
            String kbIdStr = chunk.getMetadata().get("kbId");
            if (kbIdStr != null) {
                try { kbId = Long.parseLong(kbIdStr); } catch (NumberFormatException ignored) {}
            }
        }
        if (kbId != null) {
            doc.add(new StringField(FIELD_KB_ID, String.valueOf(kbId), Field.Store.YES));
        }

        // documentName.raw：用于 deleteByDocumentName 的精确删除
        doc.add(new StringField(FIELD_DOCUMENT_NAME + ".raw",
                safeStr(chunk.getDocumentName()), Field.Store.NO));

        return doc;
    }

    /**
     * 将 Lucene Document 和得分转换为 RetrievedChunk
     */
    private RetrievedChunk docToChunk(Document doc, float score) {
        Integer pageNum = null;
        IndexableField pf = doc.getField(FIELD_PAGE_NUMBER);
        if (pf != null && pf.numericValue() != null) {
            pageNum = pf.numericValue().intValue();
        }

        Integer chunkIdx = null;
        IndexableField cf = doc.getField(FIELD_CHUNK_INDEX);
        if (cf != null && cf.numericValue() != null) {
            chunkIdx = cf.numericValue().intValue();
        }

        // 读取 tenantId 和 kbId
        String tenantId = doc.get(FIELD_TENANT_ID);
        Long kbId = null;
        String kbIdStr = doc.get(FIELD_KB_ID);
        if (kbIdStr != null) {
            try { kbId = Long.parseLong(kbIdStr); } catch (NumberFormatException ignored) {}
        }

        return RetrievedChunk.builder()
                .chunkId(doc.get(FIELD_CHUNK_ID))
                .content(doc.get(FIELD_CONTENT))
                .documentName(doc.get(FIELD_DOCUMENT_NAME))
                .documentPath(doc.get(FIELD_DOCUMENT_PATH))
                .pageNumber(pageNum)
                .chunkIndex(chunkIdx)
                .tenantId(tenantId)
                .kbId(kbId)
                .vectorScore(0.0)
                .bm25Score(score)
                .rrfScore(0.0)
                .rerankerScore(0.0)
                .metadata(null)
                .retrievalSource(RetrievedChunk.RetrievalSource.BM25_ONLY)
                .build();
    }

    /**
     * 在读操作前尝试刷新 DirectoryReader（以看到最新写入）
     * <p>注意：此方法在 readLock 保护下调用，reopen 是轻量级操作（无变化时直接返回 this）
     */
    private void refreshReaderIfNeeded() {
        try {
            DirectoryReader newReader = DirectoryReader.openIfChanged(directoryReader);
            if (newReader != null) {
                directoryReader.close();
                directoryReader = newReader;
                indexSearcher   = new IndexSearcher(directoryReader);
                indexSearcher.setSimilarity(new BM25Similarity(bm25K1, bm25B));
            }
        } catch (IOException e) {
            log.debug("[BM25-Lucene] 刷新 reader 失败（可忽略）：{}", e.getMessage());
        }
    }

    private String resolveDocId(RetrievedChunk chunk) {
        return chunk.getChunkId() != null ? chunk.getChunkId()
                : safeStr(chunk.getDocumentName()) + "_" + safeStr(String.valueOf(chunk.getChunkIndex()));
    }

    private String safeStr(String s) { return s != null ? s : ""; }
}
