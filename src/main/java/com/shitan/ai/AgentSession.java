package com.shitan.ai;

import java.time.Instant;

/**
 * 一次可恢复的 Agent 会话；状态描述当前轮是完成、越界还是失败。
 */
public record AgentSession(
        String id,
        String userId,
        String latestObjective,
        String knowledgeBaseId,
        String status,
        String finalAnswer,
        Instant createdAt,
        Instant updatedAt
) {
}
