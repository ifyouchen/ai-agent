package com.example.aiagent.tool.repository;

import com.example.aiagent.tool.entity.WeatherCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

/**
 * 天气缓存 Repository
 */
public interface WeatherCacheRepository extends JpaRepository<WeatherCache, Long> {

    /**
     * 按城市名查询最新缓存（不判断是否过期，由业务层判断）。
     */
    Optional<WeatherCache> findByCity(String city);

    /**
     * 查询指定城市在 {@code after} 时刻之后更新的缓存（用于 30 分钟有效期校验）。
     *
     * @param city  城市名
     * @param after 有效期起始时刻（= 当前时间 - 30 分钟）
     */
    Optional<WeatherCache> findByCityAndUpdatedAtAfter(String city, Instant after);
}
