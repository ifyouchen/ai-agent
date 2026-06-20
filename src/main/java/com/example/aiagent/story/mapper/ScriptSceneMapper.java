package com.example.aiagent.story.mapper;

import com.example.aiagent.story.entity.ScriptScene;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ScriptSceneMapper {
    List<ScriptScene> findByEpisodeId(@Param("episodeId") Long episodeId);
    Optional<ScriptScene> findById(@Param("id") Long id);
    Integer nextSceneNo(@Param("episodeId") Long episodeId);
    void insert(ScriptScene scene);
    void update(ScriptScene scene);
    void updateSceneNo(@Param("id") Long id, @Param("sceneNo") Integer sceneNo);
    void deleteById(@Param("id") Long id);
}
