package com.example.aiagent.tool.entity;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 天气缓存实体（对应 biz_weather_cache 表）
 * <p>
 * 用于缓存外部天气 API 的查询结果，默认有效期 30 分钟。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherCache {

    private Long id;

    /** 城市名称（唯一键） */
    private String city;

    /** 天气描述，如：晴天、多云 */
    private String weatherDesc;

    /** 温度（°C） */
    private BigDecimal temperature;

    /** 湿度（%） */
    private Integer humidity;

    /** 风速（m/s） */
    private BigDecimal wind;

    /** 缓存写入时间，用于判断是否过期 */
    private Instant updatedAt;
}
