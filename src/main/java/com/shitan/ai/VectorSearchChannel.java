package com.shitan.ai;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 语义向量召回通道：适合同义表达和自然语言描述。
 */
@Component
public class VectorSearchChannel implements SearchChannel {

    private final BailianClient bailianClient;
    private final KnowledgeRepository knowledgeRepository;

    /**
     * 保存 Embedding 客户端和 pgvector 仓库。
     */
    public VectorSearchChannel(BailianClient bailianClient, KnowledgeRepository knowledgeRepository) {
        this.bailianClient = bailianClient;
        this.knowledgeRepository = knowledgeRepository;
    }

    @Override
    public String name() {
        return "vector";
    }

    /**
     * 先把问题向量化，再在第 14 课选定的知识库作用域内取 Top-N。
     */
    @Override
    public SearchChannelResult search(SearchQuery query) throws Exception {
        long startedAt = System.nanoTime();
        double[] vector = bailianClient.createEmbedding(query.question());
        List<RetrievedEvidence> candidates = knowledgeRepository.searchVectorCandidates(
                query.knowledgeBaseIds(),
                vector,
                query.limit()
        );
        return new SearchChannelResult(name(), candidates, elapsedMillis(startedAt));
    }

    /**
     * 把纳秒起点转换为本通道可观察的毫秒耗时。
     */
    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
