package com.example.aiagent.agent;

import com.example.aiagent.tool.BusinessTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.Tools;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct（Reason + Act）多步推理 Agent
 *
 * <p>执行范式（Yao et al., 2022）：
 * <pre>
 *   Thought → Action → Observation → Thought → Action → ... → Final Answer
 * </pre>
 *
 * <p>与基础对话 Agent 的核心差异：
 * <ul>
 *   <li>基础 Agent：LLM 一次性输出答案（工具调用由框架自动注入）</li>
 *   <li>ReAct Agent：显式"思考 → 工具调用 → 观察结果"多轮循环，每轮都有可审计的推理痕迹</li>
 * </ul>
 *
 * <p>适用场景：
 * <ul>
 *   <li>需要多个工具协作才能完成的复杂任务（如：先查账户 → 再查订单 → 最后计算费用）</li>
 *   <li>需要动态决策下一步操作（不确定需要几步）</li>
 *   <li>需要完整推理过程可审计的场景</li>
 * </ul>
 *
 * <p>循环控制：
 * <ul>
 *   <li>最大迭代次数 {@link #MAX_ITERATIONS}，防止无限循环</li>
 *   <li>LLM 不再产生工具调用时自动终止</li>
 *   <li>Final Answer 关键词触发终止（兜底）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReActAgent {

    private final ChatLanguageModel chatModel;
    private final BusinessTools businessTools;

    /** 最大推理迭代次数（防止工具调用死循环） */
    private static final int MAX_ITERATIONS = 8;

    /** 工具规格列表（懒加载，启动后不再变化） */
    private volatile List<ToolSpecification> cachedToolSpecs;

    private static final String SYSTEM_PROMPT = """
            你是一个具备多步推理能力的智能助手（ReAct Agent）。

            ## 工作模式
            对于复杂任务，你需要按以下循环思考和行动：

            **Thought（思考）**：分析当前状态，决定下一步需要做什么
            **Action（行动）**：调用合适的工具获取信息
            **Observation（观察）**：分析工具返回的结果

            重复以上循环，直到你有足够的信息给出 **Final Answer**。

            ## 工具调用规则
            - 每次只调用一个最必要的工具
            - 用工具返回的真实数据回答，不要凭空猜测
            - 获得足够信息后，直接给出 Final Answer，不要过度调用工具

            ## 输出格式
            在 Final Answer 中，给出清晰、完整、友好的中文答案。
            """;

    /**
     * 执行 ReAct 多步推理
     *
     * @param userQuery  用户原始问题
     * @param sessionId  会话 ID（用于日志追踪）
     * @return           最终答案文本
     */
    public ReActResult execute(String userQuery, String sessionId) {
        log.info("[ReAct] 开始多步推理 sessionId={} query='{}'", sessionId, userQuery);
        long startMs = System.currentTimeMillis();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        messages.add(UserMessage.from(userQuery));

        List<ToolSpecification> toolSpecs = getToolSpecs();
        List<ReActStep> steps = new ArrayList<>();

        String finalAnswer = null;
        int iteration = 0;

        while (iteration < MAX_ITERATIONS) {
            iteration++;
            log.debug("[ReAct] 第 {} 轮推理...", iteration);

            // ── LLM 推理：决定下一步 ──────────────────────────
            Response<AiMessage> response = chatModel.generate(messages, toolSpecs);
            AiMessage aiMessage = response.content();
            messages.add(aiMessage);

            String thought = aiMessage.text();
            List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();

            // ── 判断是否结束 ────────────────────────────────────
            if (toolRequests == null || toolRequests.isEmpty()) {
                // LLM 没有发起工具调用，说明已得出最终答案
                finalAnswer = thought != null ? thought : "";
                log.info("[ReAct] 第 {} 轮无工具调用，推理结束", iteration);
                break;
            }

            // ── 执行工具调用 ────────────────────────────────────
            for (ToolExecutionRequest req : toolRequests) {
                String toolName = req.name();
                String toolArgs = req.arguments();

                log.info("[ReAct] 第 {} 轮 → 调用工具: {} 参数: {}", iteration, toolName, toolArgs);

                // 记录推理步骤（供调用方审计）
                ReActStep step = new ReActStep(iteration, thought, toolName, toolArgs, null);

                String toolResult;
                try {
                    toolResult = invokeTool(toolName, toolArgs);
                } catch (Exception e) {
                    toolResult = "工具调用失败：" + e.getMessage();
                    log.warn("[ReAct] 工具 {} 调用失败: {}", toolName, e.getMessage());
                }

                log.info("[ReAct] 工具 {} 返回: {}",
                        toolName, toolResult.length() > 200 ? toolResult.substring(0, 200) + "..." : toolResult);

                // 将工具结果加入对话历史（Observation）
                messages.add(ToolExecutionResultMessage.from(req, toolResult));

                // 记录步骤完整信息
                steps.add(new ReActStep(iteration, thought, toolName, toolArgs, toolResult));
            }
        }

        if (finalAnswer == null) {
            // 超过最大迭代次数，要求 LLM 给出最终答案
            log.warn("[ReAct] 达到最大迭代次数 {}，强制获取最终答案", MAX_ITERATIONS);
            messages.add(UserMessage.from("请根据以上所有观察信息，给出最终答案。"));
            Response<AiMessage> finalResponse = chatModel.generate(messages);
            finalAnswer = finalResponse.content().text();
        }

        long durationMs = System.currentTimeMillis() - startMs;
        log.info("[ReAct] 推理完成 iterations={} durationMs={}", iteration, durationMs);

        return new ReActResult(finalAnswer, steps, iteration, durationMs);
    }

    // ── 工具调用分派 ──────────────────────────────────────────────

    /**
     * 根据工具名和 JSON 参数字符串调用对应的 BusinessTools 方法
     *
     * LangChain4j 的工具调用约定：
     *   - 方法上标注 @Tool，框架自动生成 ToolSpecification
     *   - 调用时 arguments 是 JSON 字符串，单参数形如 {"city":"北京"}
     */
    private String invokeTool(String toolName, String arguments) {
        // 使用 LangChain4j 的工具执行器通过反射调用 BusinessTools 中的方法
        try {
            // 解析参数（LangChain4j arguments 格式为 JSON 对象）
            String param = extractFirstStringParam(arguments);

            return switch (toolName) {
                case "queryOrderStatus"  -> businessTools.queryOrderStatus(param);
                case "queryUserOrders"   -> businessTools.queryUserOrders(param);
                case "queryOrderSummary" -> businessTools.queryOrderSummary(param);
                case "getWeather"        -> businessTools.getWeather(param);
                case "queryUserAccount"  -> businessTools.queryUserAccount(param);
                case "queryUserPoints"   -> businessTools.queryUserPoints(param);
                case "calculate"         -> businessTools.calculate(param);
                case "getCurrentDateTime"-> businessTools.getCurrentDateTime();
                default -> "未知工具：" + toolName;
            };
        } catch (Exception e) {
            return "工具执行异常：" + e.getMessage();
        }
    }

    /**
     * 从 JSON 参数字符串中提取第一个字符串参数值
     *
     * 示例：{"city":"北京"} → "北京"
     *       {"orderId":"#12345"} → "#12345"
     *       {"expression":"1+2*3"} → "1+2*3"
     *       {}  → ""（无参数工具）
     */
    private String extractFirstStringParam(String jsonArgs) {
        if (jsonArgs == null || jsonArgs.isBlank() || "{}".equals(jsonArgs.trim())) {
            return "";
        }
        // 简单解析：找到第一个冒号后的值
        int colonIdx = jsonArgs.indexOf(':');
        if (colonIdx == -1) return jsonArgs;

        String valueStr = jsonArgs.substring(colonIdx + 1).trim();
        // 去掉结尾的 }
        int endIdx = valueStr.lastIndexOf('}');
        if (endIdx > 0) valueStr = valueStr.substring(0, endIdx).trim();
        // 去掉两端引号
        if (valueStr.startsWith("\"") && valueStr.endsWith("\"")) {
            valueStr = valueStr.substring(1, valueStr.length() - 1);
        }
        return valueStr;
    }

    /**
     * 获取工具规格列表（懒加载 + 双重检查锁定）
     *
     * 通过 LangChain4j 的 Tools 工具类从 BusinessTools 的 @Tool 注解中自动提取规格，
     * 不需要手动维护工具列表。
     */
    private List<ToolSpecification> getToolSpecs() {
        if (cachedToolSpecs == null) {
            synchronized (this) {
                if (cachedToolSpecs == null) {
                    cachedToolSpecs = Tools.toolSpecificationsFrom(businessTools);
                    log.info("[ReAct] 已加载工具规格 {} 个: {}",
                            cachedToolSpecs.size(),
                            cachedToolSpecs.stream().map(ToolSpecification::name).toList());
                }
            }
        }
        return cachedToolSpecs;
    }

    // ── 结果数据类 ────────────────────────────────────────────────

    /**
     * ReAct 执行结果
     *
     * @param answer      最终答案文本
     * @param steps       每轮推理步骤（Thought + Action + Observation）
     * @param iterations  实际迭代次数
     * @param durationMs  总耗时（毫秒）
     */
    public record ReActResult(
            String answer,
            List<ReActStep> steps,
            int iterations,
            long durationMs
    ) {}

    /**
     * 单次推理步骤
     *
     * @param iteration   第几轮
     * @param thought     LLM 的思考文本
     * @param toolName    调用的工具名
     * @param toolArgs    工具参数（JSON）
     * @param observation 工具返回结果
     */
    public record ReActStep(
            int iteration,
            String thought,
            String toolName,
            String toolArgs,
            String observation
    ) {}
}

