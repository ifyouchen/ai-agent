package com.example.aiagent.kb.repository;

import com.example.aiagent.kb.entity.RetrievalLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 检索日志 Repository
 */
@Repository
public interface RetrievalLogRepository extends JpaRepository<RetrievalLog, Long> {

    /**
     * 分页查询租户下的检索日志（按时间倒序）
     *
     * @param tenantId 租户 ID
     * @param pageable 分页参数，建议传入 Sort.by("createdAt").descending()
     */
    Page<RetrievalLog> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    /**
     * 查询知识库在指定时间之后的所有日志（用于效果分析时间窗口统计）
     */
    List<RetrievalLog> findByKbIdAndCreatedAtAfter(Long kbId, Instant createdAt);

    /**
     * 按 answerType 分组统计各类型数量（效果分析核心指标）
     *
     * 返回 Object[]：[answerType(String), count(Long)]
     * 示例：[["ANSWERED", 120], ["NO_ANSWER", 30], ["PARTIAL", 15]]
     */
    @Query("SELECT r.answerType, COUNT(r) FROM RetrievalLog r " +
           "WHERE r.tenantId = :tenantId AND r.kbId = :kbId " +
           "AND r.createdAt >= :since " +
           "GROUP BY r.answerType")
    List<Object[]> countGroupByAnswerType(@Param("tenantId") String tenantId,
                                          @Param("kbId") Long kbId,
                                          @Param("since") Instant since);

    /**
     * 统计指定时间之后的查询次数（快速计数，用于 stats 接口）
     */
    long countByTenantIdAndKbIdAndCreatedAtAfter(String tenantId, Long kbId, Instant createdAt);

    /**
     * 查询知识库最近 N 条日志（用于查询历史展示）
     */
    @Query("SELECT r FROM RetrievalLog r WHERE r.tenantId = :tenantId AND r.kbId = :kbId " +
           "ORDER BY r.createdAt DESC")
    List<RetrievalLog> findRecentByKbId(@Param("tenantId") String tenantId,
                                        @Param("kbId") Long kbId,
                                        Pageable pageable);
}
