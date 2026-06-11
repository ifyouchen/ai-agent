package com.example.aiagent.kb.entity;

import lombok.*;

import java.time.Instant;

/**
 * 文档切片实体（对应 kb_chunk 表）
 *
 * 核心存储单元，每条记录对应一个文本切片。
 * embedding 向量由 pgvector 扩展单独管理（不在此 Entity 中）。
 * metadata 以 JSON 字符串存储页码、章节等附加信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Chunk {

    private Long id;

    /** 所属文档 ID */
    private Long docId;

    /** 所属知识库 ID（冗余，加速按 kb 查询） */
    private Long kbId;

    private String tenantId;

    /** 在文档中的顺序索引（从 0 开始） */
    private int chunkIndex;

    /** 切片文本内容 */
    private String content;

    /** 内容 Hash（增量更新时对比，避免重复 embedding） */
    private String contentHash;

    /**
     * 元数据（JSON），存储页码、章节标题等，如：
     * {"page":3,"section":"第一章","source_file":"xxx.pdf"}
     */
    @Builder.Default
    private String metadata = "{}";

    /** 当前切片的 Token 数量 */
    private Integer tokenCount;

    /** 是否激活（软删除标记） */
    @Builder.Default
    private boolean isActive = true;

    private Instant createdAt;
}
