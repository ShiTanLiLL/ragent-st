package com.shitan.ai;

import java.util.List;

/**
 * 文档解析后的结构块：它保留标题、段落和表格的区别，是解析器与分块器之间的中间数据。
 */
public sealed interface DocumentBlock
        permits DocumentBlock.Heading, DocumentBlock.Paragraph,
        DocumentBlock.Table, DocumentBlock.LegacyKnowledge {

    /**
     * Markdown 标题块；level 表示一到六级标题。
     */
    record Heading(int level, String text) implements DocumentBlock {
    }

    /**
     * Markdown 普通段落块；行内强调等标记已经还原为可检索文字。
     */
    record Paragraph(String text) implements DocumentBlock {
    }

    /**
     * GFM 表格块；表头和每一行保持列的位置关系，不压成一段无结构文字。
     */
    record Table(List<String> headers, List<List<String>> rows) implements DocumentBlock {
    }

    /**
     * 前十课三段式文本中的完整知识块，用来保留显式填写的标题和关键词。
     */
    record LegacyKnowledge(String title, String text, List<String> keywords)
            implements DocumentBlock {
    }
}
