package com.shitan.ai;

import java.util.List;

/**
 * 根据目标、历史 Observation 和工具说明选择下一步；不直接执行任何工具。
 */
@FunctionalInterface
public interface AgentDecisionModel {

    /**
     * 返回一次工具调用或最终回答决定。
     */
    AgentDecision decide(
            AgentScope scope,
            List<AgentStep> steps,
            List<AgentToolDefinition> tools
    ) throws Exception;
}
