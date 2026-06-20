package com.example.aiagent.story.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoryProject {
    private Long id;
    private String tenantId;
    private String title;
    private String type;
    private String status;
    private String description;
    private Long linkedKbId;
    private String metadata;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
    private Integer chapterCount;
    private Integer scriptDraftCount;
}
