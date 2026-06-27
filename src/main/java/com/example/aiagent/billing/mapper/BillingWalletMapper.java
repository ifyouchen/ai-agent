package com.example.aiagent.billing.mapper;

import com.example.aiagent.billing.entity.BillingWallet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

@Mapper
public interface BillingWalletMapper {

    void insertIfAbsent(@Param("userId") String userId);

    BillingWallet findByUserId(@Param("userId") String userId);

    int reserve(@Param("userId") String userId, @Param("amount") BigDecimal amount);

    int settleReserved(@Param("userId") String userId,
                       @Param("reserved") BigDecimal reserved,
                       @Param("actual") BigDecimal actual,
                       @Param("refund") BigDecimal refund,
                       @Param("overage") BigDecimal overage);

    int releaseReserved(@Param("userId") String userId, @Param("reserved") BigDecimal reserved);

    int creditRecharge(@Param("userId") String userId, @Param("amount") BigDecimal amount);
}
