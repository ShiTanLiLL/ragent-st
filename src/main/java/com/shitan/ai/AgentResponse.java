package com.shitan.ai;

import java.util.List;

/**
 * Agent HTTP 响应：当前会话状态、最终答案和可阅读的迭代步骤。
 */
public record AgentResponse(
        String sessionId,
        String status,
        String answer,
        List<AgentStep> steps
) {

    /**
     * 固定步骤快照，避免返回期间后台代码修改列表。
     */
    public AgentResponse {
        steps = List.copyOf(steps);
    }
}
