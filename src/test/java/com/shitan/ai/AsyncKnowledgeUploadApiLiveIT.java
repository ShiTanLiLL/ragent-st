package com.shitan.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * 第 10 课真实流程测试：HTTP 上传立即受理，后台完成真实百炼摄取后，新知识可以参与问答。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ragent.storage-root=target/lesson10-uploads"
)
class AsyncKnowledgeUploadApiLiveIT {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 用一个用例跑满“创建知识库 → 快速受理上传 → 查询后台完成 → 按新知识问答”的 Happy Path。
     *
     * @throws Exception 本地 HTTP、文件读写、JSON 解析或真实百炼调用失败时报告原始原因
     */
    @Test
    void shouldUploadIndexAndAnswerFromNewKnowledge() throws Exception {
        // 第一步：运营人员先创建容器，后续文档、片段和问答都使用返回的 knowledgeBaseId。
        JsonNode knowledgeBase = postJson("/api/knowledge-bases", """
                {"name":"公司内部制度"}
                """);
        String knowledgeBaseId = knowledgeBase.path("id").asText();
        assertFalse(knowledgeBaseId.isBlank());

        // 第二步：上传真实 Markdown。标题层级决定片段来源，表格必须保持列和值的关系。
        String documentText = """
                # 公司制度

                ## 年假规则

                员工连续工作满一年后，每年享有 5 天带薪年假。

                ## 访客预约时限

                | 访客类型 | 最晚预约时间 |
                | --- | --- |
                | 外部访客 | 到访前一天 |
                | 面试候选人 | 到访前两小时 |
                """;
        JsonNode accepted = uploadText(knowledgeBaseId, "company-rules.md", documentText);

        // HTTP 202 只返回任务和文档编号；此刻不会等待两个 Embedding 全部完成。
        assertEquals("pending", accepted.path("status").asText());
        String taskId = accepted.path("taskId").asText();
        String documentId = accepted.path("documentId").asText();
        assertFalse(taskId.isBlank());
        assertFalse(documentId.isBlank());

        // 浏览器之后轮询任务；completed 表示后台 parse、embedding、publish 都已结束。
        JsonNode completedTask = waitForTerminalTask(taskId);
        assertEquals("completed", completedTask.path("status").asText());
        assertEquals(1, completedTask.path("attemptCount").asInt());

        JsonNode documentStatus = getJson("/api/documents/" + documentId);
        assertEquals("success", documentStatus.path("status").asText());
        assertEquals(2, documentStatus.path("chunkCount").asInt());
        assertTrue(Files.exists(Path.of(documentStatus.path("storedPath").asText())));

        JsonNode chunks = getJson("/api/documents/" + documentId + "/chunks");
        assertEquals(2, chunks.size());

        JsonNode steps = getJson("/api/ingestion-tasks/" + taskId + "/steps");
        assertEquals(3, steps.size());
        assertEquals("parse", steps.path(0).path("stepName").asText());
        assertEquals("embedding", steps.path(1).path("stepName").asText());
        assertEquals("publish", steps.path(2).path("stepName").asText());

        // 片段保留知识库和文档归属；vectorDimension > 0 证明上传阶段确实调用百炼建立了索引。
        assertEquals(0, chunks.path(0).path("chunkIndex").asInt());
        assertEquals(1, chunks.path(1).path("chunkIndex").asInt());
        assertEquals(knowledgeBaseId, chunks.path(0).path("knowledgeBaseId").asText());
        assertEquals(documentId, chunks.path(0).path("documentId").asText());
        assertEquals("公司制度 / 年假规则", chunks.path(0).path("title").asText());
        assertTrue(chunks.path(0).path("embeddingText").asText().contains("公司制度 > 年假规则"));
        assertTrue(chunks.path(1).path("content").asText().contains("| 访客类型 | 最晚预约时间 |"));
        assertTrue(chunks.path(1).path("embeddingText").asText().contains("访客类型：外部访客；最晚预约时间：到访前一天"));
        assertTrue(chunks.path(0).path("vectorDimension").asInt() > 0);

        // 第三步：问题明确指定刚创建的知识库；问答只需再向量化问题并检索已保存的片段向量。
        JsonNode answer = postJson("/api/questions", """
                {
                  "question":"员工工作满一年后，每年有几天带薪年假？",
                  "knowledgeBaseId":"%s"
                }
                """.formatted(knowledgeBaseId));

        assertFalse(answer.path("answer").asText().isBlank());
        assertEquals("公司制度 / 年假规则", answer.path("sourceTitle").asText());

        System.out.println("上传接口立即返回：" + accepted);
        System.out.println("后台任务最终状态：" + completedTask);
        System.out.println("三个阶段记录：" + steps);
        System.out.println("生成的片段数据：" + chunks);
        System.out.println("基于新知识的回答：" + answer);
    }

    /**
     * 上传格式错误的文本，验证后台 parse 失败、原文件为重试保留且没有半成品片段。
     *
     * @throws Exception 本地 HTTP、文件检查或 JSON 解析失败时报告原始原因
     */
    @Test
    void shouldExposeFailureAndCleanPartialData() throws Exception {
        JsonNode knowledgeBase = postJson("/api/knowledge-bases", """
                {"name":"失败场景知识库"}
                """);
        String knowledgeBaseId = knowledgeBase.path("id").asText();

        // 缺少“标题、关键词、正文”结构，文件读取器会在调用百炼前明确失败。
        JsonNode accepted = uploadText(
                knowledgeBaseId,
                "broken.txt",
                "这不是符合当前课程格式的知识块"
        );

        JsonNode failedTask = waitForTerminalTask(accepted.path("taskId").asText());
        JsonNode failedDocument = getJson(
                "/api/documents/" + accepted.path("documentId").asText()
        );

        assertEquals("failed", failedTask.path("status").asText());
        assertEquals("parse", failedTask.path("currentStep").asText());
        assertFalse(failedTask.path("errorMessage").asText().isBlank());
        assertEquals("failed", failedDocument.path("status").asText());
        assertEquals(0, failedDocument.path("chunkCount").asInt());
        assertTrue(Files.exists(Path.of(failedDocument.path("storedPath").asText())));

        JsonNode chunks = getJson(
                "/api/documents/" + accepted.path("documentId").asText() + "/chunks"
        );
        assertEquals(0, chunks.size());

        System.out.println("失败任务状态：" + failedTask);
        System.out.println("失败文档状态：" + failedDocument);
    }

    /**
     * 向本地应用发送 JSON POST，要求成功状态并返回解析后的响应对象。
     *
     * @param path     API 路径
     * @param jsonBody 原始 JSON 请求正文
     * @return 已解析的 JSON 响应
     * @throws IOException          本地 HTTP 或 JSON 解析失败
     * @throws InterruptedException 等待本地响应时线程被中断
     */
    private JsonNode postJson(String path, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(apiUri(path))
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
     * 手工组装一个只有 file 部件的 multipart 请求，以真实浏览器协议上传 UTF-8 文本。
     *
     * @param knowledgeBaseId 目标知识库编号
     * @param filename        浏览器提交的原始文件名
     * @param content         文件正文
     * @return 上传 API 立即返回的任务受理 JSON
     * @throws IOException          组装字节、本地 HTTP 或 JSON 解析失败
     * @throws InterruptedException 等待本地响应时线程被中断
     */
    private JsonNode uploadText(String knowledgeBaseId, String filename, String content)
            throws IOException, InterruptedException {
        String boundary = "lesson8-boundary";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        body.write("Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8));
        body.write(content.getBytes(StandardCharsets.UTF_8));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(
                        apiUri("/api/knowledge-bases/" + knowledgeBaseId + "/documents")
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
     * 像浏览器轮询进度一样读取任务，直到 completed/failed 或超过 60 秒。
     *
     * @param taskId 上传接口返回的任务编号
     * @return 最终任务 JSON
     * @throws IOException          本地 HTTP 或 JSON 解析失败
     * @throws InterruptedException 轮询等待被中断
     */
    private JsonNode waitForTerminalTask(String taskId)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            JsonNode task = getJson("/api/ingestion-tasks/" + taskId);
            String status = task.path("status").asText();
            if ("completed".equals(status) || "failed".equals(status)) {
                return task;
            }
            Thread.sleep(200L);
        }
        throw new IllegalStateException("等待后台摄取任务超时：" + taskId);
    }

    /**
     * 读取一个本地 JSON API，并把正文转换成测试可逐层检查的 Jackson 树。
     *
     * @param path API 路径
     * @return 已解析的 JSON 响应
     * @throws IOException          本地 HTTP 或 JSON 解析失败
     * @throws InterruptedException 等待本地响应时线程被中断
     */
    private JsonNode getJson(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(apiUri(path)).GET().build();
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
    }

    /**
     * 用 Spring 测试启动后注入的随机端口创建本地 API 地址。
     *
     * @param path 以斜杠开头的 API 路径
     * @return 指向本次测试应用的完整 URI
     */
    private URI apiUri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
