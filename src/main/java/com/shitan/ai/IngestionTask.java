package com.shitan.ai;

import java.time.Instant;

/**
 * 一次后台摄取的数据库状态快照。
 *
 * @param id            任务编号
 * @param documentId    本任务处理的文档编号
 * @param status        当前任务状态
 * @param currentStep   当前阶段；等待或结束后可以为空
 * @param attemptCount  当前是第几次执行，首次为 1
 * @param errorMessage  最后一次失败原因
 * @param startedAt     当前尝试开始时间
 * @param completedAt   当前尝试结束时间
 */
public record IngestionTask(
        String id,
        String documentId,
        IngestionTaskStatus status,
        String currentStep,
        int attemptCount,
        String errorMessage,
        Instant startedAt,
        Instant completedAt
) {
}
