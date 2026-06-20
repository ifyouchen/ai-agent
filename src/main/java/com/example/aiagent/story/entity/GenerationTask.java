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
public class GenerationTask {
    private Long id;
    private String taskType;
    private Long projectId;
    private String status;
    private Integer progress;
    private String currentStep;
    private String errorMessage;
    private String tokenUsage;
    private Instant createdAt;
    private Instant updatedAt;
}
