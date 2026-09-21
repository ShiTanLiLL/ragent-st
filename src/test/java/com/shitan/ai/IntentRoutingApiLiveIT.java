package com.shitan.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 14 课真实流程测试：两个领域问题分别定向到两个知识库，并观察低置信回落和同名歧义。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ragent.storage-root=target/lesson14-uploads"
)
class IntentRoutingApiLiveIT {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 跑满“创建领域库 → 上传知识 → 多问题拆分 → 定向检索 → 低置信回落 → 同名意图澄清”。
     *
     * @throws Exception 本地 HTTP、Docker 数据库、文件或真实百炼调用失败
     */
    @Test
    void shouldRouteSubQuestionsAndExplainAmbiguity() throws Exception {
        String hrBaseId = createBase("人事/年假").path("id").asText();
        String financeBaseId = createBase("财务/报销").path("id").asText();
        uploadAndWait(hrBaseId, "leave.md", "# 年假规则\n\n连续工作满一年有 5 天带薪年假。");
        uploadAndWait(financeBaseId, "expense.md", "# 报销规则\n\n差旅报销需要发票和出差申请单。");

        // 不传 knowledgeBaseId：规划器根据每个子问题中的领域词分别选择两个知识库。
        JsonNode multiAnswer = postJson("/api/questions", """
                {
                  "question":"年假有几天，并且报销需要什么材料？",
                  "userId":"alice"
                }
                """);
        assertEquals(2, multiAnswer.path("intentPlans").size());
        assertEquals(1, multiAnswer.path("intentPlans").path(0).path("knowledgeBaseIds").size());
        assertEquals(1, multiAnswer.path("intentPlans").path(1).path("knowledgeBaseIds").size());
        assertTrue(multiAnswer.path("sourceTitle").asText().contains("年假规则"));
        assertTrue(multiAnswer.path("sourceTitle").asText().contains("报销规则"));
        assertFalse(multiAnswer.path("answer").asText().isBlank());

        // 没有任何领域词时不武断地拒绝，而是把作用域置空，交给全库向量检索。
        JsonNode fallbackAnswer = postJson("/api/questions", """
                {
                  "question":"公司制度如何更新？",
                  "userId":"carol"
                }
                """);
        assertTrue(fallbackAnswer.path("intentPlans").path(0).path("fallbackToAll").asBoolean());
        assertEquals(0, fallbackAnswer.path("intentPlans").path(0).path("knowledgeBaseIds").size());

        // 再创建一个同名领域，下一次“年假”不应该偷偷挑一个库，而应该先让用户澄清。
        createBase("福利/年假");
        JsonNode ambiguous = postJson("/api/questions", """
                {
                  "question":"年假怎么申请？",
                  "userId":"bob"
                }
                """);
        assertTrue(ambiguous.path("intentPlans").path(0).path("clarificationRequired").asBoolean());
        assertTrue(ambiguous.path("answer").asText().contains("多个知识库"));
        assertTrue(ambiguous.path("sourceTitle").isNull());

        System.out.println("多问题路由结果：" + multiAnswer);
        System.out.println("低置信度回落结果：" + fallbackAnswer);
        System.out.println("同名意图澄清结果：" + ambiguous);
    }

    /**
     * 创建一个带路径名称的知识库；名称中的“人事/年假”就是本课最小意图树路径。
     */
    private JsonNode createBase(String name) throws Exception {
        return postJson("/api/knowledge-bases", "{" +
                "\"name\":\"" + name + "\"}");
    }

    /**
     * 通过真实 HTTP 上传，并轮询第 12 课已经实现的节点摄取流程。
     */
    private void uploadAndWait(String knowledgeBaseId, String filename, String content)
            throws Exception {
        JsonNode accepted = uploadText(knowledgeBaseId, filename, content);
        String taskId = accepted.path("taskId").asText();
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
     * 向真实 Spring 应用发送 JSON POST。
     */
    private JsonNode postJson(String path, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(applicationUri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        return objectMapper.readTree(response.body());
    }

    /**
     * 发送 multipart Markdown 文件，复用真实摄取通路。
     */
    private JsonNode uploadText(String knowledgeBaseId, String filename, String content)
            throws Exception {
        String boundary = "lesson14-boundary";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        body.write("Content-Type: text/markdown; charset=UTF-8\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8));
        body.write(content.getBytes(StandardCharsets.UTF_8));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(
                        applicationUri("/api/knowledge-bases/" + knowledgeBaseId + "/documents")
                )
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(202, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
    }

    /**
     * 读取一个成功 JSON GET。
     */
    private JsonNode getJson(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(applicationUri(path)).GET().build();
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
    }

    /**
     * 根据随机端口拼接当前 Spring 测试应用的地址。
     */
    private URI applicationUri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
