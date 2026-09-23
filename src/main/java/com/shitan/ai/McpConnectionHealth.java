package com.shitan.ai;

/**
 * MCP 连接探测结果；区分没有配置、远端不可达和已经发现工具三种情况。
 */
public record McpConnectionHealth(
        boolean configured,
        boolean reachable,
        int toolCount,
        String error
) {
}
