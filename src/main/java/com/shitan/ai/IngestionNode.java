package com.shitan.ai;

/**
 * 所有摄取节点共同遵守的最小约定：声明类型，并接收、返回同一种上下文。
 */
public interface IngestionNode {

    /**
     * 声明本实现可以执行哪一种流程节点。
     *
     * @return 唯一节点类型
     */
    IngestionNodeType type();

    /**
     * 读取上游上下文、完成本节点工作，并把新增数据交给下游。
     *
     * @param context 上游节点传来的任务数据
     * @return 本节点处理后的任务数据
     * @throws Exception 文件、网络或模型调用失败时交给统一流程执行器记录
     */
    IngestionContext execute(IngestionContext context) throws Exception;
}
