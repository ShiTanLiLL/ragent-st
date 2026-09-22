package com.shitan.ai;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 使用当前已经配置好的百炼 Chat 模型完成教学版精排；失败时保留 RRF 候选继续回答。
 */
@Service
public class BailianEvidenceReranker implements EvidenceReranker {

    private final BailianClient bailianClient;

    /**
     * 保存百炼协议客户端。
     */
    public BailianEvidenceReranker(BailianClient bailianClient) {
        this.bailianClient = bailianClient;
    }

    /**
     * 调用模型打分；网络、空响应或 JSON 格式异常都降级为没有精排分的原候选。
     */
    @Override
    public List<RetrievedEvidence> rerank(
            String question,
            List<RetrievedEvidence> candidates,
            int topN
    ) {
        try {
            List<RetrievedEvidence> reranked = bailianClient.rerank(question, candidates, topN);
            return reranked.isEmpty() ? candidates.stream().limit(topN).toList() : reranked;
        } catch (Exception ignored) {
            return candidates.stream().limit(topN).toList();
        }
    }
}
