package com.shitan.ai;

/**
 * 带有模型改写问题的 RAG 结果，便于 HTTP 层和测试观察追问是如何被补全的。
 *
 * @param answer            最终回答和证据来源
 * @param rewrittenQuestion 交给 Embedding 的独立问题
 */
public record ContextualKnowledgeAnswer(
        KnowledgeAnswer answer,
        String rewrittenQuestion
) {
}
