package com.shitan.ai;

import java.util.List;

/**
 * 一次 HTTP 调用允许 Agent 使用的服务端边界和已经恢复的历史状态。
 *
 * @param sessionId       Agent 会话编号
 * @param userId          会话所有者
 * @param objective       当前轮用户目标
 * @param knowledgeBaseId 服务端限定的知识库，不允许模型自行扩大范围
 * @param maxIterations   当前轮最多允许几次模型决定
 * @param previousSteps   数据库恢复出的历史步骤
 */
public record AgentScope(
        String sessionId,
        String userId,
        String objective,
        String knowledgeBaseId,
        int maxIterations,
        List<AgentStep> previousSteps
) {

    /**
     * 复制历史步骤，保证循环期间 Scope 作为不可变边界使用。
     */
    public AgentScope {
        previousSteps = List.copyOf(previousSteps);
    }

    /**
     * 下一条步骤编号接在数据库历史之后，使恢复会话时不会覆盖旧步骤。
     */
    public int firstIteration() {
        return previousSteps.stream().mapToInt(AgentStep::iteration).max().orElse(0) + 1;
    }
}
