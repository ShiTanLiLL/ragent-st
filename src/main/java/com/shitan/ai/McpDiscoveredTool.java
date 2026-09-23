package com.shitan.ai;

import java.util.List;

/**
 * 从远端 tools/list 读取出的最小工具定义；保留 Agent 决策和安全控制真正需要的字段。
 */
public record McpDiscoveredTool(
        String name,
        String description,
        List<String> argumentNames,
        boolean readOnly,
        String requiredSkillCode
) {

    /**
     * 固定参数名快照，避免发现完成后被调用方修改。
     */
    public McpDiscoveredTool {
        argumentNames = List.copyOf(argumentNames);
    }
}
