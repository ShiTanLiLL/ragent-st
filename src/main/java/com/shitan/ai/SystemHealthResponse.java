package com.shitan.ai;

/**
 * 教学部署的最小健康响应：分别报告主数据库和可选 MCP 工具服务。
 */
public record SystemHealthResponse(
        String status,
        boolean databaseReachable,
        McpConnectionHealth mcp
) {
}
