package com.shitan.ai;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 解析前十课的三段式纯文本，使旧知识文件在引入结构化 Markdown 后仍可继续上传。
 */
public class LegacyKnowledgeTextParser implements DocumentParser {

    private final KnowledgeFileLoader fileLoader = new KnowledgeFileLoader();

    /**
     * 旧格式只认领普通文本；Markdown MIME 由专门解析器处理。
     *
     * @param mimeType Tika 探测出的 MIME
     * @return MIME 为 text/plain 时返回 true
     */
    @Override
    public boolean supports(String mimeType) {
        return "text/plain".equals(mimeType);
    }

    /**
     * 复用旧加载器解析知识，再把每条知识包装成保留关键词的结构块。
     *
     * @param content    UTF-8 文件字节
     * @param mimeType   已确认的普通文本 MIME
     * @param sourceName 原文件名
     * @return 可以交给统一分块器处理的结构化文档
     */
    @Override
    public ParsedDocument parse(byte[] content, String mimeType, String sourceName) {
        List<DocumentBlock> blocks = fileLoader.loadText(
                        new String(content, StandardCharsets.UTF_8)
                ).stream()
                .map(entry -> (DocumentBlock) new DocumentBlock.LegacyKnowledge(
                        entry.title(),
                        entry.content(),
                        entry.keywords()
                ))
                .toList();
        return new ParsedDocument(mimeType, sourceName, blocks);
    }
}
