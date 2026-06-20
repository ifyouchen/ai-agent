package com.example.aiagent.story.mapper;

import com.example.aiagent.story.entity.StoryProject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface StoryProjectMapper {
    List<StoryProject> findByTenantId(@Param("tenantId") String tenantId, @Param("type") String type);
    Optional<StoryProject> findById(@Param("id") Long id);
    void insert(StoryProject project);
    void update(StoryProject project);
    void updateStatus(@Param("id") Long id, @Param("status") String status);
}
