package com.example.aiagent.kb.entity;

import lombok.*;

import java.time.Instant;

/**
 * 文档实体（对应 kb_document 表）
 *
 * 支持版本控制和增量更新：通过 fileHash 检测文件变化。
 * allowedRoles 在 Java 侧以逗号分隔字符串存储（如 "ROLE_ADMIN,ROLE_EDITOR"），
 * 需要时自行 split。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Document {

    private Long id;

    /** 所属知识库 ID */
    private Long kbId;

    private String tenantId;

    /** 文件显示名称 */
    private String name;

    /** 文档类型：PDF | WORD | EXCEL | HTML | TXT */
    private String docType;

    /** 存储路径（MinIO/OSS） */
    private String filePath;

    private Long fileSize;

    /** MD5，用于检测文件变化（增量更新） */
    private String fileHash;

    /**
     * 处理状态：PENDING | PARSING | CHUNKING | EMBEDDING | DONE | FAILED
     */
    @Builder.Default
    private String parseStatus = "PENDING";

    private String parseError;

    /** 切片数量（冗余字段） */
    @Builder.Default
    private int chunkCount = 0;

    /** 权限级别：0=公开 1=内部 2=保密 */
    @Builder.Default
    private Integer permissionLevel = 0;

    /**
     * 允许访问的角色列表，逗号分隔存储，如 "ROLE_ADMIN,ROLE_EDITOR"
     */
    private String allowedRoles;

    private String createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    /** 最后完成索引的时间 */
    private Instant indexedAt;
}
