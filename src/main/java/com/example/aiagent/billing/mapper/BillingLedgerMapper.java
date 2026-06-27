package com.example.aiagent.billing.mapper;

import com.example.aiagent.billing.entity.BillingLedger;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BillingLedgerMapper {

    void insert(BillingLedger ledger);

    List<BillingLedger> listByUser(@Param("userId") String userId,
                                   @Param("limit") int limit,
                                   @Param("offset") int offset);
}
