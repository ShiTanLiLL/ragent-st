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

    /**
     * 保存具体百炼客户端，后续由助手统一安排向量化、检索和生成顺序。
     *
     * @param bailianClient 负责真实模型 HTTP 协议的客户端
     */
    public BailianRagAssistant(BailianClient bailianClient) {
        this.bailianClient = bailianClient;
    }

    /**
     * 为尚未建立索引的知识逐条生成向量，再执行问题检索和回答；保留给前几课内置知识使用。
     *
     * @param question         用户问题
     * @param knowledgeEntries 尚未带向量的知识列表
     * @return 模型回答及检索命中的来源标题
     * @throws IOException          百炼网络通信或响应解析失败
     * @throws InterruptedException 等待百炼响应期间当前线程被中断
     */
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

        return answerFromIndex(question, candidates);
    }

    /**
     * 使用上传阶段已经建立好的知识向量，只为当前问题生成一次向量并完成检索与回答。
     *
     * @param question   用户问题
     * @param candidates 已经带向量的知识片段
     * @return 模型生成的回答及本地检索选中的来源标题
     * @throws IOException          百炼网络通信或响应解析失败
     * @throws InterruptedException 等待百炼响应期间当前线程被中断
     */
    public KnowledgeAnswer answerFromIndex(
            String question,
            List<EmbeddedKnowledge> candidates
    ) throws IOException, InterruptedException {
        if (candidates.isEmpty()) {
            return new KnowledgeAnswer("暂时没有找到相关知识。", null);
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

        return streamAnswerFromIndex(question, candidates, onChunk, cancelled);
    }

    /**
     * 使用上传时保存的知识向量执行流式问答，避免每次提问重复向量化全部片段。
     *
     * @param question   用户问题
     * @param candidates 已经带向量的知识片段
     * @param onChunk    每得到一段新增回答文字时执行的回调
     * @param cancelled  每到工作关口查询最新取消状态的函数
     * @return 实际使用的证据标题；取消或没有知识时返回 null
     * @throws IOException          百炼网络通信或响应解析失败
     * @throws InterruptedException 等待百炼响应时后台线程被中断
     */
    public String streamAnswerFromIndex(
            String question,
            List<EmbeddedKnowledge> candidates,
            Consumer<String> onChunk,
            BooleanSupplier cancelled
    ) throws IOException, InterruptedException {
        if (candidates.isEmpty() || cancelled.getAsBoolean()) {
            return null;
        }

        double[] questionVector = bailianClient.createEmbedding(question);
        if (cancelled.getAsBoolean()) {
            return null;
        }

        KnowledgeEntry evidence = vectorSearch.search(questionVector, candidates, 1).get(0);
        bailianClient.streamAnswer(question, evidence, onChunk, cancelled);

        return cancelled.getAsBoolean() ? null : evidence.title();
    }
}
