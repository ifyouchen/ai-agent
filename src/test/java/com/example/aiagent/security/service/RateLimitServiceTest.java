package com.example.aiagent.security.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * RateLimitService 单元测试（Mock Redis）
 *
 * 覆盖：每分钟超限、每日超限、正常请求放行
 */
@DisplayName("RateLimitService - 限流逻辑")
@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        rateLimitService = new RateLimitService(redis);
        ReflectionTestUtils.setField(rateLimitService, "maxPerMinute", 10);
        ReflectionTestUtils.setField(rateLimitService, "maxPerDay", 500);
    }

    @Test
    @DisplayName("首次请求应放行")
    void shouldAllowFirstRequest() {
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);

        RateLimitService.RateLimitResult result = rateLimitService.tryAcquire("user-001");

        assertThat(result.allowed()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    @DisplayName("每分钟请求数未超限应放行")
    void shouldAllowWithinMinuteLimit() {
        // 分钟计数=5，日计数=50，均未超限
        when(valueOps.increment(anyString()))
                .thenReturn(5L)   // minuteCount
                .thenReturn(50L); // dailyCount
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);

        RateLimitService.RateLimitResult result = rateLimitService.tryAcquire("user-002");
        assertThat(result.allowed()).isTrue();
    }

    @Test
    @DisplayName("每分钟请求数超限应拒绝")
    void shouldDenyWhenExceedingMinuteLimit() {
        // 分钟计数超过 maxPerMinute(10)
        when(valueOps.increment(anyString())).thenReturn(11L);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);

        RateLimitService.RateLimitResult result = rateLimitService.tryAcquire("user-003");

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("每分钟");
        assertThat(result.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("每日请求数超限应拒绝")
    void shouldDenyWhenExceedingDailyLimit() {
        // 分钟计数正常，日计数超过 maxPerDay(500)
        when(valueOps.increment(anyString()))
                .thenReturn(5L)    // minuteCount（未超限）
                .thenReturn(501L); // dailyCount（超限）
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);

        RateLimitService.RateLimitResult result = rateLimitService.tryAcquire("user-004");

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("今日");
        assertThat(result.retryAfterSeconds()).isEqualTo(86400);
    }

    @Test
    @DisplayName("恰好达到每分钟上限（等于 maxPerMinute）应放行")
    void shouldAllowAtExactMinuteLimit() {
        when(valueOps.increment(anyString()))
                .thenReturn(10L)   // 恰好等于 maxPerMinute
                .thenReturn(100L); // dailyCount 正常
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);

        RateLimitService.RateLimitResult result = rateLimitService.tryAcquire("user-005");
        assertThat(result.allowed()).isTrue();
    }

    @Test
    @DisplayName("RateLimitResult.allow() 工厂方法应返回 allowed=true")
    void shouldCreateAllowResult() {
        RateLimitService.RateLimitResult result = RateLimitService.RateLimitResult.allow();
        assertThat(result.allowed()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(result.retryAfterSeconds()).isZero();
    }

    @Test
    @DisplayName("RateLimitResult.deny() 工厂方法应返回 allowed=false")
    void shouldCreateDenyResult() {
        RateLimitService.RateLimitResult result =
                RateLimitService.RateLimitResult.deny("超出限制", 60);
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).isEqualTo("超出限制");
        assertThat(result.retryAfterSeconds()).isEqualTo(60);
    }
}

