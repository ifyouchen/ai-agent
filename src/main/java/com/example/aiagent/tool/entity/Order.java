package com.example.aiagent.tool.entity;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 订单实体（对应 biz_order 表）
 * <p>
 * status 枚举值：PENDING | PAID | SHIPPED | DELIVERED | CANCELLED | REFUNDED
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    private Long id;

    /** 订单编号，如 #12345，全局唯一 */
    private String orderNo;

    /** 订单状态：PENDING / PAID / SHIPPED / DELIVERED / CANCELLED / REFUNDED */
    private String status;

    /** 订单金额 */
    private BigDecimal amount;

    /** 商品名称 */
    private String productName;

    /** 快递单号 */
    private String shippingNo;

    /** 快递公司 */
    private String shippingCompany;

    /** 预计到达日期 */
    private LocalDate expectedArrival;

    /** 下单用户 ID */
    private String userId;

    private Instant createdAt;

    private Instant updatedAt;
}
