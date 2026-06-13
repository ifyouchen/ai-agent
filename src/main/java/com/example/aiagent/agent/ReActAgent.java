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
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

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
    private final StreamingChatLanguageModel streamingChatModel;
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

            **Thought（思考摘要）**：只输出给用户可见的简短推理摘要，分析当前状态，决定下一步需要做什么；不要展开完整隐式思维链
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

    private static final String FINAL_ANSWER_PROMPT = """
            请基于以上会话、知识库片段、推理摘要和工具观察，直接生成面向用户的最终答案。
            要求：
            - 不要继续调用工具。
            - 不要输出 Thought/Action/Observation 标签。
            - 如果知识库片段不足以支持回答，请明确说明当前知识库未找到相关信息。
            """;

    private static final String STREAMING_REACT_PROTOCOL_PROMPT = """
            当前使用流式 ReAct 协议：
            - 每轮只输出 1-3 句给用户可见的推理摘要，并通过工具调用表达需要的行动。
            - 不要输出完整隐式思维链。
            - 不要在推理轮输出最终答案；若信息已足够，只说明“信息已足够，可以生成最终答案”，然后停止工具调用。
            - 最终答案由后续 answer 阶段单独生成。
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
                case "listMyOrganizations"   -> businessTools.listMyOrganizations();
                case "listOrgMembers"        -> businessTools.listOrgMembers(getString(params, "orgId"));
                case "listMyKnowledgeBases"  -> businessTools.listMyKnowledgeBases(getString(params, "orgName"));
                case "listKbDocuments"       -> {
                    String kbIdStr = getString(params, "kbId");
                    Long kbId;
                    try {
                        kbId = kbIdStr.isBlank() ? null : Long.parseLong(kbIdStr);
                    } catch (NumberFormatException e) {
                        yield "参数错误：kbId 应为数字，收到：" + kbIdStr;
                    }
                    yield businessTools.listKbDocuments(kbId);
                }
                case "getSystemCapabilities" -> businessTools.getSystemCapabilities();
                case "getDeploymentGuide"    -> businessTools.getDeploymentGuide();
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

    /**
     * 执行全链路 ReAct token 流式推理。
     *
     * <p>该路径把可见推理摘要、工具调用、工具结果和最终答案拆成语义化事件，
     * 由 Controller 转换为 SSE 推送给前端。这里返回的是最终汇总结果，便于持久化和审计。
     */
    public ReActResult executeStreamingWithCallback(String userQuery, String sessionId, String modelName,
                                                    String tenantId, Long kbId,
                                                    ReActStreamCallback streamCallback) {
        StreamingChatLanguageModel activeModel = streamingChatModel(modelName);
        ReActStreamCallback callback = streamCallback != null ? streamCallback : new ReActStreamCallback() {};
        log.info("[ReAct-TokenStream] 开始多步推理 sessionId={} model={} tenantId={} kbId={} query='{}'",
                sessionId, modelName, tenantId, kbId, userQuery);
        long startMs = System.currentTimeMillis();

        List<ChatMessage> messages = buildInitialMessages(sessionId, userQuery, tenantId, kbId);
        messages.add(1, SystemMessage.from(STREAMING_REACT_PROTOCOL_PROMPT));
        List<ToolSpecification> toolSpecs = getToolSpecs();
        List<ReActStep> steps = new ArrayList<>();

        int iteration = 0;
        boolean usedTools = false;
        String finalAnswer = null;
        while (iteration < MAX_ITERATIONS) {
            iteration++;
            int currentIteration = iteration;
            log.debug("[ReAct-TokenStream] 第 {} 轮推理...", currentIteration);

            callback.onReasoningStart(currentIteration);
            StringBuilder reasoningBuffer = new StringBuilder();
            Response<AiMessage> response = streamGenerate(activeModel, messages, toolSpecs, token -> {
                reasoningBuffer.append(token);
                callback.onReasoningToken(currentIteration, token);
            });

            AiMessage aiMessage = response != null && response.content() != null
                    ? response.content()
                    : AiMessage.from("");
            messages.add(aiMessage);

            String streamedReasoning = reasoningBuffer.toString();
            String thought = !streamedReasoning.isBlank()
                    ? streamedReasoning
                    : aiMessage.text() != null ? aiMessage.text() : "";
            callback.onReasoningDone(currentIteration, thought);

            List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
            if (toolRequests == null || toolRequests.isEmpty()) {
                if (!usedTools && isDirectAnswerCandidate(thought)) {
                    log.info("[ReAct-TokenStream] 第 {} 轮无工具调用，复用模型输出作为最终答案", currentIteration);
                    finalAnswer = thought;
                    callback.onAnswerStart(currentIteration);
                    callback.onAnswerToken(thought);
                } else {
                    log.info("[ReAct-TokenStream] 第 {} 轮无工具调用，进入最终答案生成", currentIteration);
                }
                break;
            }

            usedTools = true;
            for (ToolExecutionRequest req : toolRequests) {
                String toolName = req.name();
                String toolArgs = req.arguments();
                log.info("[ReAct-TokenStream] 第 {} 轮 → 调用工具: {} 参数: {}",
                        currentIteration, toolName, toolArgs);

                ReActStep pendingStep = new ReActStep(currentIteration, thought, toolName, toolArgs, null);
                callback.onToolCall(pendingStep);

                String toolResult;
                try {
                    toolResult = invokeTool(toolName, toolArgs);
                } catch (Exception e) {
                    toolResult = "工具调用失败：" + e.getMessage();
                    log.warn("[ReAct-TokenStream] 工具 {} 调用失败: {}", toolName, e.getMessage());
                }

                log.info("[ReAct-TokenStream] 工具 {} 返回: {}",
                        toolName, toolResult.length() > 200 ? toolResult.substring(0, 200) + "..." : toolResult);

                messages.add(ToolExecutionResultMessage.from(req, toolResult));

                ReActStep step = new ReActStep(currentIteration, thought, toolName, toolArgs, toolResult);
                steps.add(step);
                callback.onToolResult(step);
            }
        }

        if (iteration >= MAX_ITERATIONS) {
            log.warn("[ReAct-TokenStream] 达到最大迭代次数 {}，强制生成最终答案", MAX_ITERATIONS);
        }

        if (finalAnswer == null) {
            messages.add(UserMessage.from(FINAL_ANSWER_PROMPT));
            int answerIteration = Math.max(iteration, 1);
            callback.onAnswerStart(answerIteration);
            StringBuilder answerBuffer = new StringBuilder();
            Response<AiMessage> finalResponse = streamGenerate(activeModel, messages, null, token -> {
                answerBuffer.append(token);
                callback.onAnswerToken(token);
            });

            String streamedAnswer = answerBuffer.toString();
            AiMessage finalMessage = finalResponse != null ? finalResponse.content() : null;
            finalAnswer = !streamedAnswer.isBlank()
                    ? streamedAnswer
                    : finalMessage != null && finalMessage.text() != null ? finalMessage.text() : "";
        }

        long durationMs = System.currentTimeMillis() - startMs;
        log.info("[ReAct-TokenStream] 推理完成 iterations={} durationMs={}", iteration, durationMs);
        return new ReActResult(finalAnswer, steps, iteration, durationMs);
    }

    private boolean isDirectAnswerCandidate(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.strip();
        if (normalized.length() < 12) {
            return false;
        }
        String lower = normalized.toLowerCase();
        return !lower.contains("thought")
                && !lower.contains("action")
                && !lower.contains("observation")
                && !normalized.contains("信息已足够")
                && !normalized.contains("最终答案")
                && !normalized.contains("生成最终")
                && !normalized.contains("可以生成");
    }

    private Response<AiMessage> streamGenerate(StreamingChatLanguageModel activeModel,
                                               List<ChatMessage> messages,
                                               List<ToolSpecification> toolSpecs,
                                               Consumer<String> tokenConsumer) {
        CompletableFuture<Response<AiMessage>> future = new CompletableFuture<>();
        StreamingResponseHandler<AiMessage> handler = new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
                if (token != null && !token.isEmpty()) {
                    tokenConsumer.accept(token);
                }
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                future.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        };

        try {
            if (toolSpecs == null) {
                activeModel.generate(messages, handler);
            } else {
                activeModel.generate(messages, toolSpecs, handler);
            }
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }

        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    private ChatLanguageModel chatModel(String modelName) {
        DeepSeekModelFactory factory = deepSeekModelFactory.getIfAvailable();
        return factory != null ? factory.chatModel(modelName) : chatModel;
    }

    private StreamingChatLanguageModel streamingChatModel(String modelName) {
        DeepSeekModelFactory factory = deepSeekModelFactory.getIfAvailable();
        return factory != null ? factory.streamingModel(modelName) : streamingChatModel;
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

    @Async
    public void rememberExchangeAsync(String sessionId, String userQuery, String answer) {
        rememberExchange(sessionId, userQuery, answer);
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
     * 全链路 ReAct 流式回调接口。
     *
     * <p>默认空实现，调用方可以只关心自己需要转发的事件。
     */
    public interface ReActStreamCallback {
        default void onReasoningStart(int iteration) {}

        default void onReasoningToken(int iteration, String token) {}

        default void onReasoningDone(int iteration, String text) {}

        default void onToolCall(ReActStep step) {}

        default void onToolResult(ReActStep step) {}

        default void onAnswerStart(int iteration) {}

        default void onAnswerToken(String token) {}
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

