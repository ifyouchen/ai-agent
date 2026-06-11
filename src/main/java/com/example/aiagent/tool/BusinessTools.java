package com.example.aiagent.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 业务工具集合
 * 在此添加你的业务工具，LLM 会根据用户意图自动决定是否调用
 *
 * 使用方法：
 * 1. 在方法上加 @Tool 注解，描述工具功能
 * 2. 在参数上加 @P 注解，描述参数含义
 * 3. 在 AgentFactory 中注入此类，框架自动注册
 */
@Slf4j
@Component
public class BusinessTools {

    /**
     * 查询订单状态
     * 实际项目中替换为真实的数据库查询
     */
    @Tool("查询指定订单的当前状态和物流信息")
    public String queryOrderStatus(@P("订单编号，格式如 #12345") String orderId) {
        log.info("查询订单: {}", orderId);
        // TODO: 替换为真实的数据库查询
        // return orderRepository.findById(orderId).getStatus();
        return String.format("订单 %s 状态：已发货，预计明天到达，快递单号：SF1234567890", orderId);
    }

    /**
     * 查询天气
     * 实际项目中替换为天气 API 调用
     */
    @Tool("查询指定城市的实时天气信息")
    public String getWeather(@P("城市名称，如：北京、上海、深圳") String city) {
        log.info("查询天气: {}", city);
        // TODO: 替换为真实的天气 API
        // return weatherApiClient.getWeather(city);
        return String.format("%s：晴天，气温 26°C，湿度 45%%，空气质量优", city);
    }

    /**
     * 查询用户账户信息
     * 注意：生产环境需要做权限校验
     */
    @Tool("查询用户的账户余额和会员等级信息")
    public String queryUserAccount(@P("用户ID") String userId) {
        log.info("查询用户账户: {}", userId);
        // TODO: 替换为真实的用户服务调用
        return String.format("用户 %s：账户余额 ¥1,280.00，会员等级：黄金会员，积分：3,500", userId);
    }

    /**
     * 计算工具（示例：简单计算）
     */
    @Tool("执行数学计算，支持加减乘除和基本数学运算")
    public String calculate(@P("数学表达式，如：1+2*3") String expression) {
        log.info("执行计算: {}", expression);
        // 简单示例，实际可引入表达式解析库
        try {
            // 生产环境不要用 eval，使用 Aviator 或 Mvel 等安全的表达式引擎
            return "计算结果：" + expression + " = (请接入真实计算引擎)";
        } catch (Exception e) {
            return "计算失败：" + e.getMessage();
        }
    }
}
