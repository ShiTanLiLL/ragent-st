package com.shitan.ai;

/**
 * 一种召回策略的最小契约；不同通道共享作用域，但各自决定如何找候选。
 */
public interface SearchChannel {

    /**
     * 返回用于归因和测试观察的稳定通道名。
     */
    String name();

    /**
     * 执行本通道召回，并按本通道内部相关性从高到低返回。
     *
     * @param query 问题、作用域和召回预算
     * @return 有序候选和耗时
     * @throws Exception 外部模型或数据库访问失败
     */
    SearchChannelResult search(SearchQuery query) throws Exception;
}
