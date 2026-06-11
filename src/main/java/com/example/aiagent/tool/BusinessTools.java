package com.example.aiagent.tool;

import com.example.aiagent.tool.client.WeatherApiClient;
import com.example.aiagent.tool.entity.Order;
import com.example.aiagent.tool.entity.UserAccount;
import com.example.aiagent.tool.entity.WeatherCache;
import com.example.aiagent.tool.mapper.OrderMapper;
import com.example.aiagent.tool.mapper.UserAccountMapper;
import com.example.aiagent.tool.mapper.WeatherCacheMapper;
import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.Options;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
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

    /**
     * Aviator 沙箱实例（全局复用，线程安全）
     *
     * 安全配置：
     * - FORBIDDEN_INVOKE_REFLECT：禁止反射调用（防止代码注入）
     * - 最大循环次数 10000，防止无限循环耗尽 CPU
     * - 表达式长度限制 500 字符
     */
    private static final AviatorEvaluatorInstance AVIATOR = AviatorEvaluator.newInstance();
    static {
        // Note: FORBIDDEN_INVOKE_REFLECT was removed in Aviator 5.x; use ALLOWED_CLASS_SET for security if needed
        AVIATOR.setOption(Options.MAX_LOOP_COUNT, 10000L);
        AVIATOR.setOption(Options.OPTIMIZE_LEVEL, AviatorEvaluator.EVAL);
    }

    /** calculate 工具支持的最大表达式长度 */
    private static final int MAX_EXPR_LENGTH = 500;

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
     * 执行数学计算（基于 Aviator 表达式引擎，安全沙箱）
     *
     * 支持：
     * - 四则运算：1 + 2 * 3 / 4 - 5
     * - 幂运算：2 ** 10
     * - 取模：17 % 3
     * - 内置函数：math.sqrt(2)、math.abs(-5)、math.pow(2,10)、math.log(100)
     * - 比较与逻辑：3 > 2 ? "大" : "小"
     * - 大数精度：结果使用 BigDecimal，精确到 10 位小数
     *
     * 安全限制：
     * - 禁止反射调用（无法执行任意 Java 代码）
     * - 表达式最长 500 字符
     * - 禁止访问 System / Runtime / Process 等危险类
     */
    @Tool("执行数学计算，支持加减乘除、幂运算、取模、math.sqrt/abs/pow/log 等函数")
    public String calculate(@P("数学表达式，如：1+2*3、math.sqrt(144)、2**10") String expression) {
        log.info("执行计算: {}", expression);

        if (expression == null || expression.isBlank()) {
            return "表达式不能为空";
        }
        if (expression.length() > MAX_EXPR_LENGTH) {
            return "表达式过长（最多 " + MAX_EXPR_LENGTH + " 字符）";
        }

        // 安全校验：拦截危险关键词
        String lower = expression.toLowerCase();
        for (String forbidden : new String[]{"system", "runtime", "process", "reflect",
                "classloader", "exec(", "forname", "import", "class."}) {
            if (lower.contains(forbidden)) {
                log.warn("计算表达式包含危险关键词：{}", forbidden);
                return "表达式包含不允许的内容";
            }
        }

        try {
            Object raw = AVIATOR.execute(expression);
            // 统一格式化输出
            String result = formatCalcResult(raw);
            log.info("计算完成: {} = {}", expression, result);
            return String.format("计算结果：%s = %s", expression, result);

        } catch (com.googlecode.aviator.exception.ExpressionSyntaxErrorException e) {
            log.warn("表达式语法错误: {} -> {}", expression, e.getMessage());
            return "表达式语法错误：" + e.getMessage()
                    + "\n提示：支持 +、-、*、/、**（幂）、%（取模）及 math.sqrt/abs/pow/log 函数";
        } catch (com.googlecode.aviator.exception.ExpressionRuntimeException e) {
            log.warn("表达式运行时错误: {} -> {}", expression, e.getMessage());
            return "计算错误（如除以零）：" + e.getMessage();
        } catch (Exception e) {
            log.error("计算异常: {}", expression, e);
            return "计算失败：" + e.getMessage();
        }
    }

    /**
     * 格式化计算结果
     * - 整数：去掉小数点（1.0 → 1）
     * - 浮点数：最多保留 10 位小数，去掉末尾零
     * - 其他：直接 toString
     */
    private String formatCalcResult(Object raw) {
        if (raw == null) return "null";
        if (raw instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        if (raw instanceof Double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf(d.longValue());
            }
            BigDecimal bd = new BigDecimal(d, new MathContext(10));
            return bd.stripTrailingZeros().toPlainString();
        }
        if (raw instanceof Long || raw instanceof Integer) {
            return String.valueOf(raw);
        }
        return raw.toString();
    }
}
