package com.shitan.ai;

import java.util.ArrayList;
import java.util.List;

public class VectorSearch {

    public List<KnowledgeEntry> search(
            double[] questionVector,
            List<EmbeddedKnowledge> candidates,
            int topK
    ) {
        if (topK <= 0) {
            return List.of();
        }

        List<ScoredKnowledge> scoredKnowledge = new ArrayList<>();

        for (EmbeddedKnowledge candidate : candidates) {
            double similarity = cosineSimilarity(questionVector, candidate.vector());
            scoredKnowledge.add(new ScoredKnowledge(candidate.knowledge(), similarity));
        }

        // 相似度越大越接近问题，因此按分数从大到小排列。
        scoredKnowledge.sort((left, right) ->
                Double.compare(right.similarity(), left.similarity())
        );

        int resultSize = Math.min(topK, scoredKnowledge.size());
        List<KnowledgeEntry> results = new ArrayList<>();

        for (int index = 0; index < resultSize; index++) {
            results.add(scoredKnowledge.get(index).knowledge());
        }

        return results;
    }

    public double cosineSimilarity(double[] left, double[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("参与比较的向量维度必须相同");
        }

        double dotProduct = 0.0;
        double leftLengthSquared = 0.0;
        double rightLengthSquared = 0.0;

        for (int index = 0; index < left.length; index++) {
            dotProduct += left[index] * right[index];
            leftLengthSquared += left[index] * left[index];
            rightLengthSquared += right[index] * right[index];
        }

        // 全零向量没有方向，分母也会是 0，不能计算余弦相似度。
        if (leftLengthSquared == 0.0 || rightLengthSquared == 0.0) {
            throw new IllegalArgumentException("向量不能全部为 0");
        }

        return dotProduct / (Math.sqrt(leftLengthSquared) * Math.sqrt(rightLengthSquared));
    }

    /**
     * 排序期间临时保存知识和相似度，不暴露为外部业务对象。
     */
    private record ScoredKnowledge(
            KnowledgeEntry knowledge,
            double similarity
    ) {
    }
}
