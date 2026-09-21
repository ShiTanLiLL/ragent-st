package com.shitan.ai;

/**
 * 上传接口立即返回的受理结果；它只表示任务已登记，不表示文档已经完成索引。
 *
 * @param taskId     后续查询进度和重试时使用的任务编号
 * @param documentId 后续查询文档和片段时使用的文档编号
 * @param status     返回时固定为 pending
 */
public record IngestionSubmission(
        String taskId,
        String documentId,
        IngestionTaskStatus status
) {
}
