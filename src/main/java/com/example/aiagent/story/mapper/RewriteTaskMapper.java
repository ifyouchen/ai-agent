package com.example.aiagent.story.mapper;

import com.example.aiagent.story.entity.RewriteTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface RewriteTaskMapper {
    Optional<RewriteTask> findById(@Param("id") Long id);
    List<RewriteTask> findRecent(@Param("limit") int limit);
    void insert(RewriteTask task);
    void update(RewriteTask task);
}
