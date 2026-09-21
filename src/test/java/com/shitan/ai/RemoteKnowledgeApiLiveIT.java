package com.shitan.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 12 课真实流程测试：本地 HTTP 站点提供原文，应用用真实百炼完成远程摄取和问答。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ragent.storage-root=target/lesson12-uploads",
                "ragent.remote-allowed-hosts=localhost"
        }
)
class RemoteKnowledgeApiLiveIT {

    private static HttpServer documentServer;
    private static int documentServerPort;

    @LocalServerPort
    private int applicationPort;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 启动一个只返回固定 Markdown 的轻量 HTTP 站点；它替代资料网站，不替代百炼模型。
     *
     * @throws IOException 本机端口监听失败
     */
    @BeforeAll
    static void startDocumentServer() throws IOException {
        String markdown = """
                # 差旅制度

                ## 高铁报销

                员工出差乘坐高铁二等座可以据实报销。
                """;
        documentServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        documentServer.createContext("/travel-policy.md", exchange -> {
            byte[] body = markdown.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/markdown; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        documentServer.start();
        documentServerPort = documentServer.getAddress().getPort();
    }

    /**
     * 测试类结束时关闭临时资料站点，释放随机端口。
     */
    @AfterAll
    static void stopDocumentServer() {
        if (documentServer != null) {
            documentServer.stop(0);
        }
    }

    /**
     * 一个用例跑满“登记 URL → fetch → parse → embedding → publish → 基于新知识回答”。
     *
     * @throws Exception 本地 HTTP、数据库、文件或真实百炼调用失败
     */
    @Test
    void shouldFetchRemoteDocumentIndexAndAnswer() throws Exception {
        JsonNode knowledgeBase = postJson("/api/knowledge-bases", """
                {"name":"远程制度知识库"}
                """);
        String knowledgeBaseId = knowledgeBase.path("id").asText();
        String sourceUrl = "http://localhost:" + documentServerPort + "/travel-policy.md";

        JsonNode accepted = postJson(
                "/api/knowledge-bases/" + knowledgeBaseId + "/remote-documents",
                """
                {"url":"%s"}
                """.formatted(sourceUrl)
        );
        assertEquals("pending", accepted.path("status").asText());

        String taskId = accepted.path("taskId").asText();
        String documentId = accepted.path("documentId").asText();
        JsonNode completedTask = waitForTerminalTask(taskId);
        assertEquals("completed", completedTask.path("status").asText());
        assertEquals("url", completedTask.path("sourceType").asText());
        assertEquals("remote-url", completedTask.path("pipelineName").asText());

        JsonNode steps = getJson("/api/ingestion-tasks/" + taskId + "/steps");
        assertEquals(4, steps.size());
        assertEquals("fetch", steps.path(0).path("stepName").asText());
        assertEquals("parse", steps.path(1).path("stepName").asText());
        assertEquals("embedding", steps.path(2).path("stepName").asText());
        assertEquals("publish", steps.path(3).path("stepName").asText());

        JsonNode document = getJson("/api/documents/" + documentId);
        assertEquals("success", document.path("status").asText());
        assertTrue(Files.exists(Path.of(document.path("storedPath").asText())));

        JsonNode answer = postJson("/api/questions", """
                {
                  "question":"出差坐高铁二等座可以报销吗？",
                  "knowledgeBaseId":"%s"
                }
                """.formatted(knowledgeBaseId));
        assertFalse(answer.path("answer").asText().isBlank());
        assertEquals("差旅制度 / 高铁报销", answer.path("sourceTitle").asText());

        System.out.println("远程任务：" + completedTask);
        System.out.println("四个节点日志：" + steps);
        System.out.println("远程知识回答：" + answer);
    }

    /**
     * 向本地 Spring 应用发送 JSON POST，并把成功响应解析成 Jackson 树。
     *
     * @param path     API 路径
     * @param jsonBody JSON 请求正文
     * @return 已解析响应
     * @throws Exception HTTP 或 JSON 处理失败
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
     * 模拟前端轮询，直到后台流程 completed/failed；超时会保留最后可见任务编号。
     *
     * @param taskId 受理接口返回的任务编号
     * @return 终态任务 JSON
     * @throws Exception HTTP、JSON 或等待中断
     */
    private JsonNode waitForTerminalTask(String taskId) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            JsonNode task = getJson("/api/ingestion-tasks/" + taskId);
            String status = task.path("status").asText();
            if ("completed".equals(status) || "failed".equals(status)) {
                return task;
            }
            Thread.sleep(200L);
        }
        throw new IllegalStateException("等待远程摄取任务超时：" + taskId);
    }

    /**
     * 发送 GET 并解析 JSON，用于读取任务、步骤和文档状态。
     *
     * @param path API 路径
     * @return 已解析响应
     * @throws Exception HTTP 或 JSON 处理失败
     */
    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(applicationUri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
    }

    /**
     * 用 Spring 随机端口拼出本次测试应用的完整 URL。
     *
     * @param path 以斜杠开头的 API 路径
     * @return 本次测试应用 URI
     */
    private URI applicationUri(String path) {
        return URI.create("http://localhost:" + applicationPort + path);
    }
}
