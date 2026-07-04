package com.example.aiagent.chat.service;

import com.example.aiagent.config.DeepSeekModelFactory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 基于当前聊天模型的 Java 代码块修复客户端。
 *
 * <p>所属聊天模块，仅在真实 Java formatter 无法解析 AI 生成代码时执行一次补救请求，要求模型返回完整
 * fenced Java 代码块，避免前端或后端用正则猜测源码 token。
 */
@Slf4j
@Service
@RequiredArgsConstructor
class AiCodeBlockRepairClient implements CodeBlockRepairClient {

    private static final int MAX_REPAIR_CHARS = 30_000;

    private final ChatLanguageModel chatLanguageModel;
    private final ObjectProvider<DeepSeekModelFactory> deepSeekModelFactory;

    @Override
    public Optional<String> repairJavaCode(String brokenCode, String modelName) {
        if (brokenCode == null || brokenCode.isBlank() || brokenCode.length() > MAX_REPAIR_CHARS) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(activeModel(modelName).generate(buildRepairPrompt(brokenCode)));
        } catch (RuntimeException e) {
            log.warn("[CODE_BLOCK] Java 代码 AI 修复失败 model={} reason={}", modelName, e.getMessage());
            return Optional.empty();
        }
    }

    private ChatLanguageModel activeModel(String modelName) {
        DeepSeekModelFactory factory = deepSeekModelFactory.getIfAvailable();
        return factory != null ? factory.chatModel(modelName) : chatLanguageModel;
    }

    private String buildRepairPrompt(String brokenCode) {
        return """
                下面是一段 AI 回复中已经损坏的 Java 源码。请只做源码修复，不要解释。

                修复要求：
                - 只返回一个完整的 ```java fenced code block。
                - 保留原有功能意图，修复被错误粘连或拆开的 token。
                - 不要把合法标识符拆开；例如 JFrame、BOARD_PIXEL、Integer.MIN_VALUE、ButtonGroup modeGroup。
                - 不要输出 importjava、JFram e、BOARD _PIXEL、ButtonGroupmodeGroup、returnfalse 这类错误。
                - 返回代码必须尽量可编译，并保持清晰缩进。

                损坏源码：
                ```java
                %s
                ```
                """.formatted(brokenCode);
    }
}
