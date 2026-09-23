package com.shitan.ai;

/**
 * 一段可能抛出受检异常的业务步骤，供追踪服务统一记录成功、失败和耗时。
 */
@FunctionalInterface
public interface TraceOperation<T> {

    /**
     * 执行真正业务并返回供下游继续使用的结果。
     */
    T execute() throws Exception;
}
