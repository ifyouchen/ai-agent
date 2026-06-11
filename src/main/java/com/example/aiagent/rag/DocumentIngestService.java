package com.example.aiagent.rag;

import com.example.aiagent.rag.model.RetrievedChunk;
import com.example.aiagent.rag.retrieval.Bm25Retriever;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 知识库文档导入服务
 * 负责：加载文档 → 切片 → 向量化 → 存入 PgVector
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    /** BM25 检索器，required=false：ES 未启用时 Bean 不存在，注入 null，不影响启动 */
    @Autowired(required = false)
    private Bm25Retriever bm25Retriever;

    @Value("${agent.rag.chunk-size:500}")
    private int chunkSize;

    @Value("${agent.rag.chunk-overlap:50}")
    private int chunkOverlap;

    /**
     * 导入上传的文件（支持 PDF、Word、TXT 等）
     */
    public int ingestFile(MultipartFile file) throws IOException {
        // 保存到临时文件
        Path tempFile = Files.createTempFile("ingest-", "-" + file.getOriginalFilename());
        try {
            Files.copy(file.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            return ingestPath(tempFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * 导入本地文件路径
     */
    public int ingestPath(Path filePath) {
        log.info("开始导入文档: {}", filePath);

        Document document = FileSystemDocumentLoader.loadDocument(
                filePath,
                new ApacheTikaDocumentParser()  // 自动识别 PDF/Word/TXT 等格式
        );

        return ingestDocuments(List.of(document));
    }

    /**
     * 导入整个目录下的所有文档
     */
    public int ingestDirectory(Path dirPath) {
        log.info("开始批量导入目录: {}", dirPath);

        List<Document> documents = FileSystemDocumentLoader.loadDocuments(
                dirPath,
                new ApacheTikaDocumentParser()
        );

        return ingestDocuments(documents);
    }

    /**
     * 核心导入逻辑：切片 → 向量化 → 存储（PgVector）→ 同步索引到 ES（BM25）
     */
    private int ingestDocuments(List<Document> documents) {
        // 1. 手动切片，以便拿到切片列表用于 ES 索引
        var splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
        List<TextSegment> allSegments = documents.stream()
                .flatMap(doc -> splitter.split(doc).stream())
                .toList();

        // 2. 向量化 + 存入 PgVector（使用 EmbeddingStoreIngestor 统一处理）
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(chunkSize, chunkOverlap))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
        ingestor.ingest(documents);

        // 3. 同步索引到 Elasticsearch（BM25）
        if (bm25Retriever != null && bm25Retriever.isAvailable()) {
            log.info("[BM25] 开始将 {} 个切片索引到 Elasticsearch...", allSegments.size());
            for (int i = 0; i < allSegments.size(); i++) {
                TextSegment segment = allSegments.get(i);
                var metadata = segment.metadata();
                RetrievedChunk chunk = RetrievedChunk.builder()
                        .chunkId(     metadata.getString("chunkId") != null
                                        ? metadata.getString("chunkId")
                                        : metadata.getString("documentName") + "_" + i)
                        .content(     segment.text())
                        .documentName(metadata.getString("documentName"))
                        .documentPath(metadata.getString("documentPath"))
                        .pageNumber(  parseIntSafely(metadata.getString("pageNumber")))
                        .chunkIndex(  i)
                        .build();
                bm25Retriever.indexChunk(chunk);
            }
            log.info("[BM25] 切片索引完成，共 {} 个", allSegments.size());
        } else {
            log.debug("[BM25] Elasticsearch 未启用，跳过切片索引");
        }

        log.info("文档导入完成，共 {} 篇文档，{} 个切片", documents.size(), allSegments.size());
        return allSegments.size();
    }

    private Integer parseIntSafely(String value) {
        if (value == null) return null;
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return null; }
    }
}
