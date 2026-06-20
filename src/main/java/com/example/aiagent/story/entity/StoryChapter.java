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
public class StoryChapter {
    private Long id;
    private Long projectId;
    private String title;
    private Integer chapterNo;
    private String content;
    private Integer wordCount;
    private Integer versionNo;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
