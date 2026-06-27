package com.example.aiagent.billing.mapper;

import com.example.aiagent.billing.entity.UsageReservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

@Mapper
public interface UsageReservationMapper {

    void insert(UsageReservation reservation);

    UsageReservation findByReservationNo(@Param("reservationNo") String reservationNo);

    int markSettled(@Param("reservationNo") String reservationNo,
                    @Param("actualCny") BigDecimal actualCny);

    int markReleased(@Param("reservationNo") String reservationNo);
}
