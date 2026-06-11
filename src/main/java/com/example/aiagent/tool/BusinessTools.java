package com.example.aiagent.tool;

import com.example.aiagent.tool.client.WeatherApiClient;
import com.example.aiagent.tool.entity.Order;
import com.example.aiagent.tool.entity.UserAccount;
import com.example.aiagent.tool.entity.WeatherCache;
import com.example.aiagent.tool.mapper.OrderMapper;
import com.example.aiagent.tool.mapper.UserAccountMapper;
import com.example.aiagent.tool.mapper.WeatherCacheMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 业务工具集合 —— 真实数据库 / API 实现版本
 * <p>
 * 工具列表：
 * <ul>
 *   <li>{@link #queryOrderStatus} - 查询订单状态（读 biz_order 表）</li>
 *   <li>{@link #getWeather}       - 查询天气（30 分钟缓存 + OpenWeatherMap API）</li>
 *   <li>{@link #queryUserAccount} - 查询用户账户（读 biz_user_account 表）</li>
 *   <li>{@link #calculate}        - 简单数学计算（纯逻辑，无 DB）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessTools {

    /** 天气缓存有效期（分钟） */
    private static final long WEATHER_CACHE_MINUTES = 30;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private final OrderMapper orderMapper;
    private final WeatherCacheMapper weatherCacheMapper;
    private final UserAccountMapper userAccountMapper;
    private final WeatherApiClient weatherApiClient;

    // ----------------------------------------------------------------
    // 1. 订单状态查询
    // ----------------------------------------------------------------

    /**
     * 查询订单状态
     */
    @Tool("查询指定订单的当前状态和物流信息")
    public String queryOrderStatus(@P("订单编号，格式如 #12345") String orderId) {
        log.info("查询订单: {}", orderId);
        try {
            // 同时支持 "#12345" 和 "12345" 两种格式
            Optional<Order> orderOpt = orderMapper.findByOrderNo(orderId);
            if (orderOpt.isEmpty() && !orderId.startsWith("#")) {
                orderOpt = orderMapper.findByOrderNo("#" + orderId);
            }

            if (orderOpt.isEmpty()) {
                return String.format("未找到订单 #%s，请确认订单号是否正确。", orderId.replaceFirst("^#", ""));
            }

            Order order = orderOpt.get();
            return formatOrderInfo(order);

        } catch (Exception e) {
            log.error("查询订单异常，orderId={}", orderId, e);
            return String.format("查询订单 %s 时发生错误，请稍后重试。", orderId);
        }
    }

    private String formatOrderInfo(Order order) {
        String statusDesc = switch (order.getStatus()) {
            case "PENDING"   -> "待付款";
            case "PAID"      -> "已付款，等待发货";
            case "SHIPPED"   -> "已发货";
            case "DELIVERED" -> "已签收";
            case "CANCELLED" -> "已取消";
            case "REFUNDED"  -> "已退款";
            default          -> order.getStatus();
        };

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("订单 %s 信息：\n", order.getOrderNo()));
        sb.append(String.format("  商品：%s\n", order.getProductName()));
        sb.append(String.format("  金额：¥%.2f\n", order.getAmount()));
        sb.append(String.format("  状态：%s\n", statusDesc));

        if (order.getShippingNo() != null) {
            sb.append(String.format("  快递公司：%s\n",
                    order.getShippingCompany() != null ? order.getShippingCompany() : "未知"));
            sb.append(String.format("  快递单号：%s\n", order.getShippingNo()));
        }
        if (order.getExpectedArrival() != null) {
            sb.append(String.format("  预计到达：%s\n", order.getExpectedArrival()));
        }
        return sb.toString().stripTrailing();
    }

    // ----------------------------------------------------------------
    // 2. 天气查询（缓存 + API 降级）
    // ----------------------------------------------------------------

    /**
     * 查询天气
     */
    @Tool("查询指定城市的实时天气信息")
    public String getWeather(@P("城市名称，如：北京、上海、深圳") String city) {
        log.info("查询天气: {}", city);
        try {
            // 1. 先查 30 分钟内的有效缓存
            Instant cacheThreshold = Instant.now().minus(WEATHER_CACHE_MINUTES, ChronoUnit.MINUTES);
            Optional<WeatherCache> cached =
                    weatherCacheMapper.findByCityAndUpdatedAtAfter(city, cacheThreshold);

            if (cached.isPresent()) {
                log.debug("命中天气缓存，city={}", city);
                return formatWeather(city, cached.get());
            }

            // 2. 调用外部 API
            WeatherApiClient.WeatherResult result = weatherApiClient.fetchWeather(city);
            if (result != null) {
                // 更新或新建缓存
                WeatherCache cache = weatherCacheMapper.findByCity(city)
                        .orElseGet(() -> WeatherCache.builder().city(city).build());
                cache.setWeatherDesc(result.getDescription());
                cache.setTemperature(BigDecimal.valueOf(result.getTemperature()));
                cache.setHumidity(result.getHumidity());
                cache.setWind(BigDecimal.valueOf(result.getWindSpeed()));
                cache.setUpdatedAt(Instant.now());
                weatherCacheMapper.insertOrUpdate(cache);

                log.info("天气 API 成功，city={}，desc={}", city, result.getDescription());
                return String.format("%s：%s，气温 %.1f°C，湿度 %d%%，风速 %.1f m/s",
                        city, result.getDescription(),
                        result.getTemperature(), result.getHumidity(), result.getWindSpeed());
            }

            // 3. API 失败：尝试返回过期缓存（比完全没有数据好）
            Optional<WeatherCache> staleCache = weatherCacheMapper.findByCity(city);
            if (staleCache.isPresent()) {
                log.warn("API 不可用，返回过期缓存，city={}", city);
                WeatherCache w = staleCache.get();
                String cacheTime = DATE_FMT.format(w.getUpdatedAt());
                return formatWeather(city, w) + String.format("（数据来自缓存 %s，可能不是最新）", cacheTime);
            }

            // 4. 完全降级
            log.warn("天气数据不可用，city={}（无 API key 且无缓存）", city);
            return String.format("%s 天气数据暂时不可用，请稍后重试。", city);

        } catch (Exception e) {
            log.error("查询天气异常，city={}", city, e);
            return String.format("查询 %s 天气时发生错误，请稍后重试。", city);
        }
    }

    private String formatWeather(String city, WeatherCache w) {
        return String.format("%s：%s，气温 %.1f°C，湿度 %d%%，风速 %.1f m/s",
                city,
                w.getWeatherDesc() != null ? w.getWeatherDesc() : "未知",
                w.getTemperature() != null ? w.getTemperature().doubleValue() : 0.0,
                w.getHumidity() != null ? w.getHumidity() : 0,
                w.getWind() != null ? w.getWind().doubleValue() : 0.0);
    }

    // ----------------------------------------------------------------
    // 3. 用户账户查询
    // ----------------------------------------------------------------

    /**
     * 查询用户账户信息
     */
    @Tool("查询用户的账户余额和会员等级信息")
    public String queryUserAccount(@P("用户ID") String userId) {
        log.info("查询用户账户: {}", userId);
        try {
            Optional<UserAccount> accountOpt = userAccountMapper.findByUserId(userId);
            if (accountOpt.isEmpty()) {
                return String.format("未找到用户 %s 的账户信息，请确认用户 ID 是否正确。", userId);
            }

            UserAccount account = accountOpt.get();
            String membershipDesc = switch (account.getMembershipLevel()) {
                case "NORMAL"   -> "普通会员";
                case "SILVER"   -> "白银会员";
                case "GOLD"     -> "黄金会员";
                case "PLATINUM" -> "铂金会员";
                case "DIAMOND"  -> "钻石会员";
                default         -> account.getMembershipLevel();
            };

            return String.format("用户 %s（%s）：账户余额 ¥%.2f，会员等级：%s，积分：%,d",
                    account.getUserId(),
                    account.getUsername(),
                    account.getBalance(),
                    membershipDesc,
                    account.getPoints());

        } catch (Exception e) {
            log.error("查询用户账户异常，userId={}", userId, e);
            return String.format("查询用户 %s 账户时发生错误，请稍后重试。", userId);
        }
    }

    // ----------------------------------------------------------------
    // 4. 数学计算工具（无 DB，保持原有逻辑）
    // ----------------------------------------------------------------

    /**
     * 执行数学计算
     */
    @Tool("执行数学计算，支持加减乘除和基本数学运算")
    public String calculate(@P("数学表达式，如：1+2*3") String expression) {
        log.info("执行计算: {}", expression);
        try {
            // 生产环境建议引入 Aviator / Mvel 等安全的表达式引擎
            return "计算结果：" + expression + " = (请接入真实计算引擎)";
        } catch (Exception e) {
            return "计算失败：" + e.getMessage();
        }
    }
}
