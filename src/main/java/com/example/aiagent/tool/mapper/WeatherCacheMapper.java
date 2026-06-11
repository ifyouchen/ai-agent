package com.example.aiagent.tool.mapper;

import com.example.aiagent.tool.entity.WeatherCache;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.Optional;

@Mapper
public interface WeatherCacheMapper {

    Optional<WeatherCache> findByCity(@Param("city") String city);

    Optional<WeatherCache> findByCityAndUpdatedAtAfter(@Param("city") String city, @Param("after") Instant after);

    void insertOrUpdate(WeatherCache cache);
}
