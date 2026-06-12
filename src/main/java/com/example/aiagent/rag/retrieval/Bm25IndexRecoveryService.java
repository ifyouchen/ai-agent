package com.example.aiagent.rag.retrieval;

import com.example.aiagent.kb.entity.Chunk;
import com.example.aiagent.kb.mapper.ChunkMapper;
import com.example.aiagent.rag.model.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * BM25 索引恢复服务
 *
 * <p>由于 Bm25Retriever 使用内存索引（{@link org.apache.lucene.store.ByteBuffersDirectory}），
 * 应用重启后索引丢失。本服务在应用启动完成后，从 kb_chunk 业务表中分页读取所有激活切片，
 * 重建 Lucene BM25 索引。
 *
 * <p>恢复策略：分页加载（每页 1000 条），避免一次性加载过多数据导致内存溢出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Bm25IndexRecoveryService {

    private final ChunkMapper chunkMapper;

    /** BM25 检索器，required=false */
    @Autowired(required = false)
    private Bm25Retriever bm25Retriever;

    /** 分页大小 */
    private static final int PAGE_SIZE = 1000;

    /**
     * 应用启动完成后，从数据库恢复 BM25 索引
     *
     * <p>使用 {@link ApplicationReadyEvent} 而非 {@code @PostConstruct}，
     * 确保所有 Bean 初始化完成后再开始恢复。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverIndexOnStartup() {
        if (bm25Retriever == null || !bm25Retriever.isAvailable()) {
            log.info("[BM25-Recovery] BM25 检索器未启用，跳过索引恢复");
            return;
        }

        // 磁盘持久化索引已有数据 → 直接跳过重建（避免重启时全量重索引）
        if (bm25Retriever.hasExistingIndex()) {
            log.info("[BM25-Recovery] 检测到已有持久化索引（{} 条切片），跳过重建",
                    bm25Retriever.getIndexedDocCount());
            return;
        }

        log.info("[BM25-Recovery] 开始从数据库恢复 BM25 索引...");
        long start = System.currentTimeMillis();

        try {
            int totalIndexed = recoverAllChunks();

            long elapsed = System.currentTimeMillis() - start;
            log.info("[BM25-Recovery] 索引恢复完成，共索引 {} 个切片，耗时 {}ms", totalIndexed, elapsed);

        } catch (Exception e) {
            log.error("[BM25-Recovery] 索引恢复失败: {}", e.getMessage(), e);
            // 恢复失败不影响应用启动，只是 BM25 检索不可用
        }
    }

    /**
     * 恢复指定知识库的 BM25 索引（供知识库重建索引时调用）
     *
     * @param kbId 知识库 ID
     * @return 索引的切片数量
     */
    public int recoverByKbId(Long kbId) {
        if (bm25Retriever == null || !bm25Retriever.isAvailable()) {
            return 0;
        }

        log.info("[BM25-Recovery] 开始恢复知识库 kbId={} 的索引", kbId);

        // 先删除该 kbId 的旧索引
        bm25Retriever.deleteByKbId(String.valueOf(kbId));

        // 从数据库加载该 kbId 的所有激活切片
        List<Chunk> chunks = chunkMapper.findActiveByKbId(kbId);

        for (Chunk chunk : chunks) {
            RetrievedChunk retrievedChunk = toRetrievedChunk(chunk);
            bm25Retriever.indexChunk(retrievedChunk);
        }

        log.info("[BM25-Recovery] 知识库 kbId={} 索引恢复完成，共 {} 个切片", kbId, chunks.size());
        return chunks.size();
    }

    /**
     * 从数据库分页恢复所有切片的 BM25 索引
     */
    private int recoverAllChunks() {
        int totalIndexed = 0;
        int offset = 0;

        while (true) {
            List<Chunk> chunks = chunkMapper.findActiveChunksPage(PAGE_SIZE, offset);
            if (chunks.isEmpty()) {
                break;
            }

            for (Chunk chunk : chunks) {
                RetrievedChunk retrievedChunk = toRetrievedChunk(chunk);
                bm25Retriever.indexChunk(retrievedChunk);
            }

            totalIndexed += chunks.size();
            offset += PAGE_SIZE;

            log.debug("[BM25-Recovery] 已恢复 {} 个切片，继续加载 offset={}...", totalIndexed, offset);

            // 如果本页不满，说明已经是最后一页
            if (chunks.size() < PAGE_SIZE) {
                break;
            }
        }

        return totalIndexed;
    }

    /**
     * 将业务实体 Chunk 转换为检索模型 RetrievedChunk
     */
    private RetrievedChunk toRetrievedChunk(Chunk chunk) {
        return RetrievedChunk.builder()
                .chunkId(String.valueOf(chunk.getId()))
                .content(chunk.getContent())
                .tenantId(chunk.getTenantId())
                .kbId(chunk.getKbId())
                .chunkIndex(chunk.getChunkIndex())
                .build();
    }
}

