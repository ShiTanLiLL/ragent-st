package com.shitan.ai;

import java.time.Instant;

/**
 * 一次 Run 中一个关键步骤的输入摘要、输出摘要、耗时和错误。
 */
public record RagTraceNode(
        long id,
        String runId,
        String nodeName,
        String status,
        String inputSummary,
        String outputSummary,
        long durationMillis,
        String errorMessage,
        Instant createdAt
) {
}
