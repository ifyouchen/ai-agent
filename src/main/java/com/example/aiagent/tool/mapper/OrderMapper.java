package com.example.aiagent.tool.mapper;

import com.example.aiagent.tool.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface OrderMapper {

    Optional<Order> findByOrderNo(@Param("orderNo") String orderNo);

    void insert(Order order);
}
