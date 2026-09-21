package com.shitan.ai;

/**
 * 一条线性摄取流程中的节点配置。
 *
 * @param id         节点在任务日志中的稳定名称
 * @param type       应由哪一种节点实现执行
 * @param nextNodeId 下一个节点编号；null 表示这条链结束
 */
public record IngestionPipelineNode(
        String id,
        IngestionNodeType type,
        String nextNodeId
) {
}
