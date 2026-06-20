package com.example.aiagent.story;

import dev.langchain4j.model.chat.ChatLanguageModel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoryAiService {

    private final ObjectProvider<ChatLanguageModel> chatModelProvider;
    private final ObjectMapper objectMapper;

    public String generateWriting(String action, String projectTitle, String chapterContent, String fallback) {
        String prompt = """
                你是资深网文作者和短剧改编产品里的创作助手。
                请根据任务输出可直接进入编辑器的中文内容，不要解释你的工作过程。

                作品名：%s
                任务：%s
                当前正文：
                %s

                输出要求：
                - 写小说任务要保留网文节奏、情绪钩子和具体动作。
                - setting 输出世界观、主线矛盾、爽点机制和连续性规则。
                - characters 输出人物小传、欲望、弱点、关系和出场功能。
                - expand/shorten/style 分别执行扩写、缩写和改风格。
                - review 输出章节问题诊断和可执行修改建议。
                - 如果是润色或去 AI 味，减少模板化表达、抽象形容和空泛总结。
                - 如果是大纲，使用清晰分点。
                """.formatted(projectTitle, action, trim(chapterContent, 5000));
        return complete(prompt, fallback);
    }

    public String rewriteSegment(String text, String mode, String instruction, String fallback) {
        String prompt = """
                你是网文改稿编辑。请按指定模式改写片段，只输出改写后的正文。

                改写模式：%s
                用户补充要求：%s
                原文：
                %s

                改写要求：
                - 保留原剧情信息和人物关系。
                - 对白更口语化，动作更具体。
                - 去掉 AI 腔、总结腔、过度解释。
                - 不要输出分析说明。
                """.formatted(mode, instruction == null ? "" : instruction, trim(text, 3000));
        return complete(prompt, fallback);
    }

    public Map<String, Object> generateScriptDraft(String projectTitle,
                                                   String sourceText,
                                                   int targetEpisodes,
                                                   Map<String, Object> fallback) {
        String fallbackJson = toJson(fallback);
        String prompt = """
                你是短剧改编主编。请把小说内容改编为短剧分场稿，并且只输出 JSON，不要 Markdown，不要解释。

                作品名：%s
                目标集数：%s
                小说正文：
                %s

                JSON 结构必须符合：
                {
                  "adaptationPlan": {
                    "storyCore": "故事核",
                    "characterRelations": "人物关系",
                    "plotSelection": "情节取舍",
                    "strategy": "改编策略"
                  },
                  "episodes": [
                    {
                      "episodeNo": 1,
                      "title": "第1集",
                      "estimatedDuration": "1-3分钟",
                      "coreHook": "核心爽点",
                      "mainConflict": "本集冲突",
                      "endingHook": "结尾钩子",
                      "summary": "分集梗概",
                      "scenes": [
                        {
                          "sceneNo": 1,
                          "sceneTitle": "场次标题",
                          "location": "内/外｜地点｜日/夜",
                          "timeOfDay": "日/夜",
                          "characters": "人物",
                          "sceneFunction": "本场功能",
                          "estimatedDuration": "预计时长",
                          "visualAction": "画面",
                          "narration": "旁白",
                          "dialogue": "对白",
                          "performanceCameraNote": "表演/镜头",
                          "hook": "钩子"
                        }
                      ]
                    }
                  ]
                }

                短剧规则：
                - 第1集前3秒必须有情绪、冲突或信息钩子。
                - 每集至少2场，每场必须可拍。
                - 心理活动必须外化为动作、对白、道具或人物反应。
                - 对白要口语化，减少小说旁白式解释。
                """.formatted(projectTitle, targetEpisodes, trim(sourceText, 12000));
        return completeJson(prompt, fallbackJson, fallback);
    }

    public Map<String, Object> qualityCheck(String draftMarkdown, Map<String, Object> fallback) {
        String fallbackJson = toJson(fallback);
        String prompt = """
                你是短剧平台审稿和可拍性检查专家。请检查下面的短剧分场稿，只输出 JSON，不要 Markdown，不要解释。

                分场稿：
                %s

                JSON 结构必须符合：
                {
                  "totalScore": 0,
                  "mainIssues": ["主要问题"],
                  "episodeIssues": [{"episodeNo": 1, "issues": ["问题"]}],
                  "sceneIssues": [{"sceneNo": "1-1", "issues": ["问题"]}],
                  "autoFixable": ["可自动修复项"],
                  "manualReview": ["需人工判断项"],
                  "checks": {
                    "openingHook": "pass/warn/fail",
                    "conflictDensity": "pass/warn/fail",
                    "endingHook": "pass/warn/fail",
                    "dialogueOrality": "pass/warn/fail",
                    "novelResidue": "pass/warn/fail",
                    "shootability": "pass/warn/fail"
                  }
                }

                检查规则：
                - 前3秒必须有钩子。
                - 每30到60秒至少一次冲突推进、反转、误会、打脸、危机或情绪爆发。
                - 每集结尾必须有追看钩子。
                - 标记小说残留：大段内心独白、抽象描写、无法拍摄的叙述。
                """.formatted(trim(draftMarkdown, 12000));
        return completeJson(prompt, fallbackJson, fallback);
    }

    public Map<String, Object> improveScene(String action,
                                            Map<String, Object> scene,
                                            Map<String, Object> fallback) {
        String fallbackJson = toJson(fallback);
        String prompt = """
                你是短剧分场稿编辑。请按任务修复当前场次，只输出 JSON，不要 Markdown，不要解释。

                任务：%s
                当前场次 JSON：
                %s

                JSON 结构必须只包含这些可更新字段：
                {
                  "sceneTitle": "场次标题",
                  "location": "内/外｜地点｜日/夜",
                  "characters": "人物",
                  "sceneFunction": "本场功能",
                  "estimatedDuration": "预计时长",
                  "visualAction": "画面",
                  "narration": "旁白",
                  "dialogue": "对白",
                  "performanceCameraNote": "表演/镜头",
                  "hook": "钩子"
                }

                任务含义：
                - rewrite：重写本场，保留剧情功能但增强冲突。
                - hook：补强开场或结尾钩子。
                - dialogue：对白口语化，减少解释。
                - externalize：把心理活动外化为动作、对白、道具或人物反应。
                """.formatted(action, toJson(scene));
        return completeJson(prompt, fallbackJson, fallback);
    }

    private String complete(String prompt, String fallback) {
        ChatLanguageModel model = chatModelProvider.getIfAvailable();
        if (model == null) return fallback;
        try {
            String result = model.generate(prompt);
            return result == null || result.isBlank() ? fallback : result.trim();
        } catch (Exception e) {
            log.warn("Story AI generation fallback used: {}", e.getMessage());
            return fallback;
        }
    }

    private Map<String, Object> completeJson(String prompt, String fallbackJson, Map<String, Object> fallback) {
        String raw = complete(prompt, fallbackJson);
        try {
            return objectMapper.readValue(extractJsonObject(raw), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Story AI JSON parse fallback used: {}", e.getMessage());
            return fallback == null ? new LinkedHashMap<>() : fallback;
        }
    }

    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) return "{}";
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return text;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String trim(String value, int maxChars) {
        if (value == null) return "";
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "\n...[已截断]";
    }
}
