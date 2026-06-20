package com.example.aiagent.story;

final class StoryImportClassifier {

    private StoryImportClassifier() {
    }

    static String detectType(String content) {
        String text = content == null ? "" : content;
        boolean hasEpisode = text.matches("(?s).*第[一二三四五六七八九十百千万0-9]+集.*");
        boolean hasScene = text.contains("【第") || text.contains("场景：");
        boolean hasScriptMarker = text.contains("对白：")
                || text.contains("钩子：")
                || text.contains("本集冲突")
                || text.contains("出场人物")
                || text.contains("旁白：")
                || text.contains("△")
                || text.contains("独白");
        if (hasEpisode && hasScene && hasScriptMarker) {
            return "short_drama";
        }
        if (text.contains("改编方案")
                || (text.contains("故事核") && text.contains("人物关系"))
                || text.contains("情节取舍")
                || text.contains("题材迁移")) {
            return "adaptation";
        }
        return wordCount(text) > 20_000 ? "long_novel" : "short_story";
    }

    static int wordCount(String text) {
        return text == null ? 0 : text.replaceAll("\\s+", "").length();
    }
}
