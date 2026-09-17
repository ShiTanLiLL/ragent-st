package com.shitan.ai;

/**
 * SSE 的第一条元数据事件，让调用方知道当前生成任务的唯一编号。
 *
 * @param taskId 后续取消本次生成时使用的任务编号
 */
public record StreamMeta(
        String taskId
) {
}
