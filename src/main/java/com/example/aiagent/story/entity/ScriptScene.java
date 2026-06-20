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
public class ScriptScene {
    private Long id;
    private Long episodeId;
    private Integer sceneNo;
    private String sceneTitle;
    private String location;
    private String timeOfDay;
    private String characters;
    private String sceneFunction;
    private String estimatedDuration;
    private String visualAction;
    private String narration;
    private String dialogue;
    private String performanceCameraNote;
    private String hook;
    private Instant updatedAt;
}
