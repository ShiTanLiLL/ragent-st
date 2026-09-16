package com.shitan.ai;

/**
 * 一次知识问答的正文和来源；未命中知识时来源为 null。
 */
public record KnowledgeAnswer(
        String content,
        String sourceTitle
) {
}
