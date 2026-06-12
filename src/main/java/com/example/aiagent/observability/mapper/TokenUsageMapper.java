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

    long countErrorsSince(@Param("since") Instant since);

    long countTotalSince(@Param("since") Instant since);

    /** 按天聚合费用（全局趋势折线图），返回 [{day, costUsd, totalTokens}] */
    List<Map<String, Object>> aggregateDailySince(@Param("since") Instant since);

    /** 按天聚合个人费用（个人趋势折线图），返回 [{day, costUsd, totalTokens}] */
    List<Map<String, Object>> aggregateDailyByUserSince(@Param("userId") String userId,
                                                         @Param("since") Instant since);
}
