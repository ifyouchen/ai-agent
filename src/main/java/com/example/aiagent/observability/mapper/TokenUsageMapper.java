package com.example.aiagent.observability.mapper;

import com.example.aiagent.observability.entity.TokenUsageRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Mapper
public interface TokenUsageMapper {

    void insert(TokenUsageRecord record);

    BigDecimal sumCostByUserSince(@Param("userId") String userId, @Param("since") Instant since);

    Long sumTokensByUserSince(@Param("userId") String userId, @Param("since") Instant since);

    List<Map<String, Object>> aggregateByModelSince(@Param("since") Instant since);

    List<Map<String, Object>> aggregateByUserSince(@Param("since") Instant since);

    Map<String, Object> aggregateUserSummarySince(@Param("userId") String userId,
                                                   @Param("since") Instant since);

    long countErrorsSince(@Param("since") Instant since);

    long countTotalSince(@Param("since") Instant since);

    /** 按天聚合费用（全局趋势折线图），返回 [{day, costUsd, totalTokens}] */
    List<Map<String, Object>> aggregateDailySince(@Param("since") Instant since);

    /** 按天聚合个人费用（个人趋势折线图），返回 [{day, costUsd, totalTokens}] */
    List<Map<String, Object>> aggregateDailyByUserSince(@Param("userId") String userId,
                                                         @Param("since") Instant since);

    /** 分页查询个人 Token 调用明细。 */
    List<Map<String, Object>> listUserDetailsSince(@Param("userId") String userId,
                                                   @Param("since") Instant since,
                                                   @Param("limit") int limit,
                                                   @Param("offset") int offset);

    long countUserDetailsSince(@Param("userId") String userId,
                                @Param("since") Instant since);

    /** 合并求和：一次查询得总成本 */
    BigDecimal sumCostSince(@Param("since") Instant since);

    /** 合并计数：一次查询得总调用数与错误数 */
    Map<String, Object> countTotalAndErrorsSince(@Param("since") Instant since);
}
