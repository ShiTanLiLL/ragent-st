package com.shitan.ai;

import java.util.List;

/**
 * 一条尚未生成向量的知识片段，同时区分给人看的正文和交给 Embedding 的文本。
 */
public record KnowledgeEntry(
        String title,
        String content,
        List<String> keywords,
        String embeddingText
) {

    /**
     * 兼容前十课创建知识的写法；没有单独指定向量文本时，使用“标题 + 正文”。
     *
     * @param title    知识标题
     * @param content  展示和回答使用的正文
     * @param keywords 关键词
     */
    public KnowledgeEntry(String title, String content, List<String> keywords) {
        this(title, content, keywords, title + "\n" + content);
    }
}
