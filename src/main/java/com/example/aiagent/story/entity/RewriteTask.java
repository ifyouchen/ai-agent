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
public class RewriteTask {
    private Long id;
    private Long projectId;
    private Long chapterId;
    private String sourceType;
    private String sourceText;
    private String rewriteMode;
    private String instruction;
    private String status;
    private String segmentsJson;
    private String resultText;
    private String diffPayload;
    private Instant createdAt;
    private Instant completedAt;
}
