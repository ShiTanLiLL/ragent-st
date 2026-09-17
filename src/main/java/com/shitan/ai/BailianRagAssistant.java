package com.shitan.ai;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 把百炼文本向量化、向量检索和基于证据生成回答串成当前最小 RAG 流程。
 */
public final class BailianRagAssistant {

    private final BailianClient bailianClient;
    private final VectorSearch vectorSearch = new VectorSearch();

    public BailianRagAssistant(BailianClient bailianClient) {
        this.bailianClient = bailianClient;
    }

    public KnowledgeAnswer answer(String question, List<KnowledgeEntry> knowledgeEntries)
            throws IOException, InterruptedException {
        if (knowledgeEntries.isEmpty()) {
            return new KnowledgeAnswer("暂时没有找到相关知识。", null);
        }

        List<EmbeddedKnowledge> candidates = new ArrayList<>();

        // 当前先在每次提问时为全部知识生成向量，出现真实性能问题后再考虑保存索引。
        for (KnowledgeEntry knowledge : knowledgeEntries) {
            String textForEmbedding = knowledge.title() + "\n" + knowledge.content();
            double[] vector = bailianClient.createEmbedding(textForEmbedding);
            candidates.add(new EmbeddedKnowledge(knowledge, vector));
        }

        double[] questionVector = bailianClient.createEmbedding(question);
        KnowledgeEntry evidence = vectorSearch.search(questionVector, candidates, 1).get(0);
        String generatedAnswer = bailianClient.generateAnswer(question, evidence);

        return new KnowledgeAnswer(generatedAnswer, evidence.title());
    }

    /**
     * 执行“知识向量化 → 问题向量化 → Top-1 检索 → 流式生成”，每得到一段回答就立即回调上层。
     *
     * @param question         用户问题
     * @param knowledgeEntries 当前可检索的知识
     * @param onChunk          每当百炼返回一段新增文字时调用
     * @param cancelled        用来检查用户是否已经取消当前任务
     * @return 本次生成实际使用的证据标题；取消时返回 null
     * @throws IOException          百炼网络通信或响应解析失败
     * @throws InterruptedException 等待百炼响应时后台线程被中断
     */
    public String streamAnswer(
            String question,
            List<KnowledgeEntry> knowledgeEntries,
            Consumer<String> onChunk,
            BooleanSupplier cancelled
    ) throws IOException, InterruptedException {
        if (knowledgeEntries.isEmpty() || cancelled.getAsBoolean()) {
            return null;
        }

        List<EmbeddedKnowledge> candidates = new ArrayList<>();
        for (KnowledgeEntry knowledge : knowledgeEntries) {
            if (cancelled.getAsBoolean()) {
                return null;
            }

            String textForEmbedding = knowledge.title() + "\n" + knowledge.content();
            double[] vector = bailianClient.createEmbedding(textForEmbedding);
            candidates.add(new EmbeddedKnowledge(knowledge, vector));
        }

        if (cancelled.getAsBoolean()) {
            return null;
        }

        double[] questionVector = bailianClient.createEmbedding(question);
        KnowledgeEntry evidence = vectorSearch.search(questionVector, candidates, 1).get(0);
        bailianClient.streamAnswer(question, evidence, onChunk, cancelled);

        return cancelled.getAsBoolean() ? null : evidence.title();
    }
}
