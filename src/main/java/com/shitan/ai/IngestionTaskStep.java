package com.shitan.ai;

/**
 * 摄取任务某一次尝试中的阶段记录，用于查看卡在哪一步、耗时多久以及为何失败。
 *
 * @param taskId       所属任务编号
 * @param attempt      第几次尝试
 * @param stepName     fetch、parse、embedding 或 publish
 * @param status       running、completed 或 failed
 * @param durationMs   阶段结束后的耗时毫秒数
 * @param errorMessage 本阶段失败原因
 */
public record IngestionTaskStep(
        String taskId,
        int attempt,
        String stepName,
        IngestionTaskStatus status,
        Long durationMs,
        String errorMessage
) {
}
