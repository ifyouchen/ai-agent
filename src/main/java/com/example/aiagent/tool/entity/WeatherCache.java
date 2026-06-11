package com.example.aiagent.tool.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 天气缓存实体（对应 biz_weather_cache 表）
 * <p>
 * 用于缓存外部天气 API 的查询结果，默认有效期 30 分钟。
 */
@Entity
@Table(name = "biz_weather_cache")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 城市名称（唯一键） */
    @Column(name = "city", length = 128, nullable = false, unique = true)
    private String city;

    /** 天气描述，如：晴天、多云 */
    @Column(name = "weather_desc", length = 128)
    private String weatherDesc;

    /** 温度（°C） */
    @Column(name = "temperature", precision = 5, scale = 2)
    private BigDecimal temperature;

    /** 湿度（%） */
    @Column(name = "humidity")
    private Integer humidity;

    /** 风速（m/s） */
    @Column(name = "wind", precision = 6, scale = 2)
    private BigDecimal wind;

    /** 缓存写入时间，用于判断是否过期 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
