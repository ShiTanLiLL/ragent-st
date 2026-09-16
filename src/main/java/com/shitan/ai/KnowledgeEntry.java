package com.shitan.ai;

import java.util.List;

/**
 * 一条可以参与当前关键词匹配的内存知识。
 */
public record KnowledgeEntry(
        String title,
        String content,
        List<String> keywords
) {
}
