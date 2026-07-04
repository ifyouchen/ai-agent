package com.example.aiagent.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class CodeBlockPostProcessorTest {

    @Test
    @DisplayName("合法 Java 源码会使用 formatter 统一排版")
    void process_parseableJava_formatsSource() {
        CodeBlockPostProcessor processor = newProcessor(() -> null);
        String source = """
                ```java
                public class Main{public static void main(String[] args){if(true){return;}}}
                ```
                """;

        String result = processor.process(source, "deepseek-v4-pro");

        assertThat(result)
                .contains("```java")
                .contains("public class Main")
                .contains("public static void main(String[] args)")
                .contains("if (true)");
    }

    @Test
    @DisplayName("坏 token Java 会触发一次 AI 修复并再次格式化")
    void process_brokenJava_repairsThenFormats() {
        CodeBlockPostProcessor processor = newProcessor(() ->
                (brokenCode, modelName) -> Optional.of("""
                        ```java
                        public class Main{public boolean ok(){return false;}}
                        ```
                        """));
        String source = """
                ```java
                public class Main extends JFram e {
                private static final int BOARD _PIXEL = 1;
                }
                ```
                """;

        String result = processor.process(source, "deepseek-v4-pro");

        assertThat(result)
                .contains("public class Main")
                .contains("public boolean ok()")
                .contains("return false;")
                .doesNotContain("JFram e")
                .doesNotContain("BOARD _PIXEL");
    }

    @Test
    @DisplayName("坏 token Java 修复失败时保留原始代码")
    void process_brokenJava_keepsOriginalWhenRepairFails() {
        CodeBlockPostProcessor processor = newProcessor(() ->
                (brokenCode, modelName) -> Optional.of("public class Main extends JFram e {}"));
        String source = """
                ```java
                public class Main extends JFram e {}
                ```
                """;

        String result = processor.process(source, "deepseek-v4-pro");

        assertThat(result).contains("public class Main extends JFram e {}");
    }

    @Test
    @DisplayName("整段 Java 源码缺少 fenced block 时会自动包装")
    void process_plainJavaSource_wrapsAsFencedBlock() {
        CodeBlockPostProcessor processor = newProcessor(() -> null);
        String source = """
                package com.example;

                import java.util.List;

                public class Main {
                public List<String> names(){return List.of("a");}
                }
                """;

        String result = processor.process(source, "deepseek-v4-pro");

        assertThat(result)
                .startsWith("```java")
                .contains("package com.example;")
                .contains("public List<String> names()")
                .endsWith("```");
    }

    private CodeBlockPostProcessor newProcessor(Supplier<CodeBlockRepairClient> supplier) {
        return new CodeBlockPostProcessor(new ObjectProvider<>() {
            @Override
            public CodeBlockRepairClient getObject(Object... args) {
                return supplier.get();
            }

            @Override
            public CodeBlockRepairClient getIfAvailable() {
                return supplier.get();
            }

            @Override
            public CodeBlockRepairClient getIfUnique() {
                return supplier.get();
            }

            @Override
            public CodeBlockRepairClient getObject() {
                return supplier.get();
            }
        });
    }
}
