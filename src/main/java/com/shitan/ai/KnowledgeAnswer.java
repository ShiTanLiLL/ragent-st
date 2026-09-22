package com.shitan.ai;

import java.util.List;

/**
 * 一次知识问答的正文和来源；未命中知识时来源为 null。
 *
 * @param content 生成的回答正文
 * @param sourceTitle 最主要证据标题
 * @param evidence 本次送入答案模型的有界证据及检索分数
 */
public record KnowledgeAnswer(
        String content,
        String sourceTitle,
        List<RetrievedEvidence> evidence
) {

    /**
     * 兼容前面课程只关心正文和来源的构造方式。
     */
    public KnowledgeAnswer(String content, String sourceTitle) {
        this(content, sourceTitle, List.of());
    }

    /**
     * 保证证据列表是只读快照。
     */
    public KnowledgeAnswer {
        evidence = List.copyOf(evidence);
    }
}
