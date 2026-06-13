package com.example.aiagent.agent;

import com.example.aiagent.config.DeepSeekModelFactory;
import com.example.aiagent.memory.RedisChatMemoryStore;
import com.example.aiagent.rag.model.RetrievedChunk;
import com.example.aiagent.rag.pipeline.HybridRagPipeline;
import com.example.aiagent.tool.BusinessTools;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private final HybridRagPipeline hybridRagPipeline;
    private final ObjectProvider<DeepSeekModelFactory> deepSeekModelFactory;
    private final RedisChatMemoryStore redisChatMemoryStore;

    /** 最大推理迭代次数（防止工具调用死循环） */
    private static final int MAX_ITERATIONS = 8;

    @Value("${agent.memory.max-messages:20}")
    private int maxMessages;

    /** 工具规格列表（懒加载，启动后不再变化） */
    private volatile List<ToolSpecification> cachedToolSpecs;

    /** JSON 解析器（线程安全，全局复用） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            可以少量使用自然的 emoji 表达语气，但不要密集使用；不要用 emoji 或装饰图标作为列表前缀。普通回答通常不超过 1 个表情，仍以纯文本、标题、列表和表格为主。
            输出代码时必须使用对应语言的标准、可运行/可编译格式，保留必要的空格、缩进、换行和标点。
            不要输出被压缩或粘连的代码；例如 Java 必须写成 public class Main、public static void main(String[] args)，不要写成 publicclassMain 或 publicstaticvoidmain。

            ## 会话上下文
            如果用户使用"再详细点"、"你再好好回复下"、"它/这个/上面"等承接表达，
            必须结合当前会话历史理解指代，不要声称自己没有上一轮对话记忆。
            """;

    /**
     * 执行 ReAct 多步推理
     *
     * @param userQuery  用户原始问题
     * @param sessionId  会话 ID（用于日志追踪）
     * @return           最终答案文本
     */
    public ReActResult execute(String userQuery, String sessionId) {
        return execute(userQuery, sessionId, null);
    }

    public ReActResult execute(String userQuery, String sessionId, String modelName) {
        return execute(userQuery, sessionId, modelName, null, null);
    }

    public ReActResult execute(String userQuery, String sessionId, String modelName,
                               String tenantId, Long kbId) {
        ChatLanguageModel activeModel = chatModel(modelName);
        log.info("[ReAct] 开始多步推理 sessionId={} model={} tenantId={} kbId={} query='{}'",
                sessionId, modelName, tenantId, kbId, userQuery);
        long startMs = System.currentTimeMillis();

        List<ChatMessage> messages = buildInitialMessages(sessionId, userQuery, tenantId, kbId);

        List<ToolSpecification> toolSpecs = getToolSpecs();
        List<ReActStep> steps = new ArrayList<>();

        String finalAnswer = null;
        int iteration = 0;

        while (iteration < MAX_ITERATIONS) {
            iteration++;
            log.debug("[ReAct] 第 {} 轮推理...", iteration);

            // ── LLM 推理：决定下一步 ──────────────────────────
            Response<AiMessage> response = activeModel.generate(messages, toolSpecs);
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
            Response<AiMessage> finalResponse = activeModel.generate(messages);
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
     * <p>使用 Jackson 解析 JSON 参数，按各工具的实际参数名精确提取，
     * 支持嵌套 JSON、特殊字符、多参数等复杂场景，不再依赖脆弱的字符串截取。
     */
    private String invokeTool(String toolName, String arguments) {
        try {
            // 使用 Jackson 解析 JSON 参数（健壮，支持嵌套/特殊字符）
            Map<String, Object> params = parseArgs(arguments);

            return switch (toolName) {
                case "queryOrderStatus"   -> businessTools.queryOrderStatus(getString(params, "orderId"));
                case "queryUserOrders"    -> businessTools.queryUserOrders(getString(params, "userId"));
                case "queryOrderSummary"  -> businessTools.queryOrderSummary(getString(params, "userId"));
                case "getWeather"         -> businessTools.getWeather(getString(params, "city"));
                case "queryUserAccount"   -> businessTools.queryUserAccount(getString(params, "userId"));
                case "queryUserPoints"    -> businessTools.queryUserPoints(getString(params, "userId"));
                case "calculate"          -> businessTools.calculate(getString(params, "expression"));
                case "getCurrentDateTime" -> businessTools.getCurrentDateTime(getString(params, "timezone"));
                default -> "未知工具：" + toolName;
            };
        } catch (Exception e) {
            log.warn("[ReAct] 工具 {} 调用异常: {}", toolName, e.getMessage());
            return "工具执行异常：" + e.getMessage();
        }
    }

    /**
     * 解析 LangChain4j 工具调用的 JSON 参数字符串
     *
     * <p>示例：{@code {"city":"北京"}} → Map{"city" → "北京"}
     *
     * @param jsonArgs LangChain4j 传入的 JSON 字符串（可为 null / 空 / "{}"）
     * @return 参数 Map，若解析失败返回空 Map
     */
    private Map<String, Object> parseArgs(String jsonArgs) {
        if (jsonArgs == null || jsonArgs.isBlank() || "{}".equals(jsonArgs.trim())) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(jsonArgs, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[ReAct] JSON 参数解析失败，原始参数：{}，错误：{}", jsonArgs, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 从参数 Map 中安全获取字符串值
     *
     * <p>若 Map 中该 key 不存在，返回空字符串（防止 NullPointerException）。
     */
    private String getString(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null) return "";
        return String.valueOf(val);
    }

    /**
     * 执行 ReAct 多步推理（流式回调版本，用于 SSE 实时推送）
     *
     * <p>与 {@link #execute} 的差别：每完成一个推理步骤立即触发 {@code stepCallback}，
     * 而不是全部完成后才返回，适合 SSE 场景。
     *
     * @param userQuery    用户原始问题
     * @param sessionId    会话 ID（日志追踪）
     * @param stepCallback 步骤回调，每完成一步时被调用（步骤数据 + 是否为最终答案步）
     * @return             最终结果
     */
    public ReActResult executeWithCallback(String userQuery, String sessionId,
                                           StepCallback stepCallback) {
        return executeWithCallback(userQuery, sessionId, null, stepCallback);
    }

    public ReActResult executeWithCallback(String userQuery, String sessionId, String modelName,
                                           StepCallback stepCallback) {
        return executeWithCallback(userQuery, sessionId, modelName, null, null, stepCallback);
    }

    public ReActResult executeWithCallback(String userQuery, String sessionId, String modelName,
                                           String tenantId, Long kbId,
                                           StepCallback stepCallback) {
        ChatLanguageModel activeModel = chatModel(modelName);
        log.info("[ReAct-Stream] 开始多步推理 sessionId={} model={} tenantId={} kbId={} query='{}'",
                sessionId, modelName, tenantId, kbId, userQuery);
        long startMs = System.currentTimeMillis();

        List<ChatMessage> messages = buildInitialMessages(sessionId, userQuery, tenantId, kbId);

        List<ToolSpecification> toolSpecs = getToolSpecs();
        List<ReActStep> steps = new ArrayList<>();

        String finalAnswer = null;
        int iteration = 0;

        while (iteration < MAX_ITERATIONS) {
            iteration++;
            log.debug("[ReAct-Stream] 第 {} 轮推理...", iteration);

            Response<AiMessage> response = activeModel.generate(messages, toolSpecs);
            AiMessage aiMessage = response.content();
            messages.add(aiMessage);

            String thought = aiMessage.text();
            List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();

            if (toolRequests == null || toolRequests.isEmpty()) {
                finalAnswer = thought != null ? thought : "";
                // 推送最终答案步骤
                stepCallback.onStep(
                        new ReActStep(iteration, thought, null, null, null),
                        true);
                break;
            }

            for (ToolExecutionRequest req : toolRequests) {
                String toolName = req.name();
                String toolArgs = req.arguments();
                log.info("[ReAct-Stream] 第 {} 轮 → 调用工具: {} 参数: {}", iteration, toolName, toolArgs);

                String toolResult;
                try {
                    toolResult = invokeTool(toolName, toolArgs);
                } catch (Exception e) {
                    toolResult = "工具调用失败：" + e.getMessage();
                    log.warn("[ReAct-Stream] 工具 {} 调用失败: {}", toolName, e.getMessage());
                }

                messages.add(ToolExecutionResultMessage.from(req, toolResult));

                ReActStep step = new ReActStep(iteration, thought, toolName, toolArgs, toolResult);
                steps.add(step);
                // 每完成一个工具调用步骤立刻推送
                stepCallback.onStep(step, false);
            }
        }

        if (finalAnswer == null) {
            log.warn("[ReAct-Stream] 达到最大迭代次数 {}，强制获取最终答案", MAX_ITERATIONS);
            messages.add(UserMessage.from("请根据以上所有观察信息，给出最终答案。"));
            Response<AiMessage> finalResponse = activeModel.generate(messages);
            finalAnswer = finalResponse.content().text();
            stepCallback.onStep(
                    new ReActStep(iteration, finalAnswer, null, null, null),
                    true);
        }

        long durationMs = System.currentTimeMillis() - startMs;
        log.info("[ReAct-Stream] 推理完成 iterations={} durationMs={}", iteration, durationMs);
        return new ReActResult(finalAnswer, steps, iteration, durationMs);
    }

    private ChatLanguageModel chatModel(String modelName) {
        DeepSeekModelFactory factory = deepSeekModelFactory.getIfAvailable();
        return factory != null ? factory.chatModel(modelName) : chatModel;
    }

    private List<ChatMessage> buildInitialMessages(String sessionId, String userQuery,
                                                   String tenantId, Long kbId) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        messages.addAll(loadConversationMemory(sessionId));
        messages.add(UserMessage.from(buildKnowledgeAwareUserMessage(userQuery, tenantId, kbId)));
        return messages;
    }

    private List<ChatMessage> loadConversationMemory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            List<ChatMessage> storedMessages = redisChatMemoryStore.getMessages(sessionId);
            if (storedMessages == null || storedMessages.isEmpty()) {
                return List.of();
            }
            List<ChatMessage> conversationMessages = storedMessages.stream()
                    .filter(this::isConversationMemoryMessage)
                    .toList();
            return trimMemory(conversationMessages);
        } catch (Exception e) {
            log.warn("[ReAct] 读取会话记忆失败 sessionId={}，将按无历史执行: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private boolean isConversationMemoryMessage(ChatMessage message) {
        if (message instanceof UserMessage) {
            return true;
        }
        if (message instanceof AiMessage aiMessage) {
            return aiMessage.text() != null && !aiMessage.text().isBlank();
        }
        return false;
    }

    public void rememberExchange(String sessionId, String userQuery, String answer) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        List<ChatMessage> updatedMemory = new ArrayList<>(loadConversationMemory(sessionId));
        updatedMemory.add(UserMessage.from(userQuery != null ? userQuery : ""));
        updatedMemory.add(AiMessage.from(answer != null ? answer : ""));
        redisChatMemoryStore.updateMessages(sessionId, trimMemory(updatedMemory));
    }

    private List<ChatMessage> trimMemory(List<ChatMessage> messages) {
        int limit = maxMessages > 0 ? maxMessages : 20;
        if (messages.size() <= limit) {
            return new ArrayList<>(messages);
        }
        return new ArrayList<>(messages.subList(messages.size() - limit, messages.size()));
    }

    private String buildKnowledgeAwareUserMessage(String userQuery, String tenantId, Long kbId) {
        if (tenantId == null || tenantId.isBlank()) {
            return userQuery;
        }

        List<RetrievedChunk> chunks;
        try {
            chunks = hybridRagPipeline.retrieveOnly(userQuery, tenantId, kbId);
        } catch (Exception e) {
            log.warn("[ReAct] 知识库检索失败，降级为无知识库上下文: {}", e.getMessage());
            chunks = List.of();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：\n").append(userQuery).append("\n\n");
        sb.append("当前已关联知识库。回答与知识库相关的问题时，必须只依据下面的知识库片段；");
        sb.append("如果片段不能支持回答，请明确说明当前知识库未找到相关信息，不要凭空补充。\n\n");

        if (chunks.isEmpty()) {
            sb.append("知识库片段：本次未检索到相关内容。\n");
            return sb.toString();
        }

        sb.append("知识库片段：\n");
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            sb.append("[").append(i + 1).append("] 来源：")
                    .append(chunk.getDocumentName() != null ? chunk.getDocumentName() : "未知文档");
            if (chunk.getChunkIndex() != null) {
                sb.append("，第").append(chunk.getChunkIndex() + 1).append("片");
            }
            sb.append("\n")
                    .append(chunk.getContent() != null ? chunk.getContent() : "")
                    .append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 推理步骤回调接口
     *
     * @param step    当前推理步骤（Thought + Action + Observation）
     * @param isFinal 是否为最终答案步（true 时 step.thought 为最终回答，无工具调用）
     */
    @FunctionalInterface
    public interface StepCallback {
        void onStep(ReActStep step, boolean isFinal);
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
                    cachedToolSpecs = ToolSpecifications.toolSpecificationsFrom(businessTools);
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

