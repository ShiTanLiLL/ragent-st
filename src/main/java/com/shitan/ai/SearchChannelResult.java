package com.shitan.ai;

import java.util.List;

/**
 * 一个召回通道交出的有序候选及耗时。
 *
 * @param channelName 通道名称
 * @param candidates  按该通道自己的分数从高到低排列的候选
 * @param latencyMs   通道耗时
 */
public record SearchChannelResult(
        String channelName,
        List<RetrievedEvidence> candidates,
        long latencyMs
) {

    /**
     * 让通道结果成为不可变快照。
     */
    public SearchChannelResult {
        candidates = List.copyOf(candidates);
    }
}
