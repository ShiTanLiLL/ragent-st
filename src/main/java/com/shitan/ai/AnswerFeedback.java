package com.shitan.ai;

import java.time.Instant;

/**
 * 用户针对一次已落库回答给出的点赞或点踩及原因。
 */
public record AnswerFeedback(
        String runId,
        long assistantMessageId,
        String userId,
        int rating,
        String reason,
        Instant updatedAt
) {
}
