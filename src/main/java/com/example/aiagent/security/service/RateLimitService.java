package com.example.aiagent.security.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 用户级别限流服务（基于 Redis）
 *
 * <p>两个维度限流：
 * <ol>
 *   <li>每分钟最大请求数（防突发攻击）</li>
 *   <li>每日最大请求数（防持续消耗，控制 LLM 成本）</li>
 * </ol>
 *
 * <p>Redis Key 设计：
 * <pre>
 *   rate:minute:{userId}:{yyyyMMddHHmm}  → TTL 2分钟
 *   rate:daily:{userId}:{yyyyMMdd}       → TTL 2天
 * </pre>
 *
 * <p>修复说明（原实现的问题）：
 * 原实现的 INCR + EXPIRE 是两条独立命令，非原子操作。
 * 极端情况下（INCR 成功但 EXPIRE 未执行，如进程崩溃），key 会永不过期导致用户被永久封锁。
 *
 * <p>修复方案：使用 Lua 脚本将 INCR 和 EXPIRE 合并为一个原子操作。
 * Redis 保证 Lua 脚本的原子执行（单线程，脚本执行期间不会有其他命令插入）。
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

    /**
     * Lua 脚本：原子执行 INCR + 条件 EXPIRE
     *
     * <pre>
     * 逻辑：
     *   1. INCR key（计数 +1）
     *   2. 如果是第一次（count == 1），则设置 TTL
     *   3. 返回当前计数
     * 参数：KEYS[1] = key, ARGV[1] = ttl(秒)
     * </pre>
     */
    private static final DefaultRedisScript<Long> INCR_WITH_EXPIRE_SCRIPT;

    static {
        INCR_WITH_EXPIRE_SCRIPT = new DefaultRedisScript<>();
        INCR_WITH_EXPIRE_SCRIPT.setScriptText(
                "local count = redis.call('INCR', KEYS[1])\n" +
                "if count == 1 then\n" +
                "    redis.call('EXPIRE', KEYS[1], ARGV[1])\n" +
                "end\n" +
                "return count"
        );
        INCR_WITH_EXPIRE_SCRIPT.setResultType(Long.class);
    }

    public record RateLimitResult(boolean allowed, String reason, long retryAfterSeconds) {
        public static RateLimitResult allow() {
            return new RateLimitResult(true, null, 0);
        }
        public static RateLimitResult deny(String reason, long retryAfter) {
            return new RateLimitResult(false, reason, retryAfter);
        }
    }

    /**
     * 检查并消费一次请求配额（原子操作，线程安全）
     *
     * @param userId 当前登录用户 ID
     * @return 限流结果（允许/拒绝+原因+重试等待秒数）
     */
    public RateLimitResult tryAcquire(String userId) {
        String now   = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        String minuteKey = "rate:minute:" + userId + ":" + now;
        String dailyKey  = "rate:daily:"  + userId + ":" + today;

        // ── 每分钟计数（Lua 原子操作）────────────────────────
        Long minuteCount = redis.execute(
                INCR_WITH_EXPIRE_SCRIPT,
                List.of(minuteKey),
                String.valueOf(120));  // TTL 2分钟，确保窗口结束后自动清理

        if (minuteCount == null) minuteCount = 0L;
        if (minuteCount > maxPerMinute) {
            log.warn("[RATE_LIMIT] 用户 {} 每分钟超限 {}/{}", userId, minuteCount, maxPerMinute);
            return RateLimitResult.deny(
                    String.format("请求过于频繁，每分钟最多 %d 次，请稍后重试", maxPerMinute), 60);
        }

        // ── 每日计数（Lua 原子操作）──────────────────────────
        Long dailyCount = redis.execute(
                INCR_WITH_EXPIRE_SCRIPT,
                List.of(dailyKey),
                String.valueOf(172800));  // TTL 2天

        if (dailyCount == null) dailyCount = 0L;
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
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String key   = "rate:daily:" + userId + ":" + today;
        String val   = redis.opsForValue().get(key);
        return val != null ? Long.parseLong(val) : 0;
    }
}
