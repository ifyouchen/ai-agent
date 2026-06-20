package com.example.aiagent.story.mapper;

import com.example.aiagent.story.entity.StoryChapterVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface StoryChapterVersionMapper {
    List<StoryChapterVersion> findByChapterId(@Param("chapterId") Long chapterId);
    Optional<StoryChapterVersion> findById(@Param("id") Long id);
    Optional<StoryChapterVersion> findByChapterIdAndVersionNo(@Param("chapterId") Long chapterId,
                                                              @Param("versionNo") Integer versionNo);
    void insert(StoryChapterVersion version);
}
