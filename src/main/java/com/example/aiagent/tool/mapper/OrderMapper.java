package com.example.aiagent.tool.mapper;

import com.example.aiagent.tool.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface OrderMapper {

    /** 按订单号精确查询 */
    Optional<Order> findByOrderNo(@Param("orderNo") String orderNo);

    /** 按用户 ID 查询订单列表（最近 N 条） */
    List<Order> findByUserId(@Param("userId") String userId, @Param("limit") int limit);

    /** 按用户 ID 和状态查询订单数量 */
    int countByUserIdAndStatus(@Param("userId") String userId, @Param("status") String status);

    /** 查询用户所有状态的订单数量（按状态分组） */
    List<java.util.Map<String, Object>> countGroupByStatus(@Param("userId") String userId);

    void insert(Order order);
}
