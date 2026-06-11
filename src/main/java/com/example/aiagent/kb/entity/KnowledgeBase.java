package com.example.aiagent.kb.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 知识库实体（对应 kb_knowledge_base 表）
 *
 * 支持多租户隔离，每个租户可创建多个知识库。
 * chunk_config 以 JSON 字符串存储切片配置，如 {"chunk_size":500,"chunk_overlap":50}
 */
@Entity
@Table(name = "kb_knowledge_base",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "name"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 租户 ID（多租户隔离核心字段） */
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "name", length = 256, nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 向量嵌入模型，默认 all-minilm-l6-v2 */
    @Column(name = "embed_model", length = 128, nullable = false)
    @Builder.Default
    private String embedModel = "all-minilm-l6-v2";

    /**
     * 切片配置（JSON），如：{"chunk_size":500,"chunk_overlap":50}
     * 使用 columnDefinition = "TEXT" 保持兼容性（生产环境可改为 JSONB）
     */
    @Column(name = "chunk_config", columnDefinition = "TEXT", nullable = false)
    @Builder.Default
    private String chunkConfig = "{}";

    /** 状态：1=正常，0=已归档 */
    @Column(name = "status", nullable = false)
    @Builder.Default
    private Integer status = 1;

    /** 知识库内的文档数量（冗余字段，便于快速展示） */
    @Column(name = "doc_count", nullable = false)
    @Builder.Default
    private int docCount = 0;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
