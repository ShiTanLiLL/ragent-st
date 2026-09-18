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
 * 第 8 课真实流程测试：从 HTTP 创建知识库和上传文本，一直验证到新知识参与真实百炼问答。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ragent.storage-root=target/lesson8-uploads"
)
class KnowledgeUploadApiLiveIT {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 用一个用例跑满“创建知识库 → 上传两块知识 → 查看片段 → 按新知识问答”的 Happy Path。
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

        // 第二步：上传沿用第 3 课格式的 UTF-8 文本；两个空行把它切成两块知识。
        String documentText = """
                # 年假规则
                关键词：年假、休假
                员工连续工作满一年后，每年享有 5 天带薪年假。

                # 访客规则
                关键词：访客、预约
                外部访客进入办公室前，需要由接待员工提前一天完成预约。
                """;
        JsonNode uploaded = uploadText(knowledgeBaseId, "company-rules.txt", documentText);

        assertEquals("success", uploaded.path("status").asText());
        assertEquals(2, uploaded.path("chunkCount").asInt());
        assertTrue(uploaded.path("errorMessage").isNull());
        assertTrue(Files.exists(Path.of(uploaded.path("storedPath").asText())));

        String documentId = uploaded.path("id").asText();
        JsonNode documentStatus = getJson("/api/documents/" + documentId);
        assertEquals("success", documentStatus.path("status").asText());
        assertEquals(2, documentStatus.path("chunkCount").asInt());

        JsonNode chunks = getJson("/api/documents/" + documentId + "/chunks");
        assertEquals(2, chunks.size());

        // 片段保留知识库和文档归属；vectorDimension > 0 证明上传阶段确实调用百炼建立了索引。
        assertEquals(knowledgeBaseId, chunks.path(0).path("knowledgeBaseId").asText());
        assertEquals(documentId, chunks.path(0).path("documentId").asText());
        assertEquals("年假规则", chunks.path(0).path("title").asText());
        assertTrue(chunks.path(0).path("vectorDimension").asInt() > 0);

        // 第三步：问题明确指定刚创建的知识库；问答只需再向量化问题并检索已保存的片段向量。
        JsonNode answer = postJson("/api/questions", """
                {
                  "question":"员工工作满一年后，每年有几天带薪年假？",
                  "knowledgeBaseId":"%s"
                }
                """.formatted(knowledgeBaseId));

        assertFalse(answer.path("answer").asText().isBlank());
        assertEquals("年假规则", answer.path("sourceTitle").asText());

        System.out.println("上传后的文档状态：" + uploaded);
        System.out.println("生成的片段数据：" + chunks);
        System.out.println("基于新知识的回答：" + answer);
    }

    /**
     * 上传格式错误的文本，验证文档保留 failed 原因，同时原文件和半成品片段都被清理。
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
        JsonNode failed = uploadText(
                knowledgeBaseId,
                "broken.txt",
                "这不是符合当前课程格式的知识块"
        );

        assertEquals("failed", failed.path("status").asText());
        assertEquals(0, failed.path("chunkCount").asInt());
        assertFalse(failed.path("errorMessage").asText().isBlank());
        assertFalse(Files.exists(Path.of(failed.path("storedPath").asText())));

        JsonNode chunks = getJson("/api/documents/" + failed.path("id").asText() + "/chunks");
        assertEquals(0, chunks.size());

        System.out.println("失败文档状态：" + failed);
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
     * @return 上传 API 返回的文档 JSON
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

        assertEquals(201, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
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
