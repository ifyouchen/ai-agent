package com.example.aiagent.story.mapper;

import com.example.aiagent.story.entity.ScriptEpisode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ScriptEpisodeMapper {
    List<ScriptEpisode> findByDraftId(@Param("draftId") Long draftId);
    Optional<ScriptEpisode> findById(@Param("id") Long id);
    void insert(ScriptEpisode episode);
    void update(ScriptEpisode episode);
}
