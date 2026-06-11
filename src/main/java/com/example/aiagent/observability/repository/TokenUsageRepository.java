package com.example.aiagent.observability.repository;

import com.example.aiagent.observability.entity.TokenUsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Repository
public interface TokenUsageRepository extends JpaRepository<TokenUsageRecord, Long> {

    /** 查询某用户今日总费用 */
    @Query("SELECT COALESCE(SUM(r.costUsd), 0) FROM TokenUsageRecord r " +
           "WHERE r.userId = :userId AND r.calledAt >= :since")
    BigDecimal sumCostByUserSince(@Param("userId") String userId,
                                  @Param("since") Instant since);

    /** 查询某用户今日总 Token 数 */
    @Query("SELECT COALESCE(SUM(r.totalTokens), 0) FROM TokenUsageRecord r " +
           "WHERE r.userId = :userId AND r.calledAt >= :since")
    Long sumTokensByUserSince(@Param("userId") String userId,
                               @Param("since") Instant since);

    /** 按模型统计近 N 天的用量（用于成本报表） */
    @Query("SELECT r.modelName, SUM(r.inputTokens), SUM(r.outputTokens), SUM(r.costUsd) " +
           "FROM TokenUsageRecord r WHERE r.calledAt >= :since GROUP BY r.modelName")
    List<Object[]> aggregateByModelSince(@Param("since") Instant since);

    /** 按用户统计近 N 天的用量 */
    @Query("SELECT r.userId, SUM(r.totalTokens), SUM(r.costUsd) " +
           "FROM TokenUsageRecord r WHERE r.calledAt >= :since " +
           "GROUP BY r.userId ORDER BY SUM(r.costUsd) DESC")
    List<Object[]> aggregateByUserSince(@Param("since") Instant since);

    /** 查询某时间段内的错误率 */
    @Query("SELECT COUNT(r) FROM TokenUsageRecord r " +
           "WHERE r.success = false AND r.calledAt >= :since")
    long countErrorsSince(@Param("since") Instant since);

    @Query("SELECT COUNT(r) FROM TokenUsageRecord r WHERE r.calledAt >= :since")
    long countTotalSince(@Param("since") Instant since);
}
