package com.example.aiagent.story.mapper;

import com.example.aiagent.story.entity.StoryChapter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface StoryChapterMapper {
    List<StoryChapter> findByProjectId(@Param("projectId") Long projectId);
    Optional<StoryChapter> findById(@Param("id") Long id);
    Integer nextChapterNo(@Param("projectId") Long projectId);
    void insert(StoryChapter chapter);
    void update(StoryChapter chapter);
    void deleteById(@Param("id") Long id);
}
