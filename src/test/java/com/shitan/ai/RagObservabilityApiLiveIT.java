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
 * 第18课真实流程测试：一次问答同时产出稳定来源、Run/Node Trace 和可关联反馈。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ragent.storage-root=target/lesson18-uploads"
)
class RagObservabilityApiLiveIT {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 跑满“上传一份多片段文档 → 问答 → 来源去重 → 查询 Trace → 点踩 → 制造失败并定位节点”。
     */
    @Test
    void shouldExposeSourcesTraceFeedbackAndFailedNode() throws Exception {
        String baseId = postJson("/api/knowledge-bases", """
                {"name":"差旅观察知识库"}
                """).path("id").asText();
        uploadAndWait(baseId, "travel-observability.md", """
                # 差旅制度

                ## 交通报销

                高铁二等座可以凭电子票报销，飞机经济舱需要提前审批。

                ## 住宿报销

                一线城市住宿每晚不超过 600 元，其他城市每晚不超过 400 元。
                """);

        // 同一次检索选中交通、住宿两个 Chunk；二者来自同一文档，sources 应只出现一次。
        JsonNode answer = postJson("/api/questions", """
                {
                  "question":"交通怎么报销，住宿标准是多少？",
                  "knowledgeBaseId":"%s",
                  "userId":"alice"
                }
                """.formatted(baseId));
        String conversationId = answer.path("conversationId").asText();
        String runId = answer.path("runId").asText();
        long assistantMessageId = answer.path("assistantMessageId").asLong();
        assertFalse(runId.isBlank());
        assertTrue(assistantMessageId > 0);
        assertEquals(1, answer.path("sources").size(),
                "多个 Chunk 属于同一文档时，用户来源应按 documentId 去重：" + answer);
        assertFalse(answer.path("sources").path(0).path("sourceId").asText().isBlank());
        assertFalse(answer.path("sources").path(0).path("chunkIds").isEmpty());

        // 查询 runId 可以看到改写、意图、检索、生成和消息落库分别由哪个节点完成。
        JsonNode trace = getJson("/api/rag-runs/" + runId + "?userId=alice");
        assertEquals("completed", trace.path("run").path("status").asText());
        String nodeNames = trace.path("nodes").toString();
        assertTrue(nodeNames.contains("rewrite_question"), nodeNames);
        assertTrue(nodeNames.contains("intent_plan"), nodeNames);
        assertTrue(nodeNames.contains("retrieve_1"), nodeNames);
        assertTrue(nodeNames.contains("generate_1"), nodeNames);

        // 点踩通过 runId 关联到本次真正保存的 assistant 消息，而不是只保存一段孤立文字。
        JsonNode feedback = postJson("/api/rag-runs/" + runId + "/feedback", """
                {
                  "userId":"alice",
                  "rating":-1,
                  "reason":"来源正确，但希望把交通和住宿分点回答"
                }
                """);
        assertEquals(-1, feedback.path("rating").asInt());
        assertEquals(assistantMessageId, feedback.path("assistantMessageId").asLong());

        // 同一会话故意提交不存在的知识库；HTTP 返回 404，最近 Run 应停在 intent_plan 失败节点。
        HttpResponse<String> failed = postRaw("/api/questions", """
                {
                  "question":"再说一遍住宿标准",
                  "knowledgeBaseId":"missing-knowledge-base",
                  "conversationId":"%s",
                  "userId":"alice"
                }
                """.formatted(conversationId));
        assertEquals(404, failed.statusCode(), failed.body());

        JsonNode failedTrace = getJson(
                "/api/rag-runs/latest?conversationId=" + conversationId + "&userId=alice"
        );
        assertEquals("failed", failedTrace.path("run").path("status").asText());
        assertTrue(failedTrace.path("run").path("assistantMessageId").isNull());
        JsonNode lastNode = failedTrace.path("nodes")
                .path(failedTrace.path("nodes").size() - 1);
        assertEquals("intent_plan", lastNode.path("nodeName").asText());
        assertEquals("failed", lastNode.path("status").asText());

        System.out.println("按文档去重后的来源：" + answer.path("sources"));
        System.out.println("成功 Run 的节点：" + trace.path("nodes"));
        System.out.println("关联回答消息的反馈：" + feedback);
        System.out.println("失败 Run 定位结果：" + failedTrace);
    }

    /**
     * 上传 Markdown 并等待既有摄取 Pipeline 完成，保证问答前置数据已经发布。
     */
    private void uploadAndWait(String baseId, String filename, String content) throws Exception {
        String boundary = "lesson18-boundary";
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
     * 发送必须成功的 JSON POST，并返回解析后的正文。
     */
    private JsonNode postJson(String path, String json) throws Exception {
        HttpResponse<String> response = postRaw(path, json);
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        return objectMapper.readTree(response.body());
    }

    /**
     * 发送 JSON POST 并保留状态码，供失败追踪场景观察 HTTP 400。
     */
    private HttpResponse<String> postRaw(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * 发送必须成功的 GET，并返回解析后的 JSON。
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
     * 用 Spring 随机端口拼接本地测试 URI。
     */
    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
