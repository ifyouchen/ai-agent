package com.example.aiagent.story.mapper;

import com.example.aiagent.story.entity.ScriptDraft;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ScriptDraftMapper {
    Optional<ScriptDraft> findById(@Param("id") Long id);
    List<ScriptDraft> findByProjectId(@Param("projectId") Long projectId);
    void insert(ScriptDraft draft);
    void update(ScriptDraft draft);
}
