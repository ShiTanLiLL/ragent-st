package com.shitan.ai;

/**
 * 一条知识及其向量表示；本课向量由固定测试数据提供。
 */
public record EmbeddedKnowledge(
        KnowledgeEntry knowledge,
        double[] vector
) {
}
