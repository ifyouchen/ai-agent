package com.example.aiagent.story;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Slf4j
public class StoryExportService {

    public String buildProjectMarkdown(Map<String, Object> project, Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(project.get("title")).append("\n\n");
        String description = string(project.get("description"), "");
        if (!description.isBlank()) {
            sb.append("> ").append(description).append("\n\n");
        }
        if (Boolean.TRUE.equals(payload.get("includeAssets"))) {
            appendProjectAssets(sb, objectMap(project.get("assets")));
        }
        for (Map<String, Object> chapter : mapList(project.get("chapters"))) {
            String title = string(chapter.get("title"), "第" + chapter.get("chapterNo") + "章");
            sb.append("## ").append(title).append("\n\n");
            sb.append(contentWithoutDuplicateHeading(title, string(chapter.get("content"), ""))).append("\n\n");
        }
        return sb.toString();
    }

    public String buildDraftMarkdown(Map<String, Object> draft, Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(draft.get("title")).append("\n\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> episodes = filterExportEpisodes((List<Map<String, Object>>) draft.get("episodes"), payload);
        if (Boolean.TRUE.equals(payload.get("includeAdaptationPlan"))) {
            appendAdaptationPlan(sb, draft);
        }
        if (Boolean.TRUE.equals(payload.get("includeCharacterTable"))) {
            appendCharacterTable(sb, episodes);
        }
        if (Boolean.TRUE.equals(payload.get("includeSceneDirectory"))) {
            appendSceneDirectory(sb, episodes);
        }
        for (Map<String, Object> episode : episodes) {
            sb.append("## 第").append(episode.get("episodeNo")).append("集\n");
            sb.append("预计时长：").append(episode.get("estimatedDuration")).append("\n");
            sb.append("核心爽点：").append(episode.get("coreHook")).append("\n");
            sb.append("本集冲突：").append(episode.get("mainConflict")).append("\n");
            sb.append("结尾钩子：").append(episode.get("endingHook")).append("\n\n");
            for (Map<String, Object> scene : mapList(episode.get("scenes"))) {
                sb.append("### 【第").append(scene.get("sceneNo")).append("场】").append(scene.get("sceneTitle")).append("\n");
                sb.append("场景：").append(scene.get("location")).append("\n");
                sb.append("人物：").append(scene.get("characters")).append("\n");
                sb.append("本场功能：").append(scene.get("sceneFunction")).append("\n");
                sb.append("画面：").append(scene.get("visualAction")).append("\n");
                sb.append("旁白：").append(scene.get("narration")).append("\n");
                sb.append("对白：\n").append(scene.get("dialogue")).append("\n");
                sb.append("表演/镜头：").append(scene.get("performanceCameraNote")).append("\n");
                sb.append("钩子：").append(scene.get("hook")).append("\n\n");
            }
        }
        if (Boolean.TRUE.equals(payload.get("includeQualityReport")) && draft.get("qualityReport") != null) {
            sb.append("## 质量报告\n").append(draft.get("qualityReport")).append("\n");
        }
        return sb.toString();
    }

    public StoryExportFile buildFile(Object title, String markdown, String format) throws IOException {
        String normalized = normalizeFormat(format);
        String filename = filename(title, normalized);
        return switch (normalized) {
            case "docx" -> new StoryExportFile(
                    filename,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    buildDocx(markdown)
            );
            case "html" -> new StoryExportFile(
                    filename,
                    "text/html; charset=UTF-8",
                    buildHtmlDocument(string(title, "导出内容"), markdown).getBytes(StandardCharsets.UTF_8)
            );
            case "pdf" -> new StoryExportFile(
                    filename,
                    "application/pdf",
                    buildPdf(string(title, "导出内容"), markdown)
            );
            case "txt" -> new StoryExportFile(
                    filename,
                    "text/plain; charset=UTF-8",
                    markdownToPlainText(markdown).getBytes(StandardCharsets.UTF_8)
            );
            default -> new StoryExportFile(
                    filename,
                    "text/markdown; charset=UTF-8",
                    markdown.getBytes(StandardCharsets.UTF_8)
            );
        };
    }

    public String previewContent(Object title, String markdown, String format) {
        String normalized = normalizeFormat(format);
        if ("html".equals(normalized)) return buildHtmlDocument(string(title, "导出内容"), markdown);
        if ("txt".equals(normalized)) return markdownToPlainText(markdown);
        return markdown;
    }

    public String normalizeFormat(String format) {
        String value = string(format, "md").trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "markdown" -> "md";
            case "htm" -> "html";
            case "text" -> "txt";
            case "word", "doc" -> "docx";
            case "md", "html", "pdf", "txt", "docx" -> value;
            default -> "md";
        };
    }

    public String filename(Object title, String format) {
        return safeFilename(string(title, "导出内容")) + "." + extension(format);
    }

    private List<Map<String, Object>> filterExportEpisodes(List<Map<String, Object>> episodes, Map<String, Object> payload) {
        if (episodes == null) return List.of();
        String scope = string(payload.get("scope"), "all");
        Integer episodeNo = payload.get("episodeNo") == null ? null : intValue(payload.get("episodeNo"), -1);
        Long sceneId = nullableLong(payload.get("sceneId"));
        if ("episode".equals(scope) && episodeNo != null && episodeNo > 0) {
            return episodes.stream()
                    .filter(ep -> intValue(ep.get("episodeNo"), -1) == episodeNo)
                    .toList();
        }
        if ("scene".equals(scope) && sceneId != null) {
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> episode : episodes) {
                List<Map<String, Object>> scenes = mapList(episode.get("scenes")).stream()
                        .filter(scene -> sceneId.equals(nullableLong(scene.get("id"))))
                        .toList();
                if (!scenes.isEmpty()) {
                    Map<String, Object> copy = new LinkedHashMap<>(episode);
                    copy.put("scenes", scenes);
                    filtered.add(copy);
                }
            }
            return filtered;
        }
        return episodes;
    }

    private void appendAdaptationPlan(StringBuilder sb, Map<String, Object> draft) {
        Map<String, Object> plan = objectMap(draft.get("adaptationPlan"));
        if (plan.isEmpty()) return;
        sb.append("## 改编方案\n");
        appendPlanLine(sb, "故事核", plan.get("storyCore"));
        appendPlanLine(sb, "人物关系", plan.get("characterRelations"));
        appendPlanLine(sb, "情节取舍", plan.get("plotSelection"));
        appendPlanLine(sb, "改编策略", plan.get("strategy"));
        sb.append("\n");
    }

    private void appendPlanLine(StringBuilder sb, String label, Object value) {
        String text = string(value, "");
        if (!text.isBlank()) sb.append(label).append("：").append(text).append("\n");
    }

    private void appendCharacterTable(StringBuilder sb, List<Map<String, Object>> episodes) {
        Map<String, Integer> characters = new LinkedHashMap<>();
        for (Map<String, Object> episode : episodes) {
            for (Map<String, Object> scene : mapList(episode.get("scenes"))) {
                for (String name : splitCharacters(string(scene.get("characters"), ""))) {
                    characters.merge(name, 1, Integer::sum);
                }
            }
        }
        if (characters.isEmpty()) return;
        sb.append("## 人物表\n");
        sb.append("| 人物 | 出现场次 | 备注 |\n|---|---:|---|\n");
        characters.forEach((name, count) -> sb.append("| ").append(name).append(" | ").append(count).append(" | 由分场人物字段自动汇总 |\n"));
        sb.append("\n");
    }

    private void appendSceneDirectory(StringBuilder sb, List<Map<String, Object>> episodes) {
        sb.append("## 场次目录\n");
        for (Map<String, Object> episode : episodes) {
            sb.append("- 第").append(episode.get("episodeNo")).append("集：").append(episode.get("title")).append("\n");
            for (Map<String, Object> scene : mapList(episode.get("scenes"))) {
                sb.append("  - 第").append(scene.get("sceneNo")).append("场：")
                        .append(scene.get("sceneTitle")).append("｜")
                        .append(scene.get("location")).append("｜")
                        .append(scene.get("sceneFunction")).append("\n");
            }
        }
        sb.append("\n");
    }

    private List<String> splitCharacters(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String[] parts = raw.split("[、,，/／\\s]+");
        List<String> names = new ArrayList<>();
        for (String part : parts) {
            String name = part.trim();
            if (!name.isBlank() && !names.contains(name)) names.add(name);
        }
        return names;
    }

    private void appendProjectAssets(StringBuilder sb, Map<String, Object> assets) {
        if (assets == null || assets.isEmpty()) return;
        appendProjectAsset(sb, "设定", assets.get("setting"));
        appendProjectAsset(sb, "人物", assets.get("characters"));
        appendProjectAsset(sb, "大纲", assets.get("outline"));
    }

    private void appendProjectAsset(StringBuilder sb, String label, Object value) {
        String text = string(value, "");
        if (!text.isBlank()) {
            sb.append("## ").append(label).append("\n\n").append(text).append("\n\n");
        }
    }

    private String contentWithoutDuplicateHeading(String title, String content) {
        if (title == null || title.isBlank() || content == null || content.isBlank()) return content;
        String stripped = content.stripLeading();
        int lineEnd = stripped.indexOf('\n');
        String firstLine = lineEnd >= 0 ? stripped.substring(0, lineEnd) : stripped;
        if (!firstLine.trim().equals(title.trim())) return content;
        return lineEnd >= 0 ? stripped.substring(lineEnd + 1).stripLeading() : "";
    }

    private String buildHtmlDocument(String title, String markdown) {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                  <style>
                    body { margin: 0; background: #f5f7fb; color: #172033; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif; line-height: 1.85; }
                    main { max-width: 820px; margin: 48px auto; padding: 44px 52px; background: #fff; border: 1px solid #e8edf6; border-radius: 8px; box-shadow: 0 18px 50px rgba(20,31,56,.08); }
                    h1 { margin: 0 0 28px; font-size: 32px; line-height: 1.25; }
                    h2 { margin: 34px 0 14px; padding-top: 18px; border-top: 1px solid #edf1f7; font-size: 22px; }
                    h3 { margin: 26px 0 10px; font-size: 18px; }
                    p { margin: 0 0 14px; white-space: pre-wrap; }
                    blockquote { margin: 0 0 22px; padding: 12px 16px; color: #5b667a; background: #f7f9ff; border-left: 4px solid #4f67f5; }
                    table { width: 100%%; border-collapse: collapse; margin: 16px 0 24px; }
                    th, td { border: 1px solid #e4e9f2; padding: 8px 10px; text-align: left; }
                  </style>
                </head>
                <body><main>
                %s
                </main></body>
                </html>
                """.formatted(htmlEscape(title), markdownToHtml(markdown));
    }

    private String markdownToHtml(String markdown) {
        StringBuilder html = new StringBuilder();
        boolean inTable = false;
        for (String rawLine : string(markdown, "").split("\\R")) {
            String line = rawLine.stripTrailing();
            if (line.isBlank()) {
                if (inTable) {
                    html.append("</tbody></table>\n");
                    inTable = false;
                }
                continue;
            }
            if (line.startsWith("|") && line.endsWith("|")) {
                if (line.matches("\\|\\s*[-:]+.*")) continue;
                String[] cells = line.substring(1, line.length() - 1).split("\\|");
                if (!inTable) {
                    html.append("<table><tbody>\n");
                    inTable = true;
                }
                html.append("<tr>");
                for (String cell : cells) html.append("<td>").append(htmlEscape(cell.trim())).append("</td>");
                html.append("</tr>\n");
                continue;
            }
            if (inTable) {
                html.append("</tbody></table>\n");
                inTable = false;
            }
            if (line.startsWith("### ")) html.append("<h3>").append(htmlEscape(line.substring(4))).append("</h3>\n");
            else if (line.startsWith("## ")) html.append("<h2>").append(htmlEscape(line.substring(3))).append("</h2>\n");
            else if (line.startsWith("# ")) html.append("<h1>").append(htmlEscape(line.substring(2))).append("</h1>\n");
            else if (line.startsWith("> ")) html.append("<blockquote>").append(htmlEscape(line.substring(2))).append("</blockquote>\n");
            else html.append("<p>").append(htmlEscape(line)).append("</p>\n");
        }
        if (inTable) html.append("</tbody></table>\n");
        return html.toString();
    }

    private String markdownToPlainText(String markdown) {
        StringBuilder text = new StringBuilder();
        for (String rawLine : string(markdown, "").split("\\R")) {
            String line = rawLine
                    .replaceFirst("^#{1,6}\\s+", "")
                    .replaceFirst("^>\\s+", "")
                    .replace("**", "")
                    .replace("`", "");
            if (line.matches("\\|\\s*[-:]+.*")) continue;
            if (line.startsWith("|") && line.endsWith("|")) {
                line = line.substring(1, line.length() - 1).replace("|", "\t");
            }
            text.append(line).append("\n");
        }
        return text.toString();
    }

    private byte[] buildDocx(String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            putZipEntry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """.stripLeading());
            putZipEntry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>
                    """.stripLeading());
            putZipEntry(zip, "word/document.xml", wordDocumentXml(content));
        }
        return out.toByteArray();
    }

    private byte[] buildPdf(String title, String markdown) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFont font = loadPdfFont(document);
            float fontSize = 11.5f;
            float titleSize = 18f;
            float leading = 18f;
            float margin = 54f;
            PDRectangle pageSize = PDRectangle.A4;
            float width = pageSize.getWidth() - margin * 2;
            PDPage page = new PDPage(pageSize);
            document.addPage(page);
            PDPageContentStream stream = new PDPageContentStream(document, page);
            float y = pageSize.getHeight() - margin;

            stream.beginText();
            stream.setFont(font, titleSize);
            stream.setLeading(leading + 4);
            stream.newLineAtOffset(margin, y);
            stream.showText(pdfText(font, title));
            stream.newLine();
            y -= leading + 10;
            stream.setFont(font, fontSize);
            stream.setLeading(leading);

            for (String rawLine : markdownToPlainText(markdown).split("\\R")) {
                List<String> lines = wrapPdfLine(font, pdfText(font, rawLine), fontSize, width);
                if (lines.isEmpty()) lines = List.of("");
                for (String line : lines) {
                    if (y < margin + leading) {
                        stream.endText();
                        stream.close();
                        page = new PDPage(pageSize);
                        document.addPage(page);
                        stream = new PDPageContentStream(document, page);
                        y = pageSize.getHeight() - margin;
                        stream.beginText();
                        stream.setFont(font, fontSize);
                        stream.setLeading(leading);
                        stream.newLineAtOffset(margin, y);
                    }
                    stream.showText(line);
                    stream.newLine();
                    y -= leading;
                }
            }
            stream.endText();
            stream.close();
            document.save(out);
            return out.toByteArray();
        }
    }

    private PDFont loadPdfFont(PDDocument document) {
        String[] candidates = {
                "C:/Windows/Fonts/msyh.ttc",
                "C:/Windows/Fonts/msyh.ttf",
                "C:/Windows/Fonts/simhei.ttf",
                "C:/Windows/Fonts/simsun.ttc",
                "C:/Windows/Fonts/simsun.ttf",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.otf",
                "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
                "/System/Library/Fonts/PingFang.ttc"
        };
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (!file.isFile()) continue;
            try {
                return PDType0Font.load(document, file);
            } catch (Exception e) {
                log.debug("加载 PDF 字体失败 path={} message={}", candidate, e.getMessage());
            }
        }
        log.warn("未找到可用中文 PDF 字体，导出 PDF 可能无法显示中文");
        return PDType1Font.HELVETICA;
    }

    private List<String> wrapPdfLine(PDFont font, String line, float fontSize, float maxWidth) {
        if (line == null || line.isBlank()) return List.of("");
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int offset = 0; offset < line.length(); ) {
            int codePoint = line.codePointAt(offset);
            String ch = new String(Character.toChars(codePoint));
            String candidate = current + ch;
            if (current.length() > 0 && pdfTextWidth(font, candidate, fontSize) > maxWidth) {
                lines.add(current.toString());
                current = new StringBuilder(ch);
            } else {
                current.append(ch);
            }
            offset += Character.charCount(codePoint);
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    private float pdfTextWidth(PDFont font, String text, float fontSize) {
        try {
            return font.getStringWidth(text) / 1000f * fontSize;
        } catch (IOException e) {
            return text.length() * fontSize;
        }
    }

    private String pdfText(PDFont font, String text) {
        String value = string(text, "");
        if (!(font instanceof PDType1Font)) return value;
        return value.replaceAll("[^\\x20-\\x7E]", "?");
    }

    private void putZipEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String wordDocumentXml(String content) {
        StringBuilder body = new StringBuilder();
        for (String line : content.split("\\R", -1)) {
            appendWordParagraph(body, line);
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                """.stripLeading() + body + """
                    <w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>
                  </w:body>
                </w:document>
                """;
    }

    private void appendWordParagraph(StringBuilder body, String rawLine) {
        String line = rawLine == null ? "" : rawLine.stripTrailing();
        if (line.isBlank()) {
            body.append("<w:p/>");
            return;
        }

        int headingLevel = markdownHeadingLevel(line);
        if (headingLevel > 0) {
            String text = line.substring(headingLevel).stripLeading();
            int size = switch (headingLevel) {
                case 1 -> 34;
                case 2 -> 28;
                case 3 -> 24;
                default -> 22;
            };
            appendWordTextParagraph(body, text, true, false, size, 180, 80, 0);
            return;
        }

        if (line.startsWith("> ")) {
            appendWordTextParagraph(body, line.substring(2).stripLeading(), false, true, 22, 80, 80, 360);
            return;
        }
        if (line.matches("^[-*+]\\s+.*")) {
            appendWordTextParagraph(body, "• " + line.substring(2).stripLeading(), false, false, 22, 40, 40, 240);
            return;
        }
        if (line.matches("^\\d+[.)]\\s+.*")) {
            appendWordTextParagraph(body, line, false, false, 22, 40, 40, 240);
            return;
        }
        appendWordTextParagraph(body, line, false, false, 22, 40, 40, 0);
    }

    private int markdownHeadingLevel(String line) {
        int count = 0;
        while (count < line.length() && count < 6 && line.charAt(count) == '#') count++;
        return count > 0 && count < line.length() && Character.isWhitespace(line.charAt(count)) ? count : 0;
    }

    private void appendWordTextParagraph(StringBuilder body,
                                         String text,
                                         boolean bold,
                                         boolean italic,
                                         int size,
                                         int before,
                                         int after,
                                         int leftIndent) {
        body.append("<w:p><w:pPr><w:spacing w:before=\"")
                .append(before)
                .append("\" w:after=\"")
                .append(after)
                .append("\"/>");
        if (leftIndent > 0) {
            body.append("<w:ind w:left=\"").append(leftIndent).append("\"/>");
        }
        body.append("</w:pPr>");
        appendWordRuns(body, text, bold, italic, size);
        body.append("</w:p>");
    }

    private void appendWordRuns(StringBuilder body, String text, boolean baseBold, boolean baseItalic, int size) {
        String value = string(text, "");
        int index = 0;
        boolean inlineBold = false;
        while (index < value.length()) {
            int marker = value.indexOf("**", index);
            if (marker < 0) {
                appendWordRun(body, value.substring(index), baseBold || inlineBold, baseItalic, size);
                break;
            }
            if (marker > index) {
                appendWordRun(body, value.substring(index, marker), baseBold || inlineBold, baseItalic, size);
            }
            inlineBold = !inlineBold;
            index = marker + 2;
        }
    }

    private void appendWordRun(StringBuilder body, String text, boolean bold, boolean italic, int size) {
        if (text == null || text.isEmpty()) return;
        body.append("<w:r><w:rPr><w:rFonts w:ascii=\"Calibri\" w:eastAsia=\"Microsoft YaHei\" w:hAnsi=\"Calibri\"/>")
                .append("<w:sz w:val=\"").append(size).append("\"/>")
                .append("<w:szCs w:val=\"").append(size).append("\"/>");
        if (bold) body.append("<w:b/>");
        if (italic) body.append("<w:i/>");
        body.append("</w:rPr><w:t xml:space=\"preserve\">")
                .append(xmlEscape(text))
                .append("</w:t></w:r>");
    }

    private String extension(String format) {
        return switch (normalizeFormat(format)) {
            case "docx" -> "docx";
            case "html" -> "html";
            case "pdf" -> "pdf";
            case "txt" -> "txt";
            default -> "md";
        };
    }

    private String htmlEscape(String value) {
        return string(value, "")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String xmlEscape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String safeFilename(String filename) {
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object raw) {
        if (raw instanceof Map<?, ?> map) return (Map<String, Object>) map;
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object raw) {
        if (raw instanceof List<?> list) return (List<Map<String, Object>>) (List<?>) list;
        return List.of();
    }

    private Integer intValue(Object raw, int fallback) {
        if (raw instanceof Number n) return n.intValue();
        try {
            return raw == null ? fallback : Integer.parseInt(String.valueOf(raw));
        } catch (Exception e) {
            return fallback;
        }
    }

    private Long nullableLong(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (Exception e) {
            return null;
        }
    }

    private String string(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }
}
