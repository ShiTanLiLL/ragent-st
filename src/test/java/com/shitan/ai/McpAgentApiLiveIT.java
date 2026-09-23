package com.shitan.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitan.ai.mcp.McpOrderServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第20课完整流程测试：真实 MCP HTTP 工具发现、Skill 解锁、写操作确认和冻结参数执行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(McpAgentApiLiveIT.ScriptedDecisionConfiguration.class)
class McpAgentApiLiveIT {

    private static final McpOrderServer MCP_SERVER = startMcpServer();

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 在 Spring 建 Bean 前把随机 MCP 端口写入配置，让产品客户端发现真正的远端工具。
     */
    @DynamicPropertySource
    static void mcpProperties(DynamicPropertyRegistry registry) {
        registry.add("ragent.mcp.url", () -> MCP_SERVER.endpoint().toString());
    }

    /**
     * 完成“远端查询 → 加载手册 → 冻结写参数 → 用户批准 → 远端写入 → 最终回答”。
     */
    @Test
    void shouldDiscoverMcpToolsAndConfirmWriteWithFrozenArguments() throws Exception {
        JsonNode health = getJson("/api/system/health");
        assertEquals("UP", health.path("status").asText(), health.toString());
        assertTrue(health.path("databaseReachable").asBoolean());
        assertTrue(health.path("mcp").path("reachable").asBoolean());
        assertEquals(2, health.path("mcp").path("toolCount").asInt());

        // 只读 order_status 会立即执行；Skill 加载后写工具才对模型可见，但此时仍不能真正写远端。
        JsonNode waiting = postJson("/api/agent/tasks", """
                {
                  "userId":"alice",
                  "objective":"查询 ORD-1001，并为耳机不合适创建退货申请"
                }
                """);
        assertEquals("waiting_confirmation", waiting.path("status").asText(), waiting.toString());
        assertEquals(2, waiting.path("steps").size(), waiting.toString());
        assertEquals("order_status", waiting.path("steps").path(0).path("toolName").asText());
        assertEquals("load_skill", waiting.path("steps").path(1).path("toolName").asText());
        assertTrue(waiting.path("steps").path(1).path("observation").asText()
                .contains("确认后只能执行已经展示的参数"));
        assertEquals("pending", waiting.path("confirmation").path("status").asText());
        assertEquals("return_request_create",
                waiting.path("confirmation").path("toolName").asText());
        assertTrue(waiting.path("confirmation").path("toolInput").asText().contains("ORD-1001"));
        assertTrue(waiting.path("confirmation").path("toolInput").asText().contains("耳机不合适"));
        assertEquals(0, MCP_SERVER.createdReturnCount(), "用户确认前远端绝不能产生退货申请");

        String confirmationId = waiting.path("confirmation").path("id").asText();
        assertFalse(confirmationId.isBlank());

        // 确认请求故意只有 approved，不允许调用方重新提交或替换被冻结的 orderId/reason。
        JsonNode completed = postJson("/api/agent/confirmations/" + confirmationId, """
                {"userId":"alice","approved":true}
                """);
        assertEquals("completed", completed.path("status").asText(), completed.toString());
        assertEquals(4, completed.path("steps").size(), completed.toString());
        assertEquals("return_request_create",
                completed.path("steps").path(2).path("toolName").asText());
        assertTrue(completed.path("steps").path(2).path("observation").asText()
                .contains("RET-1001"), completed.toString());
        assertEquals("answer", completed.path("steps").path(3).path("decisionType").asText());
        assertEquals("approved", completed.path("confirmation").path("status").asText());
        assertEquals(1, MCP_SERVER.createdReturnCount());

        // 同一确认再次点击不会第二次调用写工具，HTTP 409 明确告诉前端刷新状态。
        HttpResponse<String> repeated = postRaw(
                "/api/agent/confirmations/" + confirmationId,
                "{\"userId\":\"alice\",\"approved\":true}"
        );
        assertEquals(409, repeated.statusCode(), repeated.body());
        assertEquals(1, MCP_SERVER.createdReturnCount());

        System.out.println("MCP 健康与发现结果：" + health);
        System.out.println("确认前冻结状态：" + waiting);
        System.out.println("确认后远端 Observation 与最终回答：" + completed);
    }

    /**
     * 测试结束时关闭真实 MCP HTTP 服务，避免占用后台线程和端口。
     */
    @AfterAll
    static void stopMcpServer() {
        MCP_SERVER.close();
    }

    /**
     * 发送必须成功的 JSON POST，并返回 Jackson 树供完整链路断言。
     */
    private JsonNode postJson(String path, String json) throws Exception {
        HttpResponse<String> response = postRaw(path, json);
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        return objectMapper.readTree(response.body());
    }

    /**
     * 发送 JSON POST 并保留状态码，供重复确认场景检查 HTTP 409。
     */
    private HttpResponse<String> postRaw(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * 查询健康接口，证明主数据库和远端工具服务都已准备好。
     */
    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
    }

    /**
     * 使用 Spring 随机端口拼接主应用地址；MCP Server 使用另一独立随机端口。
     */
    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /**
     * 启动与生产 main 相同的 MCP Server 实现，而不是伪造 tools/list 和 tools/call 响应。
     */
    private static McpOrderServer startMcpServer() {
        try {
            return McpOrderServer.start(0);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /**
     * 只固定“模型如何选下一步”，真实 MCP 发现、HTTP 调用、确认和数据库状态都不替换。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedDecisionConfiguration {

        /**
         * 根据历史步骤和当前可见工具验证 Skill 前后工具目录确实发生变化。
         */
        @Bean
        @Primary
        AgentDecisionModel scriptedAgentDecisionModel() {
            return (scope, steps, tools) -> decide(steps, tools);
        }

        /**
         * 依次选择查询、Skill、写工具和答案；目录边界不对时立即让测试失败。
         */
        private AgentDecision decide(List<AgentStep> steps, List<AgentToolDefinition> tools) {
            boolean skillLoaded = hasTool(steps, "load_skill");
            boolean writeVisible = tools.stream()
                    .anyMatch(tool -> "return_request_create".equals(tool.name()));
            if (writeVisible != skillLoaded) {
                throw new IllegalStateException("写工具可见性与 Skill 加载状态不一致");
            }
            if (!hasTool(steps, "order_status")) {
                return AgentDecision.tool(
                        "先读取远端订单状态",
                        "order_status",
                        Map.of("orderId", "ORD-1001")
                );
            }
            if (!skillLoaded) {
                return AgentDecision.tool(
                        "复杂写操作前先加载退货办理手册",
                        "load_skill",
                        Map.of("skillCode", "return_request")
                );
            }
            if (!hasTool(steps, "return_request_create")) {
                return AgentDecision.tool(
                        "订单已签收且用户给出原因，申请执行写操作",
                        "return_request_create",
                        Map.of("orderId", "ORD-1001", "reason", "耳机不合适")
                );
            }
            return AgentDecision.answer(
                    "远端已经返回退货申请编号",
                    "退货申请 RET-1001 已创建，请等待后续审核。"
            );
        }

        /**
         * 判断某工具是否已经形成持久化步骤；等待确认的提案还不算执行步骤。
         */
        private boolean hasTool(List<AgentStep> steps, String toolName) {
            return steps.stream().anyMatch(step -> toolName.equals(step.toolName()));
        }
    }
}
