package com.example.aiagent.billing.mapper;

import com.example.aiagent.billing.entity.RechargeOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface RechargeOrderMapper {

    void insert(RechargeOrder order);

    RechargeOrder findByOrderNo(@Param("orderNo") String orderNo);

    RechargeOrder findByOrderNoForUpdate(@Param("orderNo") String orderNo);

    List<RechargeOrder> listByUser(@Param("userId") String userId,
                                   @Param("limit") int limit,
                                   @Param("offset") int offset);

    int markPaid(@Param("orderNo") String orderNo,
                 @Param("providerTradeNo") String providerTradeNo,
                 @Param("paidAt") Instant paidAt);
}
