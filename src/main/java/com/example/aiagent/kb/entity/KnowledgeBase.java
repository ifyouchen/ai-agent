package com.example.aiagent.kb.entity;

import lombok.*;

import java.time.Instant;

/**
 * 知识库实体（对应 kb_knowledge_base 表）
 *
 * 支持多租户隔离，每个租户可创建多个知识库。
 * chunk_config 以 JSON 字符串存储切片配置，如 {"chunk_size":500,"chunk_overlap":50}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBase {

    private Long id;

    /** 租户 ID（多租户隔离核心字段） */
    private String tenantId;

    private String name;

    private String description;

    /** 向量嵌入模型，默认 all-minilm-l6-v2 */
    @Builder.Default
    private String embedModel = "all-minilm-l6-v2";

    /**
     * 切片配置（JSON），如：{"chunk_size":500,"chunk_overlap":50}
     */
    @Builder.Default
    private String chunkConfig = "{}";

    /** 状态：1=正常，0=已归档 */
    @Builder.Default
    private Integer status = 1;

    /** 知识库内的文档数量（冗余字段，便于快速展示） */
    @Builder.Default
    private int docCount = 0;

    private String createdBy;

    private Instant createdAt;

    private Instant updatedAt;
}
