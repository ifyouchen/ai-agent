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
public class ScriptDraft {
    private Long id;
    private Long projectId;
    private String title;
    private String sourceRef;
    private Integer episodeCount;
    private String status;
    private Integer qualityScore;
    private String adaptationPlan;
    private String qualityReport;
    private Instant createdAt;
    private Instant updatedAt;
}
