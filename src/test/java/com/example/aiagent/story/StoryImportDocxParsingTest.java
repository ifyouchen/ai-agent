package com.example.aiagent.story;

import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StoryImport DOCX parsing - 小说导入 DOCX 解析")
class StoryImportDocxParsingTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("DOCX 改编方案样例可解析并识别")
    void shouldParseAndDetectAdaptationDocx() throws IOException {
        String text = sample("adaptation-equivalent.txt");
        Path docx = createDocx("改编-上岸 (1).docx", text);

        String parsed = parseDocx(docx);

        assertThat(parsed).contains("改编方案", "故事核", "题材迁移");
        assertThat(StoryImportClassifier.detectType(parsed)).isEqualTo("adaptation");
    }

    @Test
    @DisplayName("DOCX 短剧分场稿样例可解析并识别")
    void shouldParseAndDetectShortDramaDocx() throws IOException {
        String text = sample("short-drama-equivalent.txt");
        Path docx = createDocx("剧本 (1).docx", text);

        String parsed = parseDocx(docx);

        assertThat(parsed).contains("第1集", "场景：", "对白：", "钩子：");
        assertThat(StoryImportClassifier.detectType(parsed)).isEqualTo("short_drama");
    }

    private Path createDocx(String filename, String text) throws IOException {
        Path docx = tempDir.resolve(filename);
        try (OutputStream output = Files.newOutputStream(docx);
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """.stripLeading());
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>
                    """.stripLeading());
            put(zip, "word/document.xml", documentXml(text));
        }
        return docx;
    }

    private static String parseDocx(Path docx) {
        return FileSystemDocumentLoader.loadDocument(docx, new ApacheTikaDocumentParser()).text();
    }

    private static String documentXml(String text) {
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                    .append(escapeXml(line))
                    .append("</w:t></w:r></w:p>");
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    %s
                    <w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>
                  </w:body>
                </w:document>
                """.formatted(body).stripLeading();
    }

    private static String sample(String filename) throws IOException {
        try (var input = StoryImportDocxParsingTest.class.getResourceAsStream("/story-samples/" + filename)) {
            assertThat(input).as("sample resource " + filename).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
