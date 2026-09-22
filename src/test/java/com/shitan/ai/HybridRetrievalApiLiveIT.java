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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 第 15 课真实流程测试：同一份知识分别用精确词和语义向量召回，再融合后交给答案模型。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ragent.storage-root=target/lesson15-uploads"
)
class HybridRetrievalApiLiveIT {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 跑满“上传两类知识 → 编号问题走关键词与向量并行召回 → 自然语言问题走向量召回 → 统一证据回答”。
     *
     * @throws Exception HTTP、Testcontainers、文件或真实百炼调用失败
     */
    @Test
    void shouldCombineExactCodeAndSemanticRetrieval() throws Exception {
        String baseId = postJson("/api/knowledge-bases", """
                {"name":"设备售后"}
                """).path("id").asText();

        uploadAndWait(baseId, "device.md", """
                # 设备售后

                ## 故障代码 WX-2048

                遇到 WX-2048 时请先断电 30 秒，再重新启动。

                ## 无法开机

                设备按下电源键没有反应时，请检查电源线。
                """);

        // 编号 WX-2048 需要关键词通道保护精确命中；向量通道仍会同时给出语义候选。
        JsonNode codeAnswer = postJson("/api/questions", """
                {
                  "question":"设备报错 WX-2048 怎么处理？",
                  "knowledgeBaseId":"%s",
                  "userId":"alice"
                }
                """.formatted(baseId));
        assertFalse(codeAnswer.path("evidence").isEmpty(), codeAnswer.toString());
        assertTrue(codeAnswer.path("evidence").toString().contains("WX-2048"),
                "编号问题的证据中应能看到精确错误码：" + codeAnswer);
        assertTrue(codeAnswer.path("evidence").toString().contains("keyword"),
                "编号问题应观察到关键词召回通道：" + codeAnswer);
        assertFalse(codeAnswer.path("answer").asText().isBlank());

        // “按电源键没有反应”没有固定编号，主要依赖向量通道理解自然语言近义表达。
        JsonNode semanticAnswer = postJson("/api/questions", """
                {
                  "question":"设备按电源键没有反应怎么办？",
                  "knowledgeBaseId":"%s",
                  "userId":"bob"
                }
                """.formatted(baseId));
        assertFalse(semanticAnswer.path("evidence").isEmpty(), semanticAnswer.toString());
        assertTrue(semanticAnswer.path("evidence").toString().contains("vector"),
                "自然语言问题应观察到向量召回通道：" + semanticAnswer);
        assertFalse(semanticAnswer.path("answer").asText().isBlank());

        System.out.println("编号问题的混合证据（含 channel、vectorScore、keywordScore、rrfScore、rerankScore）："
                + codeAnswer.path("evidence"));
        System.out.println("自然语言问题的混合证据：" + semanticAnswer.path("evidence"));
    }

    /**
     * 上传 Markdown 并轮询第 12 课摄取任务，确保问答只读取已经发布的 success 片段。
     */
    private void uploadAndWait(String baseId, String filename, String content) throws Exception {
        JsonNode accepted = uploadText(baseId, filename, content);
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
     * 发送 JSON 请求；测试通过真实 HTTP 入口观察 Controller、路由和回答响应。
     */
    private JsonNode postJson(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(applicationUri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        return objectMapper.readTree(response.body());
    }

    /**
     * 组装 multipart 上传，复用正式知识摄取入口而不直接调用 Service。
     */
    private JsonNode uploadText(String baseId, String filename, String content) throws Exception {
        String boundary = "lesson15-boundary";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        body.write("Content-Type: text/markdown; charset=UTF-8\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8));
        body.write(content.getBytes(StandardCharsets.UTF_8));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(
                        applicationUri("/api/knowledge-bases/" + baseId + "/documents"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(202, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
    }

    /**
     * 读取摄取任务状态，供轮询和测试前置条件使用。
     */
    private JsonNode getJson(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(applicationUri(path)).GET().build();
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
    }

    /**
     * 用 Spring Boot 测试随机端口拼接本次应用的 HTTP 地址。
     */
    private URI applicationUri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
