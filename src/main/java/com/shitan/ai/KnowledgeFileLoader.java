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

    /**
     * 读取 UTF-8 文件，再复用字符串解析逻辑处理前十课的三段式知识格式。
     *
     * @param file 待读取的本地文件
     * @return 文件中按原顺序出现的知识
     * @throws IOException 文件读取失败
     */
    public List<KnowledgeEntry> load(Path file) throws IOException {
        String document = Files.readString(file, StandardCharsets.UTF_8);
        return loadText(document);
    }

    /**
     * 解析前十课约定的“标题、关键词、正文”文本；第 11 课的旧格式解析器复用它，
     * 避免同时维护两份相同的格式规则。
     *
     * @param document 已按 UTF-8 解码的完整文件正文
     * @return 每个空行知识块转换出的知识条目
     */
    public List<KnowledgeEntry> loadText(String document) {

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

    /**
     * 把一个三段式文本块转换成标题、正文和关键词。
     *
     * @param block 单个知识块的原始文本
     * @return 可参与后续分块和向量化的知识
     */
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

    /**
     * 去掉“关键词：”前缀，并兼容中文顿号与中英文逗号。
     *
     * @param keywordLine 文件中的关键词行
     * @return 已去空白和空项的关键词
     */
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

    /**
     * 把第三行开始的正文重新按换行拼接，不把标题和关键词混入正文。
     *
     * @param lines 当前知识块的全部行
     * @return 保留正文行顺序的文本
     */
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
