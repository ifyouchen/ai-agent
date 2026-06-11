package com.example.aiagent.kb.repository;

import com.example.aiagent.kb.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 知识库 Repository
 */
@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    /**
     * 查询租户下的所有知识库
     */
    List<KnowledgeBase> findByTenantId(String tenantId);

    /**
     * 按租户和名称精确查询（名称在同一租户内唯一）
     */
    Optional<KnowledgeBase> findByTenantIdAndName(String tenantId, String name);

    /**
     * 查询租户下指定状态的知识库（1=正常，0=已归档）
     */
    List<KnowledgeBase> findByTenantIdAndStatus(String tenantId, Integer status);
}
