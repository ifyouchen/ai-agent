package com.example.aiagent.story.mapper;

import com.example.aiagent.story.entity.ScriptEpisode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ScriptEpisodeMapper {
    List<ScriptEpisode> findByDraftId(@Param("draftId") Long draftId);
    void insert(ScriptEpisode episode);
}
