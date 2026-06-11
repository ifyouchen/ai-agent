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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 企业级业务工具集合（Agent 可调用的工具）
 *
 * <p>工具列表：
 * <ul>
 *   <li>{@link #queryOrderStatus}     - 按订单号查询单个订单状态（含物流）</li>
 *   <li>{@link #queryUserOrders}      - 查询用户最近订单列表（最多10条）</li>
 *   <li>{@link #queryOrderSummary}    - 查询用户订单统计（各状态数量）</li>
 *   <li>{@link #getWeather}           - 查询实时天气（30分钟缓存 + OpenWeatherMap API）</li>
 *   <li>{@link #queryUserAccount}     - 查询用户账户余额和会员信息</li>
 *   <li>{@link #queryUserPoints}      - 查询用户积分详情（含等级权益说明）</li>
 *   <li>{@link #calculate}            - 安全数学计算（Aviator 沙箱引擎）</li>
 *   <li>{@link #getCurrentDateTime}   - 获取当前日期时间（无需外部依赖）</li>
 * </ul>
 *
 * <p>安全设计：
 * <ul>
 *   <li>所有工具方法均 catch 全部异常，返回友好的错误提示，不向 LLM 暴露异常堆栈</li>
 *   <li>calculate 工具使用 Aviator 沙箱，禁止反射/代码注入</li>
 *   <li>天气工具包含三级降级策略：API 调用 → 过期缓存 → 友好提示</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessTools {

    /** 天气缓存有效期（分钟） */
    private static final long WEATHER_CACHE_MINUTES = 30;

    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    /**
     * Aviator 安全沙箱（全局复用，线程安全）
     *
     * 安全配置：
     * - 最大循环次数 10000，防止无限循环耗尽 CPU
     * - OPTIMIZE_LEVEL=EVAL：不优化以确保安全
     */
    private static final AviatorEvaluatorInstance AVIATOR = AviatorEvaluator.newInstance();
    static {
        AVIATOR.setOption(Options.MAX_LOOP_COUNT, 10000L);
        AVIATOR.setOption(Options.OPTIMIZE_LEVEL, AviatorEvaluator.EVAL);
    }

    private static final int MAX_EXPR_LENGTH = 500;

    private final OrderMapper orderMapper;
    private final WeatherCacheMapper weatherCacheMapper;
    private final UserAccountMapper userAccountMapper;
    private final WeatherApiClient weatherApiClient;

    // ── 1. 订单状态查询 ──────────────────────────────────────────

    /**
     * 查询单个订单状态和物流信息
     */
    @Tool("查询指定订单的当前状态、物流信息和预计到达时间")
    public String queryOrderStatus(@P("订单编号，格式如 #12345 或直接输入 12345") String orderId) {
        log.info("[Tool] 查询订单状态: {}", orderId);
        try {
            Optional<Order> orderOpt = findOrder(orderId);
            if (orderOpt.isEmpty()) {
                return String.format("未找到订单 #%s，请确认订单号是否正确。如需查询订单列表，可提供您的用户ID。",
                        orderId.replaceFirst("^#", ""));
            }
            return formatOrderDetail(orderOpt.get());
        } catch (Exception e) {
            log.error("[Tool] 查询订单异常，orderId={}", orderId, e);
            return String.format("查询订单 %s 时发生系统错误，请稍后重试。", orderId);
        }
    }

    /**
     * 查询用户最近的订单列表
     */
    @Tool("查询指定用户最近的订单列表，返回最多10条订单记录")
    public String queryUserOrders(@P("用户ID，如 U001") String userId) {
        log.info("[Tool] 查询用户订单列表: {}", userId);
        try {
            List<Order> orders = orderMapper.findByUserId(userId, 10);
            if (orders.isEmpty()) {
                return String.format("用户 %s 暂无订单记录。", userId);
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("用户 %s 最近 %d 条订单：\n\n", userId, orders.size()));
            for (int i = 0; i < orders.size(); i++) {
                Order o = orders.get(i);
                String statusDesc = getStatusDesc(o.getStatus());
                sb.append(String.format("%d. 订单 %s\n", i + 1, o.getOrderNo()));
                sb.append(String.format("   商品：%s  金额：¥%.2f  状态：%s\n",
                        o.getProductName(), o.getAmount(), statusDesc));
                if (o.getCreatedAt() != null) {
                    sb.append(String.format("   下单时间：%s\n", DATETIME_FMT.format(o.getCreatedAt())));
                }
                sb.append("\n");
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.error("[Tool] 查询用户订单列表异常，userId={}", userId, e);
            return String.format("查询用户 %s 订单列表时发生错误，请稍后重试。", userId);
        }
    }

    /**
     * 查询用户订单统计汇总
     */
    @Tool("统计用户各状态的订单数量，包括待付款、已付款、已发货、已签收、已取消等")
    public String queryOrderSummary(@P("用户ID，如 U001") String userId) {
        log.info("[Tool] 查询用户订单统计: {}", userId);
        try {
            List<Map<String, Object>> groups = orderMapper.countGroupByStatus(userId);
            if (groups.isEmpty()) {
                return String.format("用户 %s 暂无订单记录。", userId);
            }

            int total = groups.stream()
                    .mapToInt(g -> ((Number) g.get("cnt")).intValue())
                    .sum();

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("用户 %s 的订单统计（共 %d 笔）：\n", userId, total));
            for (Map<String, Object> g : groups) {
                String status = (String) g.get("status");
                int cnt = ((Number) g.get("cnt")).intValue();
                sb.append(String.format("  %s：%d 笔\n", getStatusDesc(status), cnt));
            }

            // 计算待处理订单（PENDING + PAID）
            int pending = groups.stream()
                    .filter(g -> "PENDING".equals(g.get("status")) || "PAID".equals(g.get("status")))
                    .mapToInt(g -> ((Number) g.get("cnt")).intValue())
                    .sum();
            if (pending > 0) {
                sb.append(String.format("\n⚠️ 您有 %d 笔订单待处理（待付款或待发货）", pending));
            }

            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.error("[Tool] 查询订单统计异常，userId={}", userId, e);
            return String.format("查询用户 %s 订单统计时发生错误，请稍后重试。", userId);
        }
    }

    // ── 2. 天气查询（三级降级：API → 过期缓存 → 提示） ──────────

    /**
     * 查询实时天气
     */
    @Tool("查询指定城市的实时天气，包括天气状况、气温、湿度、风速")
    public String getWeather(@P("城市名称，中文或英文均可，如：北京、上海、深圳、Beijing") String city) {
        log.info("[Tool] 查询天气: {}", city);
        try {
            // [Level 1] 查30分钟内有效缓存
            Instant cacheThreshold = Instant.now().minus(WEATHER_CACHE_MINUTES, ChronoUnit.MINUTES);
            Optional<WeatherCache> cached = weatherCacheMapper.findByCityAndUpdatedAtAfter(city, cacheThreshold);
            if (cached.isPresent()) {
                log.debug("[Tool] 命中天气缓存，city={}", city);
                return formatWeather(city, cached.get(), false);
            }

            // [Level 2] 调用 OpenWeatherMap API
            WeatherApiClient.WeatherResult result = weatherApiClient.fetchWeather(city);
            if (result != null) {
                WeatherCache cache = weatherCacheMapper.findByCity(city)
                        .orElseGet(() -> WeatherCache.builder().city(city).build());
                cache.setWeatherDesc(result.getDescription());
                cache.setTemperature(BigDecimal.valueOf(result.getTemperature()));
                cache.setHumidity(result.getHumidity());
                cache.setWind(BigDecimal.valueOf(result.getWindSpeed()));
                cache.setUpdatedAt(Instant.now());
                weatherCacheMapper.insertOrUpdate(cache);

                return String.format("%s 当前天气：%s，气温 %.1f°C，湿度 %d%%，风速 %.1f m/s",
                        city, result.getDescription(),
                        result.getTemperature(), result.getHumidity(), result.getWindSpeed());
            }

            // [Level 3] API 失败 → 尝试返回过期缓存（降级兜底）
            Optional<WeatherCache> stale = weatherCacheMapper.findByCity(city);
            if (stale.isPresent()) {
                log.warn("[Tool] 天气 API 不可用，返回过期缓存，city={}", city);
                return formatWeather(city, stale.get(), true);
            }

            // [Level 4] 完全降级
            return String.format("%s 的天气数据暂时不可用（API 无响应且无缓存数据）。"
                    + "请配置 weather.api.key 或稍后重试。", city);

        } catch (Exception e) {
            log.error("[Tool] 查询天气异常，city={}", city, e);
            return String.format("查询 %s 天气时发生错误，请稍后重试。", city);
        }
    }

    // ── 3. 用户账户查询 ──────────────────────────────────────────

    /**
     * 查询用户账户余额和会员信息
     */
    @Tool("查询用户的账户余额、会员等级和基本账户信息")
    public String queryUserAccount(@P("用户ID，如 U001") String userId) {
        log.info("[Tool] 查询用户账户: {}", userId);
        try {
            Optional<UserAccount> opt = userAccountMapper.findByUserId(userId);
            if (opt.isEmpty()) {
                // 尝试按用户名查询（兼容性处理）
                opt = userAccountMapper.findByUsername(userId);
            }
            if (opt.isEmpty()) {
                return String.format("未找到用户 %s 的账户，请确认用户ID是否正确。", userId);
            }

            UserAccount account = opt.get();
            String membershipDesc = getMembershipDesc(account.getMembershipLevel());
            String membershipBenefits = getMembershipBenefits(account.getMembershipLevel());

            return String.format(
                    "用户 %s（%s）账户信息：\n"
                    + "  账户余额：¥%.2f\n"
                    + "  会员等级：%s\n"
                    + "  等级权益：%s\n"
                    + "  当前积分：%,d 分",
                    account.getUserId(),
                    account.getUsername(),
                    account.getBalance(),
                    membershipDesc,
                    membershipBenefits,
                    account.getPoints());
        } catch (Exception e) {
            log.error("[Tool] 查询用户账户异常，userId={}", userId, e);
            return String.format("查询用户 %s 账户时发生错误，请稍后重试。", userId);
        }
    }

    /**
     * 查询用户积分详情
     */
    @Tool("查询用户当前积分余额及积分等级权益说明")
    public String queryUserPoints(@P("用户ID，如 U001") String userId) {
        log.info("[Tool] 查询用户积分: {}", userId);
        try {
            Optional<UserAccount> opt = userAccountMapper.findByUserId(userId);
            if (opt.isEmpty()) {
                return String.format("未找到用户 %s 的账户。", userId);
            }

            UserAccount account = opt.get();
            int points = account.getPoints();
            String nextLevel = getNextLevelInfo(account.getMembershipLevel(), points);

            return String.format(
                    "用户 %s（%s）积分信息：\n"
                    + "  当前积分：%,d 分\n"
                    + "  当前等级：%s\n"
                    + "  %s",
                    account.getUserId(),
                    account.getUsername(),
                    points,
                    getMembershipDesc(account.getMembershipLevel()),
                    nextLevel);
        } catch (Exception e) {
            log.error("[Tool] 查询用户积分异常，userId={}", userId, e);
            return String.format("查询用户 %s 积分时发生错误，请稍后重试。", userId);
        }
    }

    // ── 4. 获取当前日期时间 ──────────────────────────────────────

    /**
     * 获取当前日期和时间（无需网络，直接返回系统时间）
     */
    @Tool("获取当前的日期和时间，用于回答'今天是几号'、'现在几点'等问题")
    public String getCurrentDateTime(@P("时区，默认 Asia/Shanghai，可传入如 Asia/Tokyo、UTC 等") String timezone) {
        try {
            ZoneId zone;
            try {
                zone = (timezone != null && !timezone.isBlank())
                        ? ZoneId.of(timezone) : ZoneId.of("Asia/Shanghai");
            } catch (Exception e) {
                zone = ZoneId.of("Asia/Shanghai");
            }

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss (EEEE)")
                    .withZone(zone)
                    .withLocale(Locale.CHINESE);

            return "当前时间：" + fmt.format(Instant.now())
                    + "（时区：" + zone.getId() + "）";
        } catch (Exception e) {
            return "当前时间：" + DateTimeFormatter
                    .ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.of("Asia/Shanghai"))
                    .format(Instant.now());
        }
    }

    // ── 5. 安全数学计算 ──────────────────────────────────────────

    /**
     * 执行数学计算（Aviator 安全沙箱）
     *
     * 支持：四则运算、幂运算（**）、取模（%）、math.sqrt/abs/pow/log
     * 安全：禁止反射/system/runtime 等危险操作
     */
    @Tool("执行数学计算，支持加减乘除、幂运算(**)、取模(%)、math.sqrt/abs/pow/log 等函数")
    public String calculate(@P("数学表达式，如：1+2*3、math.sqrt(144)、2**10、(100-20)/0.8") String expression) {
        log.info("[Tool] 执行计算: {}", expression);

        if (expression == null || expression.isBlank()) return "表达式不能为空";
        if (expression.length() > MAX_EXPR_LENGTH) {
            return "表达式过长（最多 " + MAX_EXPR_LENGTH + " 字符）";
        }

        // 安全关键词拦截
        String lower = expression.toLowerCase();
        for (String forbidden : new String[]{"system", "runtime", "process", "reflect",
                "classloader", "exec(", "forname", "import", "class."}) {
            if (lower.contains(forbidden)) {
                log.warn("[Tool] 计算表达式包含危险关键词：{}", forbidden);
                return "表达式包含不允许的内容：" + forbidden;
            }
        }

        try {
            Object raw = AVIATOR.execute(expression);
            String result = formatCalcResult(raw);
            log.info("[Tool] 计算完成: {} = {}", expression, result);
            return String.format("计算结果：%s = %s", expression, result);

        } catch (com.googlecode.aviator.exception.ExpressionSyntaxErrorException e) {
            log.warn("[Tool] 表达式语法错误: {} -> {}", expression, e.getMessage());
            return "表达式语法错误：" + e.getMessage()
                    + "\n提示：支持 +、-、*、/、**（幂）、%（取模）及 math.sqrt/abs/pow/log 函数";
        } catch (com.googlecode.aviator.exception.ExpressionRuntimeException e) {
            log.warn("[Tool] 表达式运行时错误: {} -> {}", expression, e.getMessage());
            return "计算错误（如除以零）：" + e.getMessage();
        } catch (Exception e) {
            log.error("[Tool] 计算异常: {}", expression, e);
            return "计算失败：" + e.getMessage();
        }
    }

    // ── 私有辅助方法 ──────────────────────────────────────────────

    private Optional<Order> findOrder(String orderId) {
        // 先用原始格式查
        Optional<Order> result = orderMapper.findByOrderNo(orderId);
        if (result.isPresent()) return result;
        // 兼容带 # 和不带 # 两种格式
        if (!orderId.startsWith("#")) {
            result = orderMapper.findByOrderNo("#" + orderId);
        } else {
            result = orderMapper.findByOrderNo(orderId.substring(1));
        }
        return result;
    }

    private String formatOrderDetail(Order order) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("订单 %s 详情：\n", order.getOrderNo()));
        sb.append(String.format("  商品名称：%s\n", order.getProductName()));
        sb.append(String.format("  订单金额：¥%.2f\n", order.getAmount()));
        sb.append(String.format("  订单状态：%s\n", getStatusDesc(order.getStatus())));

        if (order.getCreatedAt() != null) {
            sb.append(String.format("  下单时间：%s\n", DATETIME_FMT.format(order.getCreatedAt())));
        }

        if (order.getShippingNo() != null) {
            sb.append(String.format("  快递公司：%s\n",
                    order.getShippingCompany() != null ? order.getShippingCompany() : "待确认"));
            sb.append(String.format("  快递单号：%s\n", order.getShippingNo()));
        }

        if (order.getExpectedArrival() != null) {
            sb.append(String.format("  预计到达：%s\n", order.getExpectedArrival()));
        }

        // 状态对应的操作提示
        String tip = switch (order.getStatus()) {
            case "PENDING"   -> "💡 提示：订单待付款，请尽快完成支付。";
            case "PAID"      -> "💡 提示：订单已付款，等待商家发货。";
            case "SHIPPED"   -> "💡 提示：商品已发货，可用快递单号查询物流详情。";
            case "DELIVERED" -> "✅ 商品已签收，如有问题请联系客服。";
            case "CANCELLED" -> "❌ 订单已取消。如需重新购买，请下新订单。";
            case "REFUNDED"  -> "💰 退款已完成，金额将在1-3个工作日退回原支付账户。";
            default -> "";
        };
        if (!tip.isBlank()) sb.append("\n").append(tip);

        return sb.toString().stripTrailing();
    }

    private String formatWeather(String city, WeatherCache w, boolean isStale) {
        String result = String.format("%s：%s，气温 %.1f°C，湿度 %d%%，风速 %.1f m/s",
                city,
                w.getWeatherDesc() != null ? w.getWeatherDesc() : "未知",
                w.getTemperature() != null ? w.getTemperature().doubleValue() : 0.0,
                w.getHumidity() != null ? w.getHumidity() : 0,
                w.getWind() != null ? w.getWind().doubleValue() : 0.0);
        if (isStale && w.getUpdatedAt() != null) {
            result += String.format("（数据来自 %s 的缓存，可能不是最新）",
                    DATE_FMT.format(w.getUpdatedAt()));
        }
        return result;
    }

    private String getStatusDesc(String status) {
        return switch (status) {
            case "PENDING"   -> "待付款";
            case "PAID"      -> "已付款（待发货）";
            case "SHIPPED"   -> "已发货（运输中）";
            case "DELIVERED" -> "已签收";
            case "CANCELLED" -> "已取消";
            case "REFUNDED"  -> "已退款";
            default          -> status;
        };
    }

    private String getMembershipDesc(String level) {
        return switch (level) {
            case "NORMAL"   -> "普通会员";
            case "SILVER"   -> "白银会员";
            case "GOLD"     -> "黄金会员";
            case "PLATINUM" -> "铂金会员";
            case "DIAMOND"  -> "钻石会员";
            default         -> level;
        };
    }

    private String getMembershipBenefits(String level) {
        return switch (level) {
            case "NORMAL"   -> "基础服务";
            case "SILVER"   -> "95折优惠 + 专属客服";
            case "GOLD"     -> "9折优惠 + 免费快递 + 优先发货";
            case "PLATINUM" -> "85折优惠 + 专属客服 + 生日双倍积分 + 免费退换货";
            case "DIAMOND"  -> "8折优惠 + 7×24 专属客服 + 每月积分礼包 + 全程免费退换货";
            default         -> "标准服务";
        };
    }

    private String getNextLevelInfo(String currentLevel, int points) {
        return switch (currentLevel) {
            case "NORMAL"   -> points < 1000
                    ? String.format("距离白银会员还需 %,d 积分（再积累 %,d 分）",
                        1000, 1000 - points)
                    : "积分已满足白银会员，下次登录后自动升级。";
            case "SILVER"   -> points < 5000
                    ? String.format("距离黄金会员还需 %,d 积分（再积累 %,d 分）",
                        5000, 5000 - points)
                    : "积分已满足黄金会员，下次登录后自动升级。";
            case "GOLD"     -> points < 20000
                    ? String.format("距离铂金会员还需 %,d 积分（再积累 %,d 分）",
                        20000, 20000 - points)
                    : "积分已满足铂金会员，下次登录后自动升级。";
            case "PLATINUM" -> points < 100000
                    ? String.format("距离钻石会员还需 %,d 积分（再积累 %,d 分）",
                        100000, 100000 - points)
                    : "积分已满足钻石会员，下次登录后自动升级。";
            case "DIAMOND"  -> "您已是最高等级钻石会员，享有全部会员权益！";
            default         -> "请联系客服了解积分规则。";
        };
    }

    /**
     * 格式化计算结果（整数去小数点，浮点保留有效精度，去末尾零）
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
