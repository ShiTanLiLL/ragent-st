package com.shitan.ai;

import java.time.Instant;

/**
 * 一次同步会话问答的总追踪记录，用于先判断整次运行成功还是失败。
 */
public record RagTraceRun(
        String id,
        String conversationId,
        String userId,
        String questionSummary,
        String status,
        Long assistantMessageId,
        String errorMessage,
        Instant startedAt,
        Instant completedAt
) {
}
