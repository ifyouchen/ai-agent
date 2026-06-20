package com.example.aiagent.story;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StoryImportClassifier - 小说导入类型识别")
class StoryImportClassifierTest {

    @Test
    @DisplayName("识别改编方案样例")
    void shouldDetectAdaptationPlan() throws IOException {
        String content = sample("adaptation-equivalent.txt");

        assertThat(StoryImportClassifier.detectType(content)).isEqualTo("adaptation");
    }

    @Test
    @DisplayName("识别短剧分场稿样例")
    void shouldDetectShortDramaScript() throws IOException {
        String content = sample("short-drama-equivalent.txt");

        assertThat(StoryImportClassifier.detectType(content)).isEqualTo("short_drama");
    }

    @Test
    @DisplayName("短剧优先于改编方案识别")
    void shouldPreferShortDramaWhenScriptContainsAdaptationTerms() {
        String content = """
                第2集
                本集冲突：改编方案在会议室被当众否定。
                【第1场】
                场景：内｜会议室｜夜
                对白：主角：这不是你的故事核，是我的证据。
                钩子：投影屏弹出原稿署名。
                """;

        assertThat(StoryImportClassifier.detectType(content)).isEqualTo("short_drama");
    }

    @Test
    @DisplayName("识别含动作符号和出场人物的短剧初稿")
    void shouldDetectDraftScriptWithStageDirections() {
        String content = """
                【第1集｜穿越与退婚：清华博士魂断大乾考场】
                【第1场】
                场景：三河县萧家食肆，临窗木桌，墙上贴黄历
                出场人物：萧火旺、柳如烟
                △ 黄历特写：永泰十七年三月廿三，宜嫁娶，忌出行。
                旁白：他是清华大学历史系和汉语言文学系的双博士。
                萧火旺（独白）：大乾永泰十七年……这是一个史书上没有的朝代。
                """;

        assertThat(StoryImportClassifier.detectType(content)).isEqualTo("short_drama");
    }

    @Test
    @DisplayName("短文本默认识别为短篇故事")
    void shouldDetectShortStoryFallback() {
        assertThat(StoryImportClassifier.detectType("我第一次意识到不对，是在那通电话之后。"))
                .isEqualTo("short_story");
    }

    @Test
    @DisplayName("超过阈值文本默认识别为长篇小说")
    void shouldDetectLongNovelFallback() {
        String longText = "故事".repeat(10_001);

        assertThat(StoryImportClassifier.detectType(longText)).isEqualTo("long_novel");
    }

    private static String sample(String filename) throws IOException {
        try (var input = StoryImportClassifierTest.class.getResourceAsStream("/story-samples/" + filename)) {
            assertThat(input).as("sample resource " + filename).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
