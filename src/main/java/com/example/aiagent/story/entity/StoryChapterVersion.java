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
public class StoryChapterVersion {
    private Long id;
    private Long chapterId;
    private Long projectId;
    private String title;
    private String content;
    private Integer wordCount;
    private Integer versionNo;
    private String source;
    private String note;
    private Instant createdAt;
}
