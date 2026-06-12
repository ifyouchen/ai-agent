package com.example.aiagent.rag;

import com.example.aiagent.kb.entity.Chunk;
import com.example.aiagent.kb.entity.Document;
import com.example.aiagent.kb.entity.KnowledgeBase;
import com.example.aiagent.kb.mapper.ChunkMapper;
import com.example.aiagent.kb.mapper.DocumentMapper;
import com.example.aiagent.kb.mapper.KnowledgeBaseMapper;
import com.example.aiagent.rag.model.RetrievedChunk;
import com.example.aiagent.rag.retrieval.Bm25Retriever;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;

/**
 * 知识库文档导入服务
 *
 * <p>负责完整的文档导入闭环：
 * <pre>
 *   上传文件 → 解析文档 → 切片 → 向量化 → 存入 PgVector
 *                                      ↓
 *                              同步索引到 Lucene（BM25）
 *                                      ↓
 *                        写入 kb_document / kb_chunk 业务表（元数据持久化）
 *                                      ↓
 *                        更新 kb_knowledge_base.docCount
 * </pre>
 *
 * <p>关键设计：
 * <ul>
 *   <li>只切片一次，PgVector 和 Lucene 共用同一批切片，保证两边数据一致</li>
 *   <li>TextSegment 的 metadata 中携带 tenantId 和 kbId，确保向量检索时能按租户过滤</li>
 *   <li>Document/Chunk 业务表同步写入，确保元数据可查询、可管理</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    /** BM25 检索器，required=false：未启用时 Bean 不存在，注入 null，不影响启动 */
    @Autowired(required = false)
    private Bm25Retriever bm25Retriever;

    /** 通过 ApplicationContext 获取自身代理，确保 @Async 跨方法调用生效 */
    @Autowired
    private ApplicationContext applicationContext;

    @Value("${agent.rag.chunk-size:500}")
    private int chunkSize;

    @Value("${agent.rag.chunk-overlap:50}")
    private int chunkOverlap;

    /**
     * Embedding API 单次批量上限。
     * 百度千帆 bge-large-zh 限制每次最多 16 条；
     * OpenAI / 其他模型通常支持更大批次（256+），但设小一些更安全。
     * 可通过 agent.rag.embedding-batch-size 覆盖。
     */
    @Value("${agent.rag.embedding-batch-size:16}")
    private int embeddingBatchSize;

    /**
     * 导入上传的文件到指定知识库（支持 PDF、Word、TXT 等）
     *
     * <p>完整闭环：
     * 1. 创建 Document 记录（状态 PENDING → PARSING → CHUNKING → EMBEDDING → DONE）
     * 2. 解析文档并切片
     * 3. 切片写入 kb_chunk 业务表
     * 4. 向量化并写入 PgVector（metadata 中携带 tenantId/kbId）
     * 5. 索引到 Lucene（BM25，metadata 中携带 tenantId/kbId）
     * 6. 更新 Document 状态为 DONE
     * 7. 更新 KnowledgeBase.docCount
     *
     * @param file     上传的文件
     * @param tenantId 租户 ID
     * @param kbId     知识库 ID
     * @return 切片数量
     */
    @Transactional
    public int ingestFile(MultipartFile file, String tenantId, Long kbId) throws IOException {
        // 保存到临时文件
        Path tempFile = Files.createTempFile("ingest-", "-" + file.getOriginalFilename());
        try {
            Files.copy(file.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            return ingestPath(tempFile, tenantId, kbId, file.getOriginalFilename(),
                    file.getSize(), file.getContentType());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * 异步导入文档：先创建 Document 记录并返回 documentId，后台异步完成解析和向量化
     *
     * <p>适用于大文件上传场景：HTTP 请求立即返回，用户无需等待解析完成。
     * 前端可通过轮询 {@code GET /api/v1/kb/{kbId}/documents} 接口的 parseStatus 字段获取进度。
     *
     * @param file     上传的文件
     * @param tenantId 租户 ID
     * @param kbId     知识库 ID
     * @return documentId（可用于前端轮询状态）
     */
    public Long ingestFileAsync(MultipartFile file, String tenantId, Long kbId) throws IOException {
        validateKnowledgeBase(tenantId, kbId);

        // 1. 保存文件到持久化临时目录（不能用系统临时文件，异步线程运行时文件还需存在）
        Path asyncDir = Files.createTempDirectory("ingest-async-");
        Path savedFile = asyncDir.resolve(file.getOriginalFilename() != null
                ? file.getOriginalFilename() : "upload");
        Files.copy(file.getInputStream(), savedFile, StandardCopyOption.REPLACE_EXISTING);

        // 2. 立即创建 Document 记录（状态 PENDING），获取 documentId
        String fileHash = computeFileHash(savedFile);
        Document docEntity = createDocumentEntity(tenantId, kbId,
                file.getOriginalFilename(), file.getSize(), fileHash, file.getContentType());

        // 3. 后台异步执行解析（不阻塞当前 HTTP 线程）
        // 通过 Spring 代理调用（确保 @Async 生效，避免同类自调用绕过 AOP）
        applicationContext.getBean(DocumentIngestService.class)
                .doIngestAsync(savedFile, tenantId, kbId, file.getOriginalFilename(),
                        file.getSize(), file.getContentType(), docEntity.getId(), asyncDir);

        log.info("文档已提交异步解析 documentId={} tenantId={} kbId={} file={}",
                docEntity.getId(), tenantId, kbId, file.getOriginalFilename());
        return docEntity.getId();
    }

    /**
     * 异步执行文档解析和向量化（内部方法，由 ingestFileAsync 触发）
     */
    @Async("documentIngestExecutor")
    public void doIngestAsync(Path filePath, String tenantId, Long kbId,
                              String fileName, Long fileSize, String contentType,
                              Long docEntityId, Path asyncDir) {
        try {
            log.info("[ASYNC] 开始后台解析文档 docId={} file={}", docEntityId, fileName);

            // 更新状态 → PARSING
            documentMapper.updateParseStatus(docEntityId, "PARSING");

            // 解析文档
            dev.langchain4j.data.document.Document document =
                    dev.langchain4j.data.document.loader.FileSystemDocumentLoader.loadDocument(
                            filePath,
                            new dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser()
                    );

            // 更新状态 → CHUNKING
            documentMapper.updateParseStatus(docEntityId, "CHUNKING");

            // 切片 + 向量化 + 写入 PgVector / Lucene
            int chunkCount = ingestSingleDocument(document, tenantId, kbId,
                    fileName, fileSize, docEntityId);

            // 完成
            documentMapper.updateParseStatus(docEntityId, "DONE");
            documentMapper.updateChunkCount(docEntityId, chunkCount);
            updateKbDocCount(kbId);

            log.info("[ASYNC] 文档解析完成 docId={} chunks={}", docEntityId, chunkCount);
        } catch (Exception e) {
            log.error("[ASYNC] 文档解析失败 docId={} file={}: {}", docEntityId, fileName, e.getMessage(), e);
            documentMapper.updateParseStatusWithError(docEntityId, "FAILED", e.getMessage());
        } finally {
            // 清理临时文件目录
            try { Files.deleteIfExists(filePath); } catch (IOException ignore) {}
            try { Files.deleteIfExists(asyncDir); } catch (IOException ignore) {}
        }
    }

    /**
     * 导入上传的文件（兼容旧调用，不写业务表）
     *
     * @deprecated 请使用 {@link #ingestFile(MultipartFile, String, Long)} 传入 tenantId 和 kbId
     */
    @Deprecated
    public int ingestFile(MultipartFile file) throws IOException {
        Path tempFile = Files.createTempFile("ingest-", "-" + file.getOriginalFilename());
        try {
            Files.copy(file.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            return ingestPath(tempFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * 导入本地文件路径到指定知识库
     *
     * @param filePath 文件路径
     * @param tenantId 租户 ID
     * @param kbId     知识库 ID
     */
    @Transactional
    public int ingestPath(Path filePath, String tenantId, Long kbId) {
        String fileName = filePath.getFileName().toString();
        try {
            long fileSize = Files.size(filePath);
            return ingestPath(filePath, tenantId, kbId, fileName, fileSize, null);
        } catch (IOException e) {
            throw new RuntimeException("无法读取文件: " + filePath, e);
        }
    }

    /**
     * 导入本地文件路径（兼容旧调用，不写业务表）
     *
     * @deprecated 请使用 {@link #ingestPath(Path, String, Long)} 传入 tenantId 和 kbId
     */
    @Deprecated
    public int ingestPath(Path filePath) {
        log.info("开始导入文档（无租户隔离）: {}", filePath);

        dev.langchain4j.data.document.Document document =
                dev.langchain4j.data.document.loader.FileSystemDocumentLoader.loadDocument(
                        filePath,
                        new dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser()
                );

        return ingestDocumentsLegacy(List.of(document));
    }

    /**
     * 导入整个目录下的所有文档到指定知识库
     *
     * @param dirPath  目录路径
     * @param tenantId 租户 ID
     * @param kbId     知识库 ID
     */
    @Transactional
    public int ingestDirectory(Path dirPath, String tenantId, Long kbId) {
        log.info("开始批量导入目录: {}，tenantId={}，kbId={}", dirPath, tenantId, kbId);

        List<dev.langchain4j.data.document.Document> documents =
                dev.langchain4j.data.document.loader.FileSystemDocumentLoader.loadDocuments(
                        dirPath,
                        new dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser()
                );

        // 校验知识库存在
        validateKnowledgeBase(tenantId, kbId);

        int totalChunks = 0;
        for (dev.langchain4j.data.document.Document doc : documents) {
            String docName = doc.metadata().getString("documentName") != null
                    ? doc.metadata().getString("documentName") : "unknown";
            totalChunks += ingestSingleDocument(doc, tenantId, kbId, docName, null, null);
        }

        log.info("目录导入完成，共 {} 篇文档，{} 个切片", documents.size(), totalChunks);
        return totalChunks;
    }

    /**
     * 导入整个目录（兼容旧调用）
     *
     * @deprecated 请使用 {@link #ingestDirectory(Path, String, Long)}
     */
    @Deprecated
    public int ingestDirectory(Path dirPath) {
        log.info("开始批量导入目录（无租户隔离）: {}", dirPath);

        List<dev.langchain4j.data.document.Document> documents =
                dev.langchain4j.data.document.loader.FileSystemDocumentLoader.loadDocuments(
                        dirPath,
                        new dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser()
                );

        return ingestDocumentsLegacy(documents);
    }

    // ── 核心导入逻辑（带完整闭环） ─────────────────────────────

    /**
     * 完整闭环的文档导入（单文件）
     *
     * <p>执行步骤：
     * 1. 校验知识库归属
     * 2. 创建 Document 记录（PENDING → PARSING）
     * 3. 解析文档并切片
     * 4. 逐切片写入 kb_chunk 表
     * 5. 向量化并写入 PgVector（metadata 含 tenantId/kbId）
     * 6. 索引到 Lucene（BM25，metadata 含 tenantId/kbId）
     * 7. 更新 Document 状态 → DONE
     * 8. 更新 KnowledgeBase.docCount
     */
    private int ingestPath(Path filePath, String tenantId, Long kbId,
                           String fileName, Long fileSize, String contentType) {
        log.info("开始导入文档: {}，tenantId={}，kbId={}", fileName, tenantId, kbId);

        // 1. 校验知识库存在且属于该租户
        validateKnowledgeBase(tenantId, kbId);

        // 2. 创建 Document 记录（状态 PENDING）
        String fileHash = computeFileHash(filePath);
        Document docEntity = createDocumentEntity(tenantId, kbId, fileName, fileSize, fileHash, contentType);

        try {
            // 3. 更新状态 → PARSING
            documentMapper.updateParseStatus(docEntity.getId(), "PARSING");

            // 4. 解析文档
            dev.langchain4j.data.document.Document document =
                    dev.langchain4j.data.document.loader.FileSystemDocumentLoader.loadDocument(
                            filePath,
                            new dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser()
                    );

            // 5. 更新状态 → CHUNKING
            documentMapper.updateParseStatus(docEntity.getId(), "CHUNKING");

            // 6. 切片 + 持久化
            int chunkCount = ingestSingleDocument(document, tenantId, kbId,
                    fileName, fileSize, docEntity.getId());

            // 7. 更新 Document 状态 → DONE，更新 chunkCount
            docEntity.setParseStatus("DONE");
            docEntity.setChunkCount(chunkCount);
            documentMapper.updateParseStatus(docEntity.getId(), "DONE");

            // 8. 更新知识库 docCount
            updateKbDocCount(kbId);

            log.info("文档导入完成，docId={}，tenantId={}，kbId={}，chunks={}",
                    docEntity.getId(), tenantId, kbId, chunkCount);
            return chunkCount;

        } catch (Exception e) {
            log.error("文档导入失败，docId={}，kbId={}，文件={}，原因：{}",
                    docEntity.getId(), kbId, fileName, e.getMessage(), e);
            documentMapper.updateParseStatusWithError(docEntity.getId(), "FAILED", e.getMessage());
            throw new RuntimeException("文档处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 处理单个文档的切片和索引（供 ingestPath 和 ingestDirectory 共用）
     *
     * @return 切片数量
     */
    private int ingestSingleDocument(dev.langchain4j.data.document.Document document,
                                     String tenantId, Long kbId,
                                     String fileName, Long fileSize,
                                     Long docEntityId) {
        // 切片
        var splitter = dev.langchain4j.data.document.splitter.DocumentSplitters
                .recursive(chunkSize, chunkOverlap);
        List<TextSegment> segments = splitter.split(document);

        // 如果没有 docEntityId（目录导入场景），创建 Document 记录
        if (docEntityId == null) {
            Document docEntity = Document.builder()
                    .kbId(kbId)
                    .tenantId(tenantId)
                    .name(fileName)
                    .docType(detectDocType(fileName))
                    .fileSize(fileSize)
                    .parseStatus("CHUNKING")
                    .build();
            documentMapper.insert(docEntity);
            docEntityId = docEntity.getId();
        }

        // 更新状态 → EMBEDDING
        documentMapper.updateParseStatus(docEntityId, "EMBEDDING");

        // 为每个切片附加 tenantId/kbId 到 metadata，并写入 kb_chunk 表
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);

            // 确保 metadata 中携带 tenantId 和 kbId（向量检索过滤用）
            segment.metadata().put("tenantId", tenantId);
            segment.metadata().put("kbId", String.valueOf(kbId));
            segment.metadata().put("chunkId", docEntityId + "_" + i);

            // 写入 kb_chunk 业务表
            Chunk chunkEntity = Chunk.builder()
                    .docId(docEntityId)
                    .kbId(kbId)
                    .tenantId(tenantId)
                    .chunkIndex(i)
                    .content(segment.text())
                    .contentHash(computeContentHash(segment.text()))
                    .metadata(buildChunkMetadataJson(segment))
                    .isActive(true)
                    .build();
            chunkMapper.insert(chunkEntity);

            // 更新 segment metadata 中的 chunkId 为数据库生成的 ID
            segment.metadata().put("chunkId", String.valueOf(chunkEntity.getId()));
        }

        // 向量化 + 存入 PgVector（分批调用，避免超出 API 单次批次限制）
        embedAllInBatches(segments);

        // 同步索引到 Lucene（BM25）
        if (bm25Retriever != null && bm25Retriever.isAvailable()) {
            log.info("[BM25] 开始将 {} 个切片索引到 Lucene（tenantId={}, kbId={}）...",
                    segments.size(), tenantId, kbId);
            for (int i = 0; i < segments.size(); i++) {
                TextSegment segment = segments.get(i);
                var metadata = segment.metadata();

                RetrievedChunk chunk = RetrievedChunk.builder()
                        .chunkId(metadata.getString("chunkId"))
                        .content(segment.text())
                        .documentName(metadata.getString("documentName") != null
                                ? metadata.getString("documentName") : fileName)
                        .documentPath(metadata.getString("documentPath"))
                        .pageNumber(parseIntSafely(metadata.getString("pageNumber")))
                        .chunkIndex(i)
                        .tenantId(tenantId)
                        .kbId(kbId)
                        .build();
                bm25Retriever.indexChunk(chunk);
            }
            log.info("[BM25] 切片索引完成，共 {} 个", segments.size());
        }

        return segments.size();
    }

    // ── 旧版导入逻辑（无租户隔离，兼容） ─────────────────────

    /**
     * 旧版导入逻辑：切片 → 向量化 → PgVector → Lucene（不写业务表）
     *
     * @deprecated 保留兼容，新调用应使用带 tenantId/kbId 的版本
     */
    private int ingestDocumentsLegacy(List<dev.langchain4j.data.document.Document> documents) {
        var splitter = dev.langchain4j.data.document.splitter.DocumentSplitters
                .recursive(chunkSize, chunkOverlap);
        List<TextSegment> allSegments = documents.stream()
                .flatMap(doc -> splitter.split(doc).stream())
                .toList();

        // 分批向量化，避免超出 API 单次批次限制
        embedAllInBatches(allSegments);

        if (bm25Retriever != null && bm25Retriever.isAvailable()) {
            log.info("[BM25] 开始将 {} 个切片索引到 Lucene...", allSegments.size());
            for (int i = 0; i < allSegments.size(); i++) {
                TextSegment segment = allSegments.get(i);
                var metadata = segment.metadata();
                RetrievedChunk chunk = RetrievedChunk.builder()
                        .chunkId(metadata.getString("chunkId") != null
                                ? metadata.getString("chunkId")
                                : metadata.getString("documentName") + "_" + i)
                        .content(segment.text())
                        .documentName(metadata.getString("documentName"))
                        .documentPath(metadata.getString("documentPath"))
                        .pageNumber(parseIntSafely(metadata.getString("pageNumber")))
                        .chunkIndex(i)
                        .build();
                bm25Retriever.indexChunk(chunk);
            }
            log.info("[BM25] 切片索引完成，共 {} 个", allSegments.size());
        }

        log.info("文档导入完成（无租户隔离），共 {} 篇文档，{} 个切片",
                documents.size(), allSegments.size());
        return allSegments.size();
    }

    // ── 辅助方法 ─────────────────────────────────────────────

    /**
     * 分批调用 EmbeddingModel，避免超出 API 单次批次上限（如百度千帆限制 16 条/次）。
     *
     * <p>策略：将 segments 按 {@code embeddingBatchSize} 分组，逐批调用 embedAll，
     * 每批调用后立即写入 EmbeddingStore，避免在内存中积累大量向量。
     *
     * @param segments 需要向量化并存储的所有切片
     */
    private void embedAllInBatches(List<TextSegment> segments) {
        int total = segments.size();
        int batchSize = Math.max(1, embeddingBatchSize);
        log.info("[Embed] 开始分批向量化，共 {} 个切片，每批 {} 条", total, batchSize);

        for (int start = 0; start < total; start += batchSize) {
            int end = Math.min(start + batchSize, total);
            List<TextSegment> batch = segments.subList(start, end);

            try {
                var embeddings = embeddingModel.embedAll(batch).content();
                embeddingStore.addAll(embeddings, batch);
                log.debug("[Embed] 批次 {}/{} 完成（{}-{}）",
                        (start / batchSize) + 1, (int) Math.ceil((double) total / batchSize),
                        start, end - 1);
            } catch (Exception e) {
                log.error("[Embed] 批次 {}-{} 向量化失败：{}", start, end - 1, e.getMessage(), e);
                throw e;  // 重新抛出，让外层事务回滚
            }
        }
        log.info("[Embed] 向量化完成，共 {} 个切片已写入向量库", total);
    }

    /** 校验知识库存在且属于该租户 */
    private void validateKnowledgeBase(String tenantId, Long kbId) {
        KnowledgeBase kb = knowledgeBaseMapper.findById(kbId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在：kbId=" + kbId));

        if (!kb.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException(
                    String.format("知识库 %d 不属于租户 %s", kbId, tenantId));
        }
    }

    /** 创建 Document 实体记录 */
    private Document createDocumentEntity(String tenantId, Long kbId,
                                          String fileName, Long fileSize,
                                          String fileHash, String contentType) {
        Document doc = Document.builder()
                .kbId(kbId)
                .tenantId(tenantId)
                .name(fileName)
                .docType(detectDocType(fileName))
                .fileSize(fileSize)
                .fileHash(fileHash)
                .parseStatus("PENDING")
                .chunkCount(0)
                .build();
        documentMapper.insert(doc);
        return doc;
    }

    /** 更新知识库的文档计数 */
    private void updateKbDocCount(Long kbId) {
        long docCount = documentMapper.countByKbId(kbId);
        knowledgeBaseMapper.updateDocCount(kbId, (int) docCount);
    }

    /** 根据文件名推断文档类型 */
    private String detectDocType(String fileName) {
        if (fileName == null) return "TXT";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf"))   return "PDF";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "WORD";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "EXCEL";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "HTML";
        return "TXT";
    }

    /** 计算文件 MD5 */
    private String computeFileHash(Path filePath) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] fileBytes = Files.readAllBytes(filePath);
            byte[] hashBytes = md.digest(fileBytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("计算文件哈希失败: {}", e.getMessage());
            return null;
        }
    }

    /** 计算内容哈希 */
    private String computeContentHash(String content) {
        if (content == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(content.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 构建 chunk 的 metadata JSON */
    private String buildChunkMetadataJson(TextSegment segment) {
        var metadata = segment.metadata();
        StringBuilder json = new StringBuilder("{");
        boolean first = true;

        if (metadata.getString("pageNumber") != null) {
            json.append("\"page\":").append(metadata.getString("pageNumber"));
            first = false;
        }
        if (metadata.getString("documentName") != null) {
            if (!first) json.append(",");
            json.append("\"source_file\":\"").append(escapeJson(metadata.getString("documentName"))).append("\"");
            first = false;
        }

        json.append("}");
        return json.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Integer parseIntSafely(String value) {
        if (value == null) return null;
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return null; }
    }
}
