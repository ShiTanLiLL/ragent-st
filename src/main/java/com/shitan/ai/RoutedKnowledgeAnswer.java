package com.shitan.ai;

import java.util.List;

/**
 * 记录意图规划后的最终回答、来源、改写问题和每个子问题的规划结果。
 *
 * @param answer             最终展示给用户的正文
 * @param sourceTitle        命中的证据标题，多个子问题时用分号连接
 * @param rewrittenQuestion  实际进入 Embedding 的一个或多个完整问题
 * @param plans              供测试和学习观察的意图规划快照
 */
public record RoutedKnowledgeAnswer(
        String answer,
        String sourceTitle,
        String rewrittenQuestion,
        List<IntentPlan> plans
) {

    /**
     * 用不可变列表保存规划结果，避免 Controller 返回后被意外修改。
     */
    public RoutedKnowledgeAnswer {
        plans = List.copyOf(plans);
    }
}
