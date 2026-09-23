package com.shitan.ai;

import java.time.Instant;

/**
 * 一次被冻结的写工具调用；用户只确认是否执行，不能在确认请求中替换工具或参数。
 */
public record AgentConfirmation(
        String id,
        String sessionId,
        String userId,
        String toolName,
        String toolInput,
        String decisionSummary,
        String status,
        String observation,
        Instant createdAt,
        Instant updatedAt
) {
}
