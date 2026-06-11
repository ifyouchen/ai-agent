package com.example.aiagent.tool.repository;

import com.example.aiagent.tool.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 订单 Repository
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * 按订单编号查询，支持有 # 前缀和无前缀两种格式。
     *
     * @param orderNo 订单编号，如 #12345 或 12345
     */
    Optional<Order> findByOrderNo(String orderNo);
}
