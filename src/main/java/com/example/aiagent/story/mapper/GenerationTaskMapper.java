package com.example.aiagent.story.mapper;

import com.example.aiagent.story.entity.GenerationTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface GenerationTaskMapper {
    Optional<GenerationTask> findById(@Param("id") Long id);
    void insert(GenerationTask task);
    void update(GenerationTask task);
}
