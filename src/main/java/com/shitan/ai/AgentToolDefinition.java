package com.shitan.ai;

import java.util.List;

/**
 * 交给决策模型阅读的工具说明；它描述名称、用途和允许的参数名。
 */
public record AgentToolDefinition(
        String name,
        String description,
        List<String> argumentNames
) {

    /**
     * 固定参数名列表，避免 Prompt 组装时被修改。
     */
    public AgentToolDefinition {
        argumentNames = List.copyOf(argumentNames);
    }
}
