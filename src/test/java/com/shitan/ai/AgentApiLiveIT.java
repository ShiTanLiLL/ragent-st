package com.shitan.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.io.ByteArrayOutputStream;
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
 * 第19课完整流程测试：Agent 根据 Observation 动态选择知识和订单工具，并能恢复会话和停止循环。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ragent.storage-root=target/lesson19-uploads"
)
@Import(AgentApiLiveIT.ScriptedDecisionConfiguration.class)
class AgentApiLiveIT {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 跑满“查政策 → 查订单 → 回答 → 恢复会话查另一订单”，再验证错误计划不会无限循环。
     */
    @Test
    void shouldRunResumeAndLimitAgentLoop() throws Exception {
        String baseId = postJson("/api/knowledge-bases", """
                {"name":"售后政策知识库"}
                """).path("id").asText();
        uploadAndWait(baseId, "refund-policy.md", """
                # 退货政策

                商品签收后 7 天内可以申请无理由退货，超过 7 天需要人工审核。
                """);

        // 第一次任务没有现成 Observation，所以脚本模型会先查政策，再查 ORD-1001，最后回答。
        JsonNode first = postJson("/api/agent/tasks", """
                {
                  "userId":"alice",
                  "objective":"先查退货政策，再查订单 ORD-1001，判断能不能退货",
                  "knowledgeBaseId":"%s"
                }
                """.formatted(baseId));
        assertEquals("completed", first.path("status").asText(), first.toString());
        assertEquals(3, first.path("steps").size(), first.toString());
        assertEquals("knowledge_search", first.path("steps").path(0).path("toolName").asText());
        assertEquals("order_lookup", first.path("steps").path(1).path("toolName").asText());
        assertEquals("answer", first.path("steps").path(2).path("decisionType").asText());
        assertTrue(first.path("answer").asText().contains("7天"), first.toString());
        assertTrue(first.path("answer").asText().contains("可以"), first.toString());

        String sessionId = first.path("sessionId").asText();
        assertFalse(sessionId.isBlank());

        // 追问只传 sessionId，不重复传知识库；服务端应恢复旧 Scope，步骤编号继续从4开始。
        JsonNode resumed = postJson("/api/agent/tasks", """
                {
                  "userId":"alice",
                  "objective":"那 ORD-1002 呢？",
                  "sessionId":"%s"
                }
                """.formatted(sessionId));
        assertEquals(sessionId, resumed.path("sessionId").asText());
        assertEquals("completed", resumed.path("status").asText(), resumed.toString());
        assertEquals(5, resumed.path("steps").size(), resumed.toString());
        assertEquals(4, resumed.path("steps").path(3).path("iteration").asInt());
        assertTrue(resumed.path("steps").path(3).path("toolInput").asText().contains("ORD-1002"));
        assertEquals("answer", resumed.path("steps").path(4).path("decisionType").asText());
        assertTrue(resumed.path("answer").asText().contains("超过"), resumed.toString());

        // 如果模型始终只调用工具而不回答，Java 在4次决定后强制收尾，避免死循环和无限费用。
        JsonNode limited = postJson("/api/agent/tasks", """
                {
                  "userId":"alice",
                  "objective":"测试迭代上限"
                }
                """);
        assertEquals("limit_reached", limited.path("status").asText(), limited.toString());
        assertEquals(4, limited.path("steps").size(), limited.toString());
        assertTrue(limited.path("answer").asText().contains("4 次决定"), limited.toString());

        JsonNode persisted = getJson("/api/agent/sessions/" + sessionId + "?userId=alice");
        assertEquals(5, persisted.path("steps").size());
        assertEquals("completed", persisted.path("status").asText());

        System.out.println("第一次任务（知识 → 订单 → 回答）：" + first);
        System.out.println("恢复会话后的累计步骤：" + resumed.path("steps"));
        System.out.println("达到迭代上限后的状态：" + limited);
    }

    /**
     * 上传 Markdown 并等待已有摄取 Pipeline 完成，使知识工具读取真实数据库索引。
     */
    private void uploadAndWait(String baseId, String filename, String content) throws Exception {
        String boundary = "lesson19-boundary";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        body.write("Content-Type: text/markdown; charset=UTF-8\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8));
        body.write(content.getBytes(StandardCharsets.UTF_8));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest upload = HttpRequest.newBuilder(
                        uri("/api/knowledge-bases/" + baseId + "/documents"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> accepted = httpClient.send(
                upload, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(202, accepted.statusCode(), accepted.body());
        String taskId = objectMapper.readTree(accepted.body()).path("taskId").asText();

        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            JsonNode task = getJson("/api/ingestion-tasks/" + taskId);
            String status = task.path("status").asText();
            if ("completed".equals(status) || "failed".equals(status)) {
                assertEquals("completed", status, task.toString());
                return;
            }
            Thread.sleep(200L);
        }
        throw new IllegalStateException("等待知识摄取超时：" + taskId);
    }

    /**
     * 发送必须成功的 JSON POST，并把响应正文转换成便于断言的 Jackson 树。
     */
    private JsonNode postJson(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        return objectMapper.readTree(response.body());
    }

    /**
     * 查询已经持久化的 Agent 会话，确认 HTTP 返回不是只存在于本次方法内存中的临时结果。
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
     * 使用 Spring 分配的随机测试端口拼接本地 HTTP 地址。
     */
    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /**
     * 只替换不可预测的“模型选下一步”，其余 HTTP、工具、数据库、Embedding 和 RAG 都走真实实现。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedDecisionConfiguration {

        /**
         * 根据持久化步骤选择下一动作，使测试稳定展示 ReAct 顺序、恢复和迭代上限。
         */
        @Bean
        @Primary
        AgentDecisionModel scriptedAgentDecisionModel() {
            return (scope, steps, tools) -> scriptedDecision(scope, steps);
        }

        /**
         * 模拟模型读取 Observation 后改变选择；它不执行工具，也不伪造工具返回值。
         */
        private AgentDecision scriptedDecision(AgentScope scope, List<AgentStep> steps) {
            if (scope.objective().contains("测试迭代上限")) {
                return AgentDecision.tool(
                        "继续重复查订单以演示服务端上限",
                        "order_lookup",
                        Map.of("orderId", "ORD-1001")
                );
            }

            if (scope.objective().contains("ORD-1002")) {
                boolean alreadyLookedUp = hasToolInput(steps, "order_lookup", "ORD-1002");
                if (!alreadyLookedUp) {
                    return AgentDecision.tool(
                            "需要取得追问中另一张订单的签收天数",
                            "order_lookup",
                            Map.of("orderId", "ORD-1002")
                    );
                }
                return AgentDecision.answer(
                        "已有政策和 ORD-1002 的 Observation，可以完成判断",
                        "ORD-1002 已签收12天，超过7天无理由退货期，需要人工审核。"
                );
            }

            if (!hasTool(steps, "knowledge_search")) {
                return AgentDecision.tool(
                        "先取得退货期限，不能凭空判断订单",
                        "knowledge_search",
                        Map.of("question", "商品签收后多少天可以无理由退货？")
                );
            }
            if (!hasToolInput(steps, "order_lookup", "ORD-1001")) {
                return AgentDecision.tool(
                        "已有政策，还需要 ORD-1001 的真实签收天数",
                        "order_lookup",
                        Map.of("orderId", "ORD-1001")
                );
            }
            return AgentDecision.answer(
                    "政策和订单两个 Observation 已经齐全",
                    "ORD-1001 已签收3天，仍在7天期限内，可以申请无理由退货。"
            );
        }

        /**
         * 判断历史中是否已经执行过某个工具，避免脚本重复选择相同步骤。
         */
        private boolean hasTool(List<AgentStep> steps, String toolName) {
            return steps.stream().anyMatch(step -> toolName.equals(step.toolName()));
        }

        /**
         * 判断特定参数的工具结果是否已经存在，恢复会话时可区分两张不同订单。
         */
        private boolean hasToolInput(List<AgentStep> steps, String toolName, String expectedText) {
            return steps.stream().anyMatch(step -> toolName.equals(step.toolName())
                    && step.toolInput() != null
                    && step.toolInput().contains(expectedText));
        }
    }
}
