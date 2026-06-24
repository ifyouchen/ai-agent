package com.example.aiagent.memory;

import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 用户级长期记忆服务
 *
 * <p>从对话中提取持久事实/偏好（职业、技术栈、语言偏好等），跨会话共享，
 * 注入 system prompt 实现个性化。与"会话短期记忆"互补：
 * <ul>
 *   <li>会话短期记忆：按 session 隔离的原始对话，话题边界清晰</li>
 *   <li>用户长期记忆：按 user 聚合的提取事实，量小、不携带话题上下文</li>
 * </ul>
 *
 * <p>提取节流：每 {@code extract-interval} 轮对话提取一次（Redis 计数器），
 * 避免每轮都调用 LLM。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserMemoryService {

    private final UserMemoryMapper userMemoryMapper;
    private final ChatLanguageModel chatLanguageModel;
    private final StringRedisTemplate redisTemplate;

    @Value("${agent.user-memory.enabled:true}")
    private boolean enabled;

    @Value("${agent.user-memory.extract-interval:5}")
    private int extractInterval;

    private static final String COUNTER_PREFIX = "user:memory:counter:";
    private static final long COUNTER_TTL_HOURS = 168L;
    private static final int MAX_FACTS_INPUT_CHARS = 1000;

    /**
     * 获取用户长期记忆，格式化为可注入 system prompt 的片段。
     * 无记忆时返回空字符串。
     */
    public String getMemoryText(String userId) {
        if (!enabled || userId == null || userId.isBlank()) return "";
        try {
            UserMemory um = userMemoryMapper.findByUserId(userId);
            if (um == null || um.getFactsText() == null || um.getFactsText().isBlank()) {
                return "";
            }
            return "\n\n## 用户长期记忆\n" + um.getFactsText();
        } catch (Exception e) {
            log.warn("读取用户长期记忆失败 userId={}: {}", userId, e.getMessage());
            return "";
        }
    }

    /**
     * 异步提取用户长期记忆（每 extractInterval 轮触发一次）。
     *
     * @param userId  用户 ID
     * @param userMsg 本轮用户消息
     * @param aiMsg   本轮 AI 回复
     */
    @Async
    public void extractAsync(String userId, String userMsg, String aiMsg) {
        if (!enabled || userId == null || userId.isBlank()) return;
        if (userMsg == null || userMsg.isBlank()) return;
        try {
            // 节流：计数器达到 extractInterval 的整数倍才提取
            String counterKey = COUNTER_PREFIX + userId;
            Long count = redisTemplate.opsForValue().increment(counterKey);
            redisTemplate.expire(counterKey, Duration.ofHours(COUNTER_TTL_HOURS));
            if (count == null || count % extractInterval != 0) {
                return;
            }
            // 防止计数器无限增长
            if (count > 100_000) {
                redisTemplate.opsForValue().set(counterKey, "0", Duration.ofHours(COUNTER_TTL_HOURS));
            }

            UserMemory existing = userMemoryMapper.findByUserId(userId);
            String existingFacts = existing != null && existing.getFactsText() != null
                    ? existing.getFactsText() : "";
            String newFacts = extractFacts(existingFacts, userMsg, aiMsg);
            if (newFacts != null && !newFacts.isBlank()) {
                userMemoryMapper.upsert(userId, newFacts);
                log.info("用户长期记忆更新 userId={} len={}", userId, newFacts.length());
            }
        } catch (Exception e) {
            log.warn("用户长期记忆提取失败 userId={}: {}", userId, e.getMessage());
        }
    }

    /**
     * 调用 LLM 从对话中提取/更新用户持久事实。
     * 提取失败时返回已有事实，保证不丢失。
     */
    private String extractFacts(String existingFacts, String userMsg, String aiMsg) {
        StringBuilder sb = new StringBuilder();
        sb.append("从以下对话中提取关于用户的持久事实和偏好（如职业、技术栈、语言偏好、常用组织/知识库等）。\n");
        sb.append("只提取明确且稳定的信息，忽略一次性的具体问题。如果已有事实已涵盖，保持不变。\n\n");
        if (existingFacts != null && !existingFacts.isBlank()) {
            sb.append("已有事实：\n").append(existingFacts).append("\n\n");
        }
        sb.append("新对话：\n")
                .append("用户：").append(truncate(userMsg)).append("\n")
                .append("助手：").append(truncate(aiMsg)).append("\n\n");
        sb.append("请输出更新后的完整事实列表（每条一行，不超过 10 条）。如果没有新事实，原样输出已有事实。");
        try {
            // 标记本次 LLM 调用为「记忆抽取」，供 AOP 切面区分 scenario
            String prevScenario = MDC.get("scenario");
            MDC.put("scenario", "memory_extraction");
            try {
                return chatLanguageModel.generate(sb.toString());
            } finally {
                if (prevScenario == null) MDC.remove("scenario");
                else MDC.put("scenario", prevScenario);
            }
        } catch (Exception e) {
            log.warn("用户记忆提取 LLM 调用失败: {}", e.getMessage());
            return existingFacts;
        }
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() <= MAX_FACTS_INPUT_CHARS ? s : s.substring(0, MAX_FACTS_INPUT_CHARS) + "…";
    }
}

