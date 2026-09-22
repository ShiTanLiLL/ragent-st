package com.shitan.ai;

import java.util.List;

/**
 * 一条候选证据在多路召回、RRF 和精排之间流动时携带的数据。
 *
 * @param id             Chunk 唯一编号，用于跨通道去重
 * @param title          证据标题
 * @param content        证据正文
 * @param channels       召回到它的通道名称
 * @param vectorScore    pgvector 余弦相似度，仅向量通道提供
 * @param keywordScore   精确词命中分，仅关键词通道提供
 * @param rrfScore       按通道名次累计的 RRF 分
 * @param rerankScore    精排模型给出的 0～1 相关性分；降级时为空
 */
public record RetrievedEvidence(
        String id,
        String title,
        String content,
        List<String> channels,
        Double vectorScore,
        Double keywordScore,
        double rrfScore,
        Double rerankScore
) {

    /**
     * 复制通道列表，避免后处理阶段外部修改候选归因。
     */
    public RetrievedEvidence {
        channels = List.copyOf(channels);
    }

    /**
     * 创建向量通道刚召回的候选，此时还没有融合和精排分。
     */
    public static RetrievedEvidence fromVector(
            String id,
            String title,
            String content,
            double vectorScore
    ) {
        return new RetrievedEvidence(
                id, title, content, List.of("vector"), vectorScore, null, 0.0, null
        );
    }

    /**
     * 创建关键词通道刚召回的候选，此时还没有融合和精排分。
     */
    public static RetrievedEvidence fromKeyword(
            String id,
            String title,
            String content,
            double keywordScore
    ) {
        return new RetrievedEvidence(
                id, title, content, List.of("keyword"), null, keywordScore, 0.0, null
        );
    }

    /**
     * 合并同一 Chunk 的通道归因和原始分数，并写入统一 RRF 分。
     */
    public RetrievedEvidence fusedWith(RetrievedEvidence other, List<String> mergedChannels, double score) {
        return new RetrievedEvidence(
                id,
                title,
                content,
                mergedChannels,
                vectorScore != null ? vectorScore : other.vectorScore,
                keywordScore != null ? keywordScore : other.keywordScore,
                score,
                rerankScore
        );
    }

    /**
     * 保存 RRF 分但不改变原始通道分数。
     */
    public RetrievedEvidence withRrfScore(double score) {
        return new RetrievedEvidence(
                id, title, content, channels, vectorScore, keywordScore, score, rerankScore
        );
    }

    /**
     * 保存精排模型给出的最终相关性分。
     */
    public RetrievedEvidence withRerankScore(double score) {
        return new RetrievedEvidence(
                id, title, content, channels, vectorScore, keywordScore, rrfScore, score
        );
    }

    /**
     * 转成答案生成模型已经认识的证据类型。
     */
    public KnowledgeEntry toKnowledgeEntry() {
        return new KnowledgeEntry(title, content, List.of());
    }
}
