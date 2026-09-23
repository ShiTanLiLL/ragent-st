package com.shitan.ai;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * 编排“改写 → 拆问 → 选择作用域 → 分别检索”的问答前置流程。
 */
@Service
public class IntentRoutingService {

    private final IntentPlanner intentPlanner;
    private final BailianRagAssistant assistant;
    private final RagTraceService traceService;

    /**
     * 保存规划器和既有 RAG 助手；本类不直接访问 JDBC 和百炼 JSON。
     *
     * @param intentPlanner 负责拆分问题和给出知识库候选
     * @param assistant     负责 Embedding、pgvector 和最终回答
     */
    public IntentRoutingService(
            IntentPlanner intentPlanner,
            BailianRagAssistant assistant,
            RagTraceService traceService
    ) {
        this.intentPlanner = intentPlanner;
        this.assistant = assistant;
        this.traceService = traceService;
    }

    /**
     * 执行本课完整路由流程；遇到歧义时立即返回澄清提示，不调用错误知识库。
     *
     * @param question         用户本轮问题
     * @param requestedBaseId  调用方明确指定的知识库，可为空
     * @param memory           会话摘要和近期消息
     * @return 带规划结果的回答
     * @throws Exception Embedding、数据库或百炼调用失败
     */
    public RoutedKnowledgeAnswer answer(
            String question,
            String requestedBaseId,
            ConversationMemory memory
    ) throws Exception {
        return answer(question, requestedBaseId, memory, ModelTier.STANDARD);
    }

    /**
     * 在既有规划链上附带模型档位；规划和检索范围不因模型降级而改变。
     */
    public RoutedKnowledgeAnswer answer(
            String question,
            String requestedBaseId,
            ConversationMemory memory,
            ModelTier modelTier
    ) throws Exception {
        return answer(question, requestedBaseId, memory, modelTier, null);
    }

    /**
     * 在一次 Run 中分别记录改写、规划，以及每个子问题的检索和生成。
     */
    public RoutedKnowledgeAnswer answer(
            String question,
            String requestedBaseId,
            ConversationMemory memory,
            ModelTier modelTier,
            String runId
    ) throws Exception {
        String rewritten = runId == null
                ? assistant.rewriteQuestionIfNeeded(question, memory)
                : traceService.recordNode(
                        runId,
                        "rewrite_question",
                        "hasContext=" + memory.hasContext() + ", question=" + question,
                        () -> assistant.rewriteQuestionIfNeeded(question, memory),
                        result -> "rewritten=" + result
                );
        List<IntentPlan> plans = runId == null
                ? intentPlanner.plan(rewritten, requestedBaseId)
                : traceService.recordNode(
                        runId,
                        "intent_plan",
                        "rewritten=" + rewritten + ", requestedBase=" + requestedBaseId,
                        () -> intentPlanner.plan(rewritten, requestedBaseId),
                        result -> "plans=" + result.size() + ", scopes="
                                + result.stream().map(IntentPlan::knowledgeBaseIds).toList()
                );

        for (IntentPlan plan : plans) {
            if (plan.clarificationRequired()) {
                return new RoutedKnowledgeAnswer(
                        plan.clarification(),
                        null,
                        rewritten,
                        plans,
                        List.of()
                );
            }
        }

        List<String> answers = new ArrayList<>();
        List<RetrievedEvidence> evidence = new ArrayList<>();
        StringJoiner sources = new StringJoiner("；");
        StringJoiner rewrittenQuestions = new StringJoiner("\n");
        for (int index = 0; index < plans.size(); index++) {
            IntentPlan plan = plans.get(index);
            KnowledgeAnswer answer = runId == null
                    ? assistant.answerFromDatabase(
                            plan.question(),
                            plan.knowledgeBaseIds(),
                            modelTier
                    )
                    : assistant.answerFromDatabase(
                            plan.question(),
                            plan.knowledgeBaseIds(),
                            modelTier,
                            runId,
                            index + 1
                    );
            answers.add(answer.content());
            evidence.addAll(answer.evidence());
            if (answer.sourceTitle() != null && !answer.sourceTitle().isBlank()) {
                sources.add(answer.sourceTitle());
            }
            rewrittenQuestions.add(plan.question());
        }
        return new RoutedKnowledgeAnswer(
                combineAnswers(answers),
                sources.length() == 0 ? null : sources.toString(),
                rewrittenQuestions.toString(),
                plans,
                evidence
        );
    }

    /**
     * 多个子问题各自回答后用编号拼接，避免本课提前引入最终答案二次合成模型。
     */
    private String combineAnswers(List<String> answers) {
        if (answers.size() == 1) {
            return answers.get(0);
        }
        StringBuilder combined = new StringBuilder("根据不同知识库分别回答：");
        for (int index = 0; index < answers.size(); index++) {
            combined.append("\n").append(index + 1).append(". ").append(answers.get(index));
        }
        return combined.toString();
    }
}
