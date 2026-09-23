package com.shitan.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 汇总主应用真正依赖的数据库和 MCP 连通性，不把探活 SQL 放进 Controller。
 */
@Service
public class SystemHealthService {

    private final JdbcTemplate jdbcTemplate;
    private final McpToolClient mcpToolClient;

    /**
     * 保存数据库入口和 MCP 客户端，用同一套生产配置执行健康检查。
     */
    public SystemHealthService(JdbcTemplate jdbcTemplate, McpToolClient mcpToolClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.mcpToolClient = mcpToolClient;
    }

    /**
     * 执行轻量 SELECT 1 和 tools/list；已配置但不可达的 MCP 会让总状态降级。
     */
    public SystemHealthResponse health() {
        boolean databaseReachable;
        try {
            databaseReachable = Integer.valueOf(1).equals(
                    jdbcTemplate.queryForObject("SELECT 1", Integer.class));
        } catch (Exception exception) {
            databaseReachable = false;
        }
        McpConnectionHealth mcp = mcpToolClient.health();
        boolean mcpReady = !mcp.configured() || mcp.reachable();
        return new SystemHealthResponse(
                databaseReachable && mcpReady ? "UP" : "DEGRADED",
                databaseReachable,
                mcp
        );
    }
}
