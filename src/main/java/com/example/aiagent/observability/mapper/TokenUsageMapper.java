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
}
