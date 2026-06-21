package com.example.aiagent.story;

import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StoryImportService {

    public StoryImportAnalysis analyzeText(String title, String content, String sourceName) {
        String safeContent = content == null ? "" : content;
        String detectedType = StoryImportClassifier.detectType(safeContent);
        List<StoryImportUnit> units = buildUnits(safeContent, detectedType);
        return new StoryImportAnalysis(
                blankToDefault(title, "导入作品"),
                blankToDefault(sourceName, "text"),
                safeContent,
                detectedType,
                StoryImportClassifier.wordCount(safeContent),
                units
        );
    }

    public StoryImportAnalysis analyzeFile(MultipartFile file, String title) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("导入文件不能为空");
        }
        String filename = cleanUploadFilename(file.getOriginalFilename());
        Path tempFile = Files.createTempFile("story-import-", "-" + safeFilename(filename));
        try {
            Files.copy(file.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            String content = parseUploadText(tempFile, filename);
            return analyzeText(blankToDefault(title, stripExtension(filename)), content, filename);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public Map<String, Object> preview(StoryImportAnalysis analysis) {
        List<Map<String, Object>> chapterPreview = new ArrayList<>();
        for (int i = 0; i < Math.min(analysis.units().size(), 20); i++) {
            StoryImportUnit unit = analysis.units().get(i);
            String content = unit.content().trim();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chapterNo", unit.chapterNo());
            item.put("title", unit.title());
            item.put("wordCount", unit.wordCount());
            item.put("preview", content.length() <= 120 ? content : content.substring(0, 120));
            chapterPreview.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", analysis.title());
        result.put("sourceName", analysis.sourceName());
        result.put("content", analysis.content());
        result.put("detectedType", analysis.detectedType());
        result.put("detectedTypeLabel", typeLabel(analysis.detectedType()));
        result.put("chapterCount", analysis.units().size());
        result.put("wordCount", analysis.wordCount());
        result.put("chapters", chapterPreview);
        result.put("truncated", analysis.units().size() > chapterPreview.size());
        return result;
    }

    private List<StoryImportUnit> buildUnits(String content, String detectedType) {
        List<String> chunks = splitImportUnits(content, detectedType);
        List<StoryImportUnit> units = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i).trim();
            units.add(new StoryImportUnit(
                    i + 1,
                    guessChapterTitle(chunk, i + 1),
                    chunk,
                    StoryImportClassifier.wordCount(chunk)
            ));
        }
        return units;
    }

    private String parseUploadText(Path filePath, String filename) throws IOException {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            try {
                return Files.readString(filePath);
            } catch (MalformedInputException e) {
                return Files.readString(filePath, Charset.forName("GBK"));
            }
        }
        dev.langchain4j.data.document.Document document = FileSystemDocumentLoader.loadDocument(
                filePath,
                new ApacheTikaDocumentParser()
        );
        return blankToDefault(document.text(), "");
    }

    private List<String> splitImportUnits(String content, String detectedType) {
        if ("short_drama".equals(detectedType)) {
            String[] parts = content.split("(?=第[一二三四五六七八九十百千万0-9]+集)");
            List<String> result = new ArrayList<>();
            for (String part : parts) if (!part.isBlank()) result.add(part);
            return result.isEmpty() ? List.of(content) : result;
        }
        return splitChapters(content);
    }

    private List<String> splitChapters(String content) {
        if (content == null || content.isBlank()) return List.of("");
        String[] parts = content.split("(?=第[一二三四五六七八九十百千万0-9]+[章节回])");
        List<String> result = new ArrayList<>();
        for (String part : parts) if (!part.isBlank()) result.add(part);
        return result.isEmpty() ? List.of(content) : result;
    }

    private String guessChapterTitle(String text, int index) {
        String firstLine = text.strip().split("\\R", 2)[0].trim();
        return firstLine.length() <= 40 ? firstLine : "第" + index + "章";
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String cleanUploadFilename(String filename) {
        String value = blankToDefault(filename, "导入作品").replace('\\', '/');
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private String safeFilename(String filename) {
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String typeLabel(String type) {
        return switch (type) {
            case "short_story" -> "短篇";
            case "adaptation" -> "改编";
            case "short_drama" -> "短剧";
            default -> "长篇";
        };
    }

    public record StoryImportAnalysis(
            String title,
            String sourceName,
            String content,
            String detectedType,
            int wordCount,
            List<StoryImportUnit> units
    ) {}

    public record StoryImportUnit(int chapterNo, String title, String content, int wordCount) {}
}
