package com.shitan.ai;

import java.util.List;

/**
 * 问答接口返回给调用方的 JSON 数据。
 *
 * @param answer      百炼根据检索证据生成的回答正文
 * @param sourceTitle 本地向量检索选中的证据标题
 * @param conversationId 本轮问答所属会话；旧的无记忆请求为 null
 * @param rewrittenQuestion 带上历史上下文后交给检索的独立问题
 * @param intentPlans 本轮拆问和知识库作用域规划，便于学习阶段观察
 * @param evidence 本轮实际送入答案模型的有界证据
 * @param sources 按文档去重、适合展示给用户的稳定来源
 * @param runId 本轮可查询节点追踪的运行编号
 * @param assistantMessageId 最终回答在会话消息表中的编号，可用于提交反馈
 */
public record QuestionResponse(
        String answer,
        String sourceTitle,
        String conversationId,
        String rewrittenQuestion,
        List<IntentPlan> intentPlans,
        List<RetrievedEvidence> evidence,
        List<SourceReference> sources,
        String runId,
        Long assistantMessageId
) {

    /**
     * 保证响应中的规划列表是只读快照。
     */
    public QuestionResponse {
        intentPlans = List.copyOf(intentPlans);
        evidence = List.copyOf(evidence);
        sources = List.copyOf(sources);
    }

    /**
     * 兼容前六课不带会话字段的 Controller 返回方式。
     *
     * @param answer      回答正文
     * @param sourceTitle 证据来源标题
     */
    public QuestionResponse(String answer, String sourceTitle) {
        this(answer, sourceTitle, null, null, List.of(), List.of(), List.of(), null, null);
    }
}
