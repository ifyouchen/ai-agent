package com.example.aiagent.kb.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 文档实体（对应 kb_document 表）
 *
 * 支持版本控制和增量更新：通过 fileHash 检测文件变化。
 * allowedRoles 在 Java 侧以逗号分隔字符串存储（如 "ROLE_ADMIN,ROLE_EDITOR"），
 * 需要时自行 split。
 */
@Entity
@Table(name = "kb_document",
       indexes = {
           @Index(name = "idx_doc_kb_id",  columnList = "kb_id"),
           @Index(name = "idx_doc_tenant", columnList = "tenant_id"),
           @Index(name = "idx_doc_status", columnList = "parse_status"),
           @Index(name = "idx_doc_hash",   columnList = "file_hash")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属知识库 ID */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    /** 文件显示名称 */
    @Column(name = "name", length = 512, nullable = false)
    private String name;

    /** 文档类型：PDF | WORD | EXCEL | HTML | TXT */
    @Column(name = "doc_type", length = 32, nullable = false)
    private String docType;

    /** 存储路径（MinIO/OSS） */
    @Column(name = "file_path", length = 1024)
    private String filePath;

    @Column(name = "file_size")
    private Long fileSize;

    /** MD5，用于检测文件变化（增量更新） */
    @Column(name = "file_hash", length = 64)
    private String fileHash;

    /**
     * 处理状态：PENDING | PARSING | CHUNKING | EMBEDDING | DONE | FAILED
     */
    @Column(name = "parse_status", length = 32, nullable = false)
    @Builder.Default
    private String parseStatus = "PENDING";

    @Column(name = "parse_error", columnDefinition = "TEXT")
    private String parseError;

    /** 切片数量（冗余字段） */
    @Column(name = "chunk_count", nullable = false)
    @Builder.Default
    private int chunkCount = 0;

    /** 权限级别：0=公开 1=内部 2=保密 */
    @Column(name = "permission_level", nullable = false)
    @Builder.Default
    private Integer permissionLevel = 0;

    /**
     * 允许访问的角色列表，逗号分隔存储，如 "ROLE_ADMIN,ROLE_EDITOR"
     * DB 侧对应 TEXT[] 数组列，Java 侧简化为字符串处理
     */
    @Column(name = "allowed_roles", columnDefinition = "TEXT")
    private String allowedRoles;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 最后完成索引的时间 */
    @Column(name = "indexed_at")
    private Instant indexedAt;

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
