package com.example.aiagent.tool.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 订单实体（对应 biz_order 表）
 * <p>
 * status 枚举值：PENDING | PAID | SHIPPED | DELIVERED | CANCELLED | REFUNDED
 */
@Entity
@Table(name = "biz_order")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 订单编号，如 #12345，全局唯一 */
    @Column(name = "order_no", length = 64, nullable = false, unique = true)
    private String orderNo;

    /** 订单状态：PENDING / PAID / SHIPPED / DELIVERED / CANCELLED / REFUNDED */
    @Column(name = "status", length = 32, nullable = false)
    private String status;

    /** 订单金额 */
    @Column(name = "amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    /** 商品名称 */
    @Column(name = "product_name", length = 256, nullable = false)
    private String productName;

    /** 快递单号 */
    @Column(name = "shipping_no", length = 64)
    private String shippingNo;

    /** 快递公司 */
    @Column(name = "shipping_company", length = 64)
    private String shippingCompany;

    /** 预计到达日期 */
    @Column(name = "expected_arrival")
    private LocalDate expectedArrival;

    /** 下单用户 ID */
    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
