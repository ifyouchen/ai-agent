package com.example.aiagent.security.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 用户级别限流服务（令牌桶，基于 Redis）
 *
 * 两个维度限流：
 * 1. 每分钟最大请求数（防突发攻击）
 * 2. 每日最大请求数（防持续消耗，控制成本）
 *
 * Redis Key 设计：
 *   rate:minute:{userId}:{yyyyMMddHHmm}  → TTL 2分钟
 *   rate:daily:{userId}:{yyyyMMdd}       → TTL 2天
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final StringRedisTemplate redis;

    @Value("${security.rate-limit.per-minute:10}")
    private int maxPerMinute;

    @Value("${security.rate-limit.per-day:500}")
    private int maxPerDay;

    public record RateLimitResult(boolean allowed, String reason, long retryAfterSeconds) {
        public static RateLimitResult allow() {
            return new RateLimitResult(true, null, 0);
        }
        public static RateLimitResult deny(String reason, long retryAfter) {
            return new RateLimitResult(false, reason, retryAfter);
        }
    }

    /**
     * 检查并消费一次请求配额
     */
    public RateLimitResult tryAcquire(String userId) {
        String now = java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        String minuteKey = "rate:minute:" + userId + ":" + now;
        String dailyKey  = "rate:daily:"  + userId + ":" + today;

        // 每分钟计数（原子递增）
        Long minuteCount = redis.opsForValue().increment(minuteKey);
        if (minuteCount == 1) {
            redis.expire(minuteKey, Duration.ofMinutes(2));
        }
        if (minuteCount > maxPerMinute) {
            log.warn("[RATE_LIMIT] 用户 {} 每分钟超限 {}/{}", userId, minuteCount, maxPerMinute);
            return RateLimitResult.deny(
                    String.format("请求过于频繁，每分钟最多 %d 次", maxPerMinute), 60);
        }

        // 每日计数
        Long dailyCount = redis.opsForValue().increment(dailyKey);
        if (dailyCount == 1) {
            redis.expire(dailyKey, Duration.ofDays(2));
        }
        if (dailyCount > maxPerDay) {
            log.warn("[RATE_LIMIT] 用户 {} 今日超限 {}/{}", userId, dailyCount, maxPerDay);
            return RateLimitResult.deny(
                    String.format("今日请求次数已达上限 %d 次，明日恢复", maxPerDay), 86400);
        }

        return RateLimitResult.allow();
    }

    /**
     * 查询用户今日已使用次数
     */
    public long getDailyUsage(String userId) {
        String today   = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String key     = "rate:daily:" + userId + ":" + today;
        String val     = redis.opsForValue().get(key);
        return val != null ? Long.parseLong(val) : 0;
    }
}
