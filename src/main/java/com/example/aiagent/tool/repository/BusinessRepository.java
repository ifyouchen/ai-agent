package com.example.aiagent.tool.repository;

import com.example.aiagent.tool.entity.Order;
import com.example.aiagent.tool.entity.UserAccount;
import com.example.aiagent.tool.entity.WeatherCache;
import com.example.aiagent.tool.mapper.OrderMapper;
import com.example.aiagent.tool.mapper.UserAccountMapper;
import com.example.aiagent.tool.mapper.WeatherCacheMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 业务工具查询门面（Repository Facade）
 *
 * 封装订单、用户账户、天气缓存三个业务实体的查询逻辑，
 * 供 BusinessTools（Agent 工具层）调用，屏蔽底层 Mapper 细节。
 *
 * 额外封装的复合逻辑：
 * - 天气缓存有效期检查（避免 BusinessTools 直接操作 Instant 计算）
 * - 订单 ID 格式归一化（同时支持 "12345" 和 "#12345"）
 * - 账户余额/积分更新（transactional 写操作入口）
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class BusinessRepository {

    private final OrderMapper orderMapper;
    private final UserAccountMapper userAccountMapper;
    private final WeatherCacheMapper weatherCacheMapper;

    /** 天气缓存有效期（分钟） */
    private static final long WEATHER_CACHE_TTL_MINUTES = 30;

    // ─── 订单 ──────────────────────────────────────────────────

    /**
     * 按订单号查询（自动兼容带 # 和不带 # 两种格式）
     *
     * @param orderNo 订单号，如 "12345" 或 "#12345"
     * @return Optional<Order>
     */
    public Optional<Order> findOrderByNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) return Optional.empty();

        // 先用原始格式查
        Optional<Order> result = orderMapper.findByOrderNo(orderNo);
        if (result.isPresent()) return result;

        // 再用带 # 格式查（兼容两种格式）
        if (!orderNo.startsWith("#")) {
            result = orderMapper.findByOrderNo("#" + orderNo);
        } else {
            // 原始带 # 但没找到，再用去掉 # 的格式查
            result = orderMapper.findByOrderNo(orderNo.substring(1));
        }
        return result;
    }

    /**
     * 创建订单
     */
    public void saveOrder(Order order) {
        orderMapper.insert(order);
        log.info("[BUSI-REPO] 订单已保存，orderNo={}", order.getOrderNo());
    }

    // ─── 用户账户 ──────────────────────────────────────────────

    /**
     * 按用户 ID 查询账户
     */
    public Optional<UserAccount> findAccount(String userId) {
        return userAccountMapper.findByUserId(userId);
    }

    /**
     * 创建用户账户
     */
    public void saveAccount(UserAccount account) {
        userAccountMapper.insert(account);
        log.info("[BUSI-REPO] 账户已保存，userId={}", account.getUserId());
    }

    // ─── 天气缓存 ──────────────────────────────────────────────

    /**
     * 查询有效天气缓存（在 TTL 内的缓存）
     *
     * @param city 城市名称
     * @return Optional<WeatherCache>，若缓存过期则返回 empty
     */
    public Optional<WeatherCache> findValidWeatherCache(String city) {
        Instant threshold = Instant.now().minus(WEATHER_CACHE_TTL_MINUTES, ChronoUnit.MINUTES);
        return weatherCacheMapper.findByCityAndUpdatedAtAfter(city, threshold);
    }

    /**
     * 查询天气缓存（不管是否过期，用于 API 不可用时的降级兜底）
     *
     * @param city 城市名称
     * @return Optional<WeatherCache>，可能是过期数据
     */
    public Optional<WeatherCache> findAnyWeatherCache(String city) {
        return weatherCacheMapper.findByCity(city);
    }

    /**
     * 更新或新建天气缓存
     *
     * @param city        城市名称
     * @param description 天气描述，如"晴天"
     * @param temperature 气温（°C）
     * @param humidity    湿度（%）
     * @param windSpeed   风速（m/s）
     */
    public void upsertWeatherCache(String city, String description,
                                   double temperature, int humidity, double windSpeed) {
        WeatherCache cache = weatherCacheMapper.findByCity(city)
                .orElseGet(() -> WeatherCache.builder().city(city).build());
        cache.setWeatherDesc(description);
        cache.setTemperature(BigDecimal.valueOf(temperature));
        cache.setHumidity(humidity);
        cache.setWind(BigDecimal.valueOf(windSpeed));
        cache.setUpdatedAt(Instant.now());
        weatherCacheMapper.insertOrUpdate(cache);
        log.debug("[BUSI-REPO] 天气缓存已更新，city={}", city);
    }
}

