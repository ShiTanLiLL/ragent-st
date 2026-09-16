package com.shitan.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取当前课程约定的简单 Markdown 知识文件，并把每个段落转换成内存知识。
 */
public class KnowledgeFileLoader {

    public List<KnowledgeEntry> load(Path file) throws IOException {
        String document = Files.readString(file, StandardCharsets.UTF_8);

        // 空文件没有可检索内容，直接返回空列表交给现有兜底逻辑处理。
        if (document.isBlank()) {
            return List.of();
        }

        // 一个或多个空行表示知识边界，每个知识块仍保持在文件中的原始顺序。
        String[] blocks = document.strip().split("\\R\\s*\\R");
        List<KnowledgeEntry> knowledgeEntries = new ArrayList<>();

        for (String block : blocks) {
            knowledgeEntries.add(parseBlock(block));
        }

        return knowledgeEntries;
    }

    private KnowledgeEntry parseBlock(String block) {
        String[] lines = block.split("\\R");

        // 当前文件格式固定为标题、关键词、正文三部分，缺少任何一部分都无法检索。
        if (lines.length < 3 || !lines[1].strip().startsWith("关键词：")) {
            throw new IllegalArgumentException("知识块必须依次包含标题、关键词和正文");
        }

        String title = lines[0].strip().replaceFirst("^#+\\s*", "");
        List<String> keywords = parseKeywords(lines[1]);
        String content = joinContentLines(lines);

        return new KnowledgeEntry(title, content, keywords);
    }

    private List<String> parseKeywords(String keywordLine) {
        String keywordText = keywordLine.strip().substring("关键词：".length());
        String[] parts = keywordText.split("[、,，]");
        List<String> keywords = new ArrayList<>();

        for (String part : parts) {
            String keyword = part.strip();
            if (!keyword.isEmpty()) {
                keywords.add(keyword);
            }
        }

        return keywords;
    }

    private String joinContentLines(String[] lines) {
        StringBuilder content = new StringBuilder();

        for (int index = 2; index < lines.length; index++) {
            if (content.length() > 0) {
                content.append('\n');
            }
            content.append(lines[index].strip());
        }

        return content.toString();
    }
}
