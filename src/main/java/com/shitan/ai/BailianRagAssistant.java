package com.shitan.ai;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
}
