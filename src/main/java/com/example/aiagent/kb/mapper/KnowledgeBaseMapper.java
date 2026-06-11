package com.example.aiagent.kb.mapper;

import com.example.aiagent.kb.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 知识库 MyBatis Mapper
 */
@Mapper
public interface KnowledgeBaseMapper {

    List<KnowledgeBase> findByTenantId(@Param("tenantId") String tenantId);

    Optional<KnowledgeBase> findByTenantIdAndName(@Param("tenantId") String tenantId,
                                                   @Param("name") String name);

    List<KnowledgeBase> findByTenantIdAndStatus(@Param("tenantId") String tenantId,
                                                 @Param("status") Integer status);

    void insert(KnowledgeBase kb);

    void updateDocCount(@Param("id") Long id, @Param("docCount") int docCount);

    void deleteById(@Param("id") Long id);

    Optional<KnowledgeBase> findById(@Param("id") Long id);
}
