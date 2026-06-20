package com.example.aiagent.story.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScriptEpisode {
    private Long id;
    private Long draftId;
    private Integer episodeNo;
    private String title;
    private String estimatedDuration;
    private String coreHook;
    private String mainConflict;
    private String endingHook;
    private String summary;
}
