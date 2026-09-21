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
 * 第 13 课真实流程测试：两轮 HTTP 追问共享会话，旧消息滑出窗口后生成并保存摘要。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ragent.storage-root=target/lesson13-uploads",
                "ragent.memory.recent-turns=1",
                "ragent.memory.summary-trigger-turns=2",
                "ragent.memory.summary-max-chars=200"
        }
)
class ConversationQuestionApiLiveIT {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 跑满“上传知识 → 第一问建会话 → 第二问改写追问 → 保存消息 → 生成摘要 → 校验用户隔离”。
     *
     * @throws Exception 本地 HTTP、Docker 数据库、文件或真实百炼调用失败
     */
    @Test
    void shouldRememberFollowUpAndIsolateAnotherUser() throws Exception {
        JsonNode knowledgeBase = postJson("/api/knowledge-bases", """
                {"name":"会话记忆知识库"}
                """);
        String knowledgeBaseId = knowledgeBase.path("id").asText();

        String markdown = """
                # 公司制度

                ## 年假规则

                连续工作满一年后，每年有 5 天带薪年假，年假可以拆开使用。

                ## 访客预约

                外部访客需要至少提前一天预约。
                """;
        JsonNode accepted = uploadText(knowledgeBaseId, "company-memory.md", markdown);
        JsonNode completed = waitForTerminalTask(accepted.path("taskId").asText());
        assertEquals("completed", completed.path("status").asText());

        // 第一问没有 conversationId：服务创建会话，先保存 user 消息，再执行一次普通 RAG。
        JsonNode firstAnswer = postJson("/api/questions", """
                {
                  "question":"连续工作满一年有几天年假？",
                  "knowledgeBaseId":"%s",
                  "userId":"alice"
                }
                """.formatted(knowledgeBaseId));
        String conversationId = firstAnswer.path("conversationId").asText();
        assertFalse(conversationId.isBlank());
        assertEquals("公司制度 / 年假规则", firstAnswer.path("sourceTitle").asText());
        assertEquals("连续工作满一年有几天年假？", firstAnswer.path("rewrittenQuestion").asText());

        // 第二问故意省略“年假”主语；改写器应该把它补回后再交给 Embedding 检索。
        JsonNode followUp = postJson("/api/questions", """
                {
                  "question":"那可以拆开用吗？",
                  "knowledgeBaseId":"%s",
                  "conversationId":"%s",
                  "userId":"alice"
                }
                """.formatted(knowledgeBaseId, conversationId));
        assertEquals(conversationId, followUp.path("conversationId").asText());
        assertTrue(followUp.path("rewrittenQuestion").asText().contains("年假"),
                "模型改写应补回第一问的主题：" + followUp);
        assertEquals("公司制度 / 年假规则", followUp.path("sourceTitle").asText());
        assertFalse(followUp.path("answer").asText().isBlank());

        // 两轮共四条原始消息按数据库 BIGSERIAL 顺序保存，不能把 assistant 放到 user 前面。
        JsonNode messages = getJson(
                "/api/conversations/" + conversationId + "/messages?userId=alice"
        );
        assertEquals(4, messages.size());
        assertEquals("user", messages.path(0).path("role").asText());
        assertEquals("assistant", messages.path(1).path("role").asText());
        assertEquals("user", messages.path(2).path("role").asText());
        assertEquals("assistant", messages.path(3).path("role").asText());

        // 测试把近期原文窗口压到 1 轮，因此第一轮已滑出窗口，应变成数据库中的滚动摘要。
        JsonNode memory = getJson(
                "/api/conversations/" + conversationId + "/memory?userId=alice"
        );
        assertFalse(memory.path("summary").asText().isBlank());
        assertTrue(memory.path("lastSummaryMessageId").asLong() > 0);
        assertEquals(2, memory.path("recentMessages").size());
        assertEquals("user", memory.path("recentMessages").path(0).path("role").asText());

        // Bob 即使知道 conversationId，也不能读取 Alice 的历史；返回 404 而不是泄露会话存在性。
        HttpResponse<String> forbiddenHistory = getRaw(
                "/api/conversations/" + conversationId + "/messages?userId=bob"
        );
        assertEquals(404, forbiddenHistory.statusCode());

        System.out.println("第一问响应：" + firstAnswer);
        System.out.println("第二问及模型改写：" + followUp);
        System.out.println("按顺序保存的消息：" + messages);
        System.out.println("摘要和近期窗口：" + memory);
    }

    /**
     * 向真实 Spring 应用发送 JSON POST，并检查状态码属于成功范围。
     *
     * @param path     API 路径
     * @param jsonBody 原始 JSON 请求正文
     * @return 已解析的 JSON 响应
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
     * 发送 multipart 文本上传，复用真实 HTTP 协议建立本课的知识前置条件。
     *
     * @param knowledgeBaseId 目标知识库
     * @param filename         原始文件名
     * @param content          Markdown 正文
     * @return HTTP 202 返回的任务受理数据
     * @throws Exception HTTP、字节组装或 JSON 处理失败
     */
    private JsonNode uploadText(String knowledgeBaseId, String filename, String content)
            throws Exception {
        String boundary = "lesson13-boundary";
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
     * 像前端一样轮询后台任务，直到 parse、embedding 和 publish 全部完成或失败。
     *
     * @param taskId 上传接口返回的任务编号
     * @return 终态任务 JSON
     * @throws Exception HTTP、JSON 或等待中断
     */
    private JsonNode waitForTerminalTask(String taskId) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            JsonNode task = getJson("/api/ingestion-tasks/" + taskId);
            String status = task.path("status").asText();
            if ("completed".equals(status) || "failed".equals(status)) {
                assertEquals("completed", status, task.toString());
                return task;
            }
            Thread.sleep(200L);
        }
        throw new IllegalStateException("等待知识上传超时：" + taskId);
    }

    /**
     * 读取成功 JSON GET。
     *
     * @param path API 路径
     * @return 已解析的响应树
     * @throws Exception HTTP 或 JSON 处理失败
     */
    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = getRaw(path);
        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
    }

    /**
     * 发送 GET 并保留原始状态码，用于验证 Bob 的越权读取被转换成 404。
     *
     * @param path API 路径
     * @return 完整 HTTP 响应
     * @throws Exception HTTP 通信失败
     */
    private HttpResponse<String> getRaw(String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(applicationUri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
    }

    /**
     * 用 Spring 随机端口拼出本次测试应用的完整地址。
     *
     * @param path 以斜杠开头的 API 路径
     * @return 本地应用 URI
     */
    private URI applicationUri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
