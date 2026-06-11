package com.example.aiagent.rag;

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
     * 核心导入逻辑：切片 → 向量化 → 存储
     */
    private int ingestDocuments(List<Document> documents) {
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(
                        DocumentSplitters.recursive(chunkSize, chunkOverlap)
                )
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        ingestor.ingest(documents);

        // 估算切片数量（实际数量由框架处理）
        int estimatedChunks = documents.stream()
                .mapToInt(d -> Math.max(1, d.text().length() / chunkSize))
                .sum();

        log.info("文档导入完成，共 {} 篇文档，约 {} 个切片", documents.size(), estimatedChunks);
        return estimatedChunks;
    }
}
