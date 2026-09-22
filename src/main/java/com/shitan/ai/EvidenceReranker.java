package com.shitan.ai;

import java.util.List;

/**
 * 对多路粗召回候选做第二阶段精排。
 */
public interface EvidenceReranker {

    /**
     * 按问题相关性重新排序并最多返回 topN 条。
     *
     * @param question   用户问题
     * @param candidates RRF 已截断的候选池
     * @param topN       最终上下文上限
     * @return 精排结果；无法调用时由实现决定如何降级
     */
    List<RetrievedEvidence> rerank(
            String question,
            List<RetrievedEvidence> candidates,
            int topN
    );
}
