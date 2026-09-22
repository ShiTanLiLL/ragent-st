package com.shitan.ai;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 编排“并行召回 → 去重 → RRF → 候选截断 → 精排 → 证据门槛”的检索后处理链。
 */
@Service
public class HybridRetrievalService {

    private static final int RRF_K = 60;
    private static final int RECALL_LIMIT = 8;
    private static final int RERANK_CANDIDATE_LIMIT = 6;
    private static final int CONTEXT_TOP_K = 2;
    private static final double MIN_RERANK_SCORE = 0.25;

    private final List<SearchChannel> channels;
    private final EvidenceReranker reranker;
    private final Executor retrievalExecutor;

    /**
     * Spring 注入两条召回通道、精排器和有限并行线程池。
     *
     * @param channels          向量和关键词召回通道
     * @param reranker          第二阶段精排器
     * @param retrievalExecutor 通道并行执行器
     */
    public HybridRetrievalService(
            List<SearchChannel> channels,
            EvidenceReranker reranker,
            @Qualifier("retrievalExecutor") Executor retrievalExecutor
    ) {
        this.channels = List.copyOf(channels);
        this.reranker = reranker;
        this.retrievalExecutor = retrievalExecutor;
    }

    /**
     * 并行执行所有通道；单通道失败只丢失该通道，其他通道仍继续进入融合。
     *
     * @param question         可独立检索的问题
     * @param knowledgeBaseIds 第 14 课决定的作用域，空列表表示全库
     * @return 通过精排和证据门槛的最终证据
     */
    public List<RetrievedEvidence> retrieve(String question, List<String> knowledgeBaseIds) {
        SearchQuery query = new SearchQuery(question, knowledgeBaseIds, RECALL_LIMIT);
        List<CompletableFuture<SearchChannelResult>> futures = channels.stream()
                .map(channel -> CompletableFuture.supplyAsync(
                        () -> searchSafely(channel, query), retrievalExecutor
                ))
                .toList();
        List<SearchChannelResult> results = futures.stream()
                .map(CompletableFuture::join)
                .filter(result -> !result.candidates().isEmpty())
                .toList();
        List<RetrievedEvidence> fused = fuseByRrf(results);
        if (fused.isEmpty()) {
            return List.of();
        }

        List<RetrievedEvidence> candidatePool = fused.stream()
                .limit(RERANK_CANDIDATE_LIMIT)
                .toList();
        List<RetrievedEvidence> reranked = reranker.rerank(question, candidatePool, CONTEXT_TOP_K);
        if (reranked.isEmpty()) {
            return List.of();
        }
        if (hasRerankScore(reranked)
                && reranked.stream().mapToDouble(evidence -> evidence.rerankScore()).max().orElse(0.0)
                < MIN_RERANK_SCORE) {
            return List.of();
        }
        return reranked;
    }

    /**
     * 给单个通道设置异常边界；异常变成空结果，不抛掉已经成功的另一路。
     */
    private SearchChannelResult searchSafely(SearchChannel channel, SearchQuery query) {
        try {
            return channel.search(query);
        } catch (Exception ignored) {
            return new SearchChannelResult(channel.name(), List.of(), 0L);
        }
    }

    /**
     * RRF 只比较每个通道中的名次，不直接比较余弦分和关键词分这两把不同的尺子。
     */
    private List<RetrievedEvidence> fuseByRrf(List<SearchChannelResult> results) {
        Map<String, RetrievedEvidence> merged = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, List<String>> channelNames = new LinkedHashMap<>();
        for (SearchChannelResult result : results) {
            List<RetrievedEvidence> candidates = result.candidates();
            for (int rank = 0; rank < candidates.size(); rank++) {
                RetrievedEvidence candidate = candidates.get(rank);
                double contribution = 1.0 / (RRF_K + rank + 1);
                scores.merge(candidate.id(), contribution, Double::sum);
                channelNames.computeIfAbsent(candidate.id(), ignored -> new ArrayList<>())
                        .addAll(candidate.channels());
                merged.merge(candidate.id(), candidate, (oldValue, newValue) ->
                        oldValue.fusedWith(newValue, channelNames.get(candidate.id()), scores.get(candidate.id()))
                );
            }
        }
        return merged.values().stream()
                .map(candidate -> candidate.withRrfScore(scores.get(candidate.id())))
                .sorted(Comparator.comparingDouble(RetrievedEvidence::rrfScore).reversed())
                .toList();
    }

    private boolean hasRerankScore(List<RetrievedEvidence> evidences) {
        return evidences.stream().anyMatch(evidence -> evidence.rerankScore() != null);
    }
}
