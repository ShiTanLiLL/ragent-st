package com.shitan.ai;

import java.time.Instant;

/**
 * Agent 循环的一次模型决定，以及工具执行后返回给模型的 Observation。
 */
public record AgentStep(
        long id,
        String sessionId,
        int iteration,
        String decisionType,
        String decisionSummary,
        String toolName,
        String toolInput,
        String observation,
        Instant createdAt
) {
}
