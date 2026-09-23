package com.shitan.ai;

import java.util.List;

/**
 * 追踪查询接口的聚合结果：一次 Run、顺序节点和可选用户反馈。
 */
public record RagTraceDetail(
        RagTraceRun run,
        List<RagTraceNode> nodes,
        AnswerFeedback feedback
) {

    /**
     * 固定节点列表快照，保证 JSON 序列化期间顺序不被修改。
     */
    public RagTraceDetail {
        nodes = List.copyOf(nodes);
    }
}
