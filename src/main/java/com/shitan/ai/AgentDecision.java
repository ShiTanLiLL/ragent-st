package com.shitan.ai;

import java.util.Map;

/**
 * 模型一次可执行决定：调用一个已声明工具，或者给出最终答案。
 */
public record AgentDecision(
        String type,
        String decisionSummary,
        String toolName,
        Map<String, String> arguments,
        String answer
) {

    /**
     * 固定工具参数，防止模型决定进入执行阶段后又被修改。
     */
    public AgentDecision {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    /**
     * 创建测试脚本和模型解析共用的工具调用决定。
     */
    public static AgentDecision tool(
            String summary,
            String toolName,
            Map<String, String> arguments
    ) {
        return new AgentDecision("tool", summary, toolName, arguments, null);
    }

    /**
     * 创建结束循环并返回用户答案的决定。
     */
    public static AgentDecision answer(String summary, String answer) {
        return new AgentDecision("answer", summary, null, Map.of(), answer);
    }
}
