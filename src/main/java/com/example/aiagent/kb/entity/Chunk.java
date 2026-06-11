package com.example.aiagent.kb.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 文档切片实体（对应 kb_chunk 表）
 *
 * 核心存储单元，每条记录对应一个文本切片。
 * embedding 向量由 pgvector 扩展单独管理（不在此 Entity 中）。
 * metadata 以 JSON 字符串存储页码、章节等附加信息。
 */
@Entity
@Table(name = "kb_chunk",
       indexes = {
           @Index(name = "idx_chunk_doc_id", columnList = "doc_id"),
           @Index(name = "idx_chunk_kb_id",  columnList = "kb_id, is_active")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Chunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属文档 ID */
    @Column(name = "doc_id", nullable = false)
    private Long docId;

    /** 所属知识库 ID（冗余，加速按 kb 查询） */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    /** 在文档中的顺序索引（从 0 开始） */
    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    /** 切片文本内容 */
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 内容 Hash（增量更新时对比，避免重复 embedding） */
    @Column(name = "content_hash", length = 64, nullable = false)
    private String contentHash;

    /**
     * 元数据（JSON），存储页码、章节标题等，如：
     * {"page":3,"section":"第一章","source_file":"xxx.pdf"}
     */
    @Column(name = "metadata", columnDefinition = "TEXT", nullable = false)
    @Builder.Default
    private String metadata = "{}";

    /** 当前切片的 Token 数量 */
    @Column(name = "token_count")
    private Integer tokenCount;

    /** 是否激活（软删除标记） */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
