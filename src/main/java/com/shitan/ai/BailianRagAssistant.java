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
    private final KnowledgeRepository knowledgeRepository;
    private final HybridRetrievalService hybridRetrievalService;
    private final VectorSearch vectorSearch = new VectorSearch();

    /**
     * 保存具体百炼客户端，后续由助手统一安排向量化、检索和生成顺序。
     *
     * @param bailianClient 负责真实模型 HTTP 协议的客户端
     */
    public BailianRagAssistant(BailianClient bailianClient) {
        this(bailianClient, null, null);
    }

    /**
     * 保存百炼客户端和 PostgreSQL 知识仓库，供正式应用执行数据库向量检索。
     *
     * @param bailianClient      负责 Embedding 与 Chat 的客户端
     * @param knowledgeRepository 负责 pgvector Top-1 检索的仓库
     */
    public BailianRagAssistant(
            BailianClient bailianClient,
            KnowledgeRepository knowledgeRepository
    ) {
        this(bailianClient, knowledgeRepository, null);
    }

    /**
     * 保存传统直接仓库路径和第 15 课混合检索服务；测试可继续使用前两个构造器。
     *
     * @param bailianClient           负责外部模型协议
     * @param knowledgeRepository     负责旧版单路数据库检索
     * @param hybridRetrievalService 负责多路召回、融合和精排
     */
    public BailianRagAssistant(
            BailianClient bailianClient,
            KnowledgeRepository knowledgeRepository,
            HybridRetrievalService hybridRetrievalService
    ) {
        this.bailianClient = bailianClient;
        this.knowledgeRepository = knowledgeRepository;
        this.hybridRetrievalService = hybridRetrievalService;
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

        return answerFromCandidatesWithVectors(question, candidates);
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
    public KnowledgeAnswer answerFromCandidatesWithVectors(
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
     * 为问题生成向量，让 PostgreSQL pgvector 选出 Top-1 证据，再调用聊天模型生成回答。
     *
     * @param question        用户问题
     * @param knowledgeBaseId 要检索的持久化知识库编号
     * @return 模型回答及数据库检索命中的来源标题
     * @throws IOException          百炼网络通信或响应解析失败
     * @throws InterruptedException 等待百炼响应期间当前线程被中断
     */
    public KnowledgeAnswer answerFromDatabase(
            String question,
            String knowledgeBaseId
    ) throws IOException, InterruptedException {
        List<String> knowledgeBaseIds = knowledgeBaseId == null || knowledgeBaseId.isBlank()
                ? List.of()
                : List.of(knowledgeBaseId);
        return answerFromDatabase(question, knowledgeBaseIds);
    }

    /**
     * 在意图规划器给出的知识库作用域中检索；空列表表示低置信度时回落全库。
     *
     * @param question         已经拆分、可独立检索的问题
     * @param knowledgeBaseIds 允许访问的知识库编号，空列表表示全部
     * @return 模型回答及 Top-1 证据标题
     * @throws IOException          百炼网络通信或响应解析失败
     * @throws InterruptedException 等待百炼响应期间线程被中断
     */
    public KnowledgeAnswer answerFromDatabase(
            String question,
            List<String> knowledgeBaseIds
    ) throws IOException, InterruptedException {
        if (hybridRetrievalService != null) {
            List<RetrievedEvidence> evidence = hybridRetrievalService.retrieve(
                    question,
                    knowledgeBaseIds
            );
            if (evidence.isEmpty()) {
                return new KnowledgeAnswer("暂时没有找到足够相关的知识。", null, List.of());
            }
            String generatedAnswer = bailianClient.generateAnswer(
                    question,
                    evidence.stream().map(RetrievedEvidence::toKnowledgeEntry).toList()
            );
            return new KnowledgeAnswer(
                    generatedAnswer,
                    evidence.get(0).title(),
                    evidence
            );
        }
        KnowledgeRepository repository = requireKnowledgeRepository();
        double[] questionVector = bailianClient.createEmbedding(question);
        KnowledgeEntry evidence = repository.searchTopOneInKnowledgeBases(knowledgeBaseIds, questionVector)
                .orElse(null);
        if (evidence == null) {
            return new KnowledgeAnswer("暂时没有找到相关知识。", null);
        }

        String generatedAnswer = bailianClient.generateAnswer(question, evidence);
        return new KnowledgeAnswer(generatedAnswer, evidence.title());
    }

    /**
     * 有会话历史时改写追问；第一问直接返回原问题，避免多调用一次模型。
     *
     * @param question 用户原始问题
     * @param memory   当前会话允许使用的有界记忆
     * @return 实际交给意图规划器的问题
     * @throws IOException          百炼网络通信或响应解析失败
     * @throws InterruptedException 等待百炼响应期间线程被中断
     */
    public String rewriteQuestionIfNeeded(String question, ConversationMemory memory)
            throws IOException, InterruptedException {
        return memory.hasContext() ? bailianClient.rewriteQuestion(question, memory) : question;
    }

    /**
     * 先用摘要和近期消息把省略追问改写成独立问题，再复用数据库 RAG 主链。
     *
     * @param question        用户本轮原始问题
     * @param knowledgeBaseId 要检索的持久化知识库
     * @param memory          本轮允许使用的有界会话记忆
     * @return 最终 RAG 结果以及真正用于 Embedding 的改写问题
     * @throws IOException          百炼网络通信或 JSON 解析失败
     * @throws InterruptedException 等待百炼响应时线程被中断
     */
    public ContextualKnowledgeAnswer answerFromDatabase(
            String question,
            String knowledgeBaseId,
            ConversationMemory memory
    ) throws IOException, InterruptedException {
        String rewrittenQuestion = rewriteQuestionIfNeeded(question, memory);
        return new ContextualKnowledgeAnswer(
                answerFromDatabase(rewrittenQuestion, knowledgeBaseId),
                rewrittenQuestion
        );
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

        return streamAnswerFromCandidatesWithVectors(question, candidates, onChunk, cancelled);
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
    public String streamAnswerFromCandidatesWithVectors(
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

    /**
     * 流式回答持久化知识库问题：生成问题向量、由 pgvector 检索证据，再流式调用 Chat。
     *
     * @param question        用户问题
     * @param knowledgeBaseId 持久化知识库编号
     * @param onChunk         接收每段新增回答文字的回调
     * @param cancelled       查询最新取消状态的函数
     * @return 实际使用的证据标题；取消或没有知识时返回 null
     * @throws IOException          百炼网络通信或响应解析失败
     * @throws InterruptedException 等待百炼响应期间当前线程被中断
     */
    public String streamAnswerFromDatabase(
            String question,
            String knowledgeBaseId,
            Consumer<String> onChunk,
            BooleanSupplier cancelled
    ) throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) {
            return null;
        }

        KnowledgeRepository repository = requireKnowledgeRepository();
        double[] questionVector = bailianClient.createEmbedding(question);
        if (cancelled.getAsBoolean()) {
            return null;
        }

        KnowledgeEntry evidence = repository.searchTopOne(knowledgeBaseId, questionVector)
                .orElse(null);
        if (evidence == null || cancelled.getAsBoolean()) {
            return null;
        }

        bailianClient.streamAnswer(question, evidence, onChunk, cancelled);
        return cancelled.getAsBoolean() ? null : evidence.title();
    }

    /**
     * 返回正式应用注入的数据库仓库，旧单元测试误调数据库路径时给出直接错误。
     *
     * @return PostgreSQL 知识仓库
     */
    private KnowledgeRepository requireKnowledgeRepository() {
        if (knowledgeRepository == null) {
            throw new IllegalStateException("当前 RAG 助手没有配置知识数据库");
        }
        return knowledgeRepository;
    }
}
