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

    @Value("${agent.rag.chunk-size:500}")
    private int chunkSize;

    @Value("${agent.rag.chunk-overlap:50}")
    private int chunkOverlap;

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

        // 向量化 + 存入 PgVector
        var embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);

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

        var embeddings = embeddingModel.embedAll(allSegments).content();
        embeddingStore.addAll(embeddings, allSegments);

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
