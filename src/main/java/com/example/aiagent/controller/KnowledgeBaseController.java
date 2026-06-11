package com.example.aiagent.controller;

import com.example.aiagent.rag.DocumentIngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 知识库管理接口
 * 用于上传文档到 RAG 知识库
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final DocumentIngestService ingestService;

    /**
     * 上传并导入文档到知识库
     *
     * POST /api/v1/kb/ingest
     * Content-Type: multipart/form-data
     * file: 你的文档（PDF、Word、TXT 等）
     */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingestDocument(
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "文件不能为空"));
        }

        log.info("收到文档导入请求: {}, 大小: {} bytes",
                file.getOriginalFilename(), file.getSize());

        try {
            int chunks = ingestService.ingestFile(file);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "filename", file.getOriginalFilename(),
                    "chunks", chunks,
                    "message", "文档导入成功，已切分为 " + chunks + " 个片段"
            ));
        } catch (IOException e) {
            log.error("文档导入失败: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "导入失败：" + e.getMessage()));
        }
    }

    /**
     * 健康检查（验证知识库是否可用）
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "message", "知识库服务正常");
    }
}
