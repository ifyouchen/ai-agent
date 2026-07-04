package com.example.aiagent.chat.service;

import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 回复代码块后处理服务。
 *
 * <p>所属聊天模块，职责是在 AI 最终回复进入前端和历史记录前，对 Markdown 代码块做安全的语言识别、
 * Java 源码格式化和必要的单次修复重试；不在前端用正则猜测或改写源码 token。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeBlockPostProcessor {

    private static final Pattern FENCED_CODE_PATTERN =
            Pattern.compile("(?s)(```|~~~)([^\\r\\n]*)\\R(.*?)(\\R?\\1)");
    private static final Pattern INFO_LANGUAGE_PATTERN =
            Pattern.compile("^\\s*([A-Za-z0-9_+.#-]+)");
    private static final Pattern JAVA_SOURCE_PATTERN =
            Pattern.compile("(?m)^\\s*(package\\s+[\\w.]+;|import\\s+(?:static\\s+)?[\\w.*]+;|"
                    + "import(?:java|javax|com|org|net)\\.|"
                    + "public\\s+(?:final\\s+)?(?:class|interface|enum|record)\\s+\\w+|"
                    + "public(?:class|interface|enum|record)[A-Z_$])");

    private final ObjectProvider<CodeBlockRepairClient> repairClientProvider;

    /**
     * 处理 AI 最终回复中的代码块。
     *
     * <p>前置条件：传入内容已经完成基础安全过滤。后置条件：可解析 Java 会被格式化，缺失 fenced
     * 的整段 Java 源码会被包装为 Java 代码块；格式化失败且修复失败时返回原内容。可能抛出运行时异常时会在
     * 方法内部降级并记录日志，不向调用方传播。
     *
     * @param answer    AI 最终回复
     * @param modelName 用户本次使用的模型名称，用于修复重试
     * @return 后处理后的回复文本
     */
    public String process(String answer, String modelName) {
        if (answer == null || answer.isBlank()) {
            return answer;
        }
        try {
            return processSafely(answer, modelName);
        } catch (RuntimeException e) {
            log.warn("[CODE_BLOCK] 回复后处理失败，保留原文 reason={}", e.getMessage());
            return answer;
        }
    }

    private String processSafely(String answer, String modelName) {
        if (FENCED_CODE_PATTERN.matcher(answer).find()) {
            return processFencedBlocks(answer, modelName);
        }
        return wrapPlainJavaSource(answer, modelName);
    }

    private String processFencedBlocks(String answer, String modelName) {
        Matcher matcher = FENCED_CODE_PATTERN.matcher(answer);
        StringBuilder result = new StringBuilder(answer.length());
        int cursor = 0;
        while (matcher.find()) {
            result.append(answer, cursor, matcher.start());
            result.append(processMatchedFence(matcher, modelName));
            cursor = matcher.end();
        }
        result.append(answer, cursor, answer.length());
        return result.toString();
    }

    private String processMatchedFence(Matcher matcher, String modelName) {
        String fence = matcher.group(1);
        String info = matcher.group(2);
        String code = normalizeNewlines(matcher.group(3));
        String language = normalizedLanguage(info);
        String inferred = language.isBlank() ? inferLanguage(code) : language;
        String finalLanguage = inferred.isBlank() ? language : inferred;
        String finalCode = "java".equals(finalLanguage)
                ? processJavaCode(code, modelName)
                : code;
        String finalInfo = finalLanguage.isBlank() ? info.strip() : finalLanguage;
        return fence + finalInfo + "\n" + trimTrailingBlankLines(finalCode) + "\n" + fence;
    }

    private String wrapPlainJavaSource(String answer, String modelName) {
        String normalized = normalizeNewlines(answer).strip();
        if (!isPlainJavaSource(normalized)) {
            return answer;
        }
        String finalCode = processJavaCode(normalized, modelName);
        return "```java\n" + trimTrailingBlankLines(finalCode) + "\n```";
    }

    private String processJavaCode(String code, String modelName) {
        Optional<String> formatted = formatJava(code);
        if (formatted.isPresent()) {
            return formatted.get();
        }
        Optional<String> repaired = repairJava(code, modelName);
        if (repaired.isEmpty()) {
            return code;
        }
        return formatJava(repaired.get()).orElseGet(() -> {
            log.warn("[CODE_BLOCK] Java 修复后仍无法格式化，保留原始代码块");
            return code;
        });
    }

    private Optional<String> formatJava(String code) {
        try {
            return Optional.of(new Formatter().formatSource(code));
        } catch (FormatterException | LinkageError e) {
            log.debug("[CODE_BLOCK] Java 格式化失败 reason={}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> repairJava(String code, String modelName) {
        CodeBlockRepairClient repairClient = repairClientProvider.getIfAvailable();
        if (repairClient == null) {
            return Optional.empty();
        }
        return repairClient.repairJavaCode(code, modelName)
                .map(CodeBlockPostProcessor::extractFirstCodeBlock)
                .filter(value -> !value.isBlank());
    }

    private static String extractFirstCodeBlock(String value) {
        Matcher matcher = FENCED_CODE_PATTERN.matcher(value.strip());
        if (matcher.find()) {
            return normalizeNewlines(matcher.group(3)).strip();
        }
        return normalizeNewlines(value).strip();
    }

    private static String normalizedLanguage(String info) {
        Matcher matcher = INFO_LANGUAGE_PATTERN.matcher(info == null ? "" : info);
        if (!matcher.find()) {
            return "";
        }
        String token = matcher.group(1).toLowerCase(Locale.ROOT);
        return switch (token) {
            case "java" -> "java";
            case "js", "jsx" -> "javascript";
            case "ts", "tsx" -> "typescript";
            case "py" -> "python";
            case "sh", "bash", "zsh" -> "shell";
            case "html", "htm", "vue" -> "html";
            case "yml" -> "yaml";
            case "md" -> "markdown";
            default -> token;
        };
    }

    private static String inferLanguage(String code) {
        return looksLikeJavaSource(code) ? "java" : "";
    }

    private static boolean isPlainJavaSource(String value) {
        return looksLikeJavaSource(value) && codeLineRatio(value) >= 0.45;
    }

    private static boolean looksLikeJavaSource(String value) {
        return value != null && JAVA_SOURCE_PATTERN.matcher(value).find();
    }

    private static double codeLineRatio(String value) {
        String[] lines = value.split("\\R");
        int total = 0;
        int code = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            total++;
            if (isCodeLikeLine(line.strip())) {
                code++;
            }
        }
        return total == 0 ? 0 : (double) code / total;
    }

    private static boolean isCodeLikeLine(String line) {
        return line.contains(";") || line.contains("{") || line.contains("}")
                || line.startsWith("package ") || line.startsWith("import ")
                || line.startsWith("public ") || line.startsWith("private ")
                || line.startsWith("protected ") || line.startsWith("//")
                || line.startsWith("/*") || line.startsWith("*");
    }

    private static String normalizeNewlines(String value) {
        return String.valueOf(value == null ? "" : value).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String trimTrailingBlankLines(String value) {
        return normalizeNewlines(value).replaceFirst("\\s++$", "");
    }
}

@FunctionalInterface
interface CodeBlockRepairClient {

    /**
     * 请求模型修复损坏的 Java 代码块。
     *
     * @param brokenCode 已确认无法被 Java formatter 解析的原始代码
     * @param modelName  用户本次选择的模型名称
     * @return 修复后的完整 Java 代码块；无法修复时为空
     */
    Optional<String> repairJavaCode(String brokenCode, String modelName);
}
