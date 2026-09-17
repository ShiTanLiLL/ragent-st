package com.shitan.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 7 课真实流式集成测试：通过本地 HTTP/SSE 观察真实百炼增量，并验证 taskId 取消路径。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamingQuestionApiLiveIT {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 用一个真实问题跑完整 SSE 链路，检查 meta 最先到达、message 可以拼接、done 最后收尾。
     *
     * @throws Exception 本地 HTTP、百炼网络、SSE 读取或 JSON 解析失败时报告真实原因
     */
    @Test
    void shouldReceiveRealAnswerAsOrderedSseEvents() throws Exception {
        HttpResponse<InputStream> response = openStream("请详细说明退货期限和条件。");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("text/event-stream"));

        try (BufferedReader reader = utf8Reader(response.body())) {
            List<ReceivedEvent> events = readUntilDone(reader);

            // 第一条 meta 提供 taskId，页面拿到它以后才有能力取消当前生成。
            assertFalse(events.isEmpty());
            assertEquals("meta", events.get(0).name());
            String taskId = objectMapper.readTree(events.get(0).data()).path("taskId").asText();
            assertFalse(taskId.isBlank());

            // 每条 message 只是一段新增文字；按收到顺序拼接才得到完整答案。
            StringBuilder answer = new StringBuilder();
            int messageCount = 0;
            for (ReceivedEvent event : events) {
                if ("message".equals(event.name())) {
                    String chunk = objectMapper.readTree(event.data()).path("content").asText();
                    answer.append(chunk);
                    messageCount++;
                    System.out.println("收到回答片段：" + chunk);
                }
            }
            assertTrue(messageCount > 0);
            assertFalse(answer.toString().isBlank());

            // 最后一条 done 表示不再有后续片段，并携带本地检索选中的证据来源。
            ReceivedEvent done = events.get(events.size() - 1);
            assertEquals("done", done.name());
            JsonNode doneData = objectMapper.readTree(done.data());
            assertFalse(doneData.path("cancelled").asBoolean());
            assertEquals("退货政策", doneData.path("sourceTitle").asText());

            System.out.println("拼接后的完整回答：" + answer);
            System.out.println("本次流式任务 ID：" + taskId);
        }
    }

    /**
     * 收到 meta 后立即用 taskId 取消任务，验证服务只用一条 cancelled=true 的 done 收尾。
     *
     * @throws Exception 本地 HTTP、SSE 读取或 JSON 解析失败时报告真实原因
     */
    @Test
    void shouldCancelRunningStreamByTaskId() throws Exception {
        HttpResponse<InputStream> response = openStream("请写一份非常详细的退货政策说明。");
        assertEquals(200, response.statusCode());

        try (BufferedReader reader = utf8Reader(response.body())) {
            ReceivedEvent meta = readNextEvent(reader);
            assertNotNull(meta);
            assertEquals("meta", meta.name());

            String taskId = objectMapper.readTree(meta.data()).path("taskId").asText();
            HttpResponse<Void> cancelResponse = cancel(taskId);
            assertEquals(204, cancelResponse.statusCode());

            List<ReceivedEvent> remainingEvents = readUntilDone(reader);
            ReceivedEvent done = remainingEvents.get(remainingEvents.size() - 1);
            assertEquals("done", done.name());
            assertTrue(objectMapper.readTree(done.data()).path("cancelled").asBoolean());

            System.out.println("已经取消流式任务：" + taskId);
        }
    }

    /**
     * 向流式端点发送问题，并在响应正文仍保持打开时把 InputStream 交给测试逐行读取。
     *
     * @param question 要提交给知识助手的问题
     * @return 状态码、响应头和尚未读完的 SSE 字节流
     * @throws IOException          本地 HTTP 通信失败
     * @throws InterruptedException 等待服务器响应头时线程被中断
     */
    private HttpResponse<InputStream> openStream(String question)
            throws IOException, InterruptedException {
        String requestJson = objectMapper.createObjectNode()
                .put("question", question)
                .toString();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/questions/stream")
                )
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    /**
     * 调用取消端点；DELETE 没有请求正文，taskId 直接放在 URL 路径中。
     *
     * @param taskId meta 事件提供的运行任务编号
     * @return 只需要检查状态码的取消响应
     * @throws IOException          本地 HTTP 通信失败
     * @throws InterruptedException 等待取消响应时线程被中断
     */
    private HttpResponse<Void> cancel(String taskId)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/questions/stream/" + taskId)
                )
                .DELETE()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }

    /**
     * 用 UTF-8 读取 SSE，避免中文回答依赖操作系统默认编码。
     *
     * @param inputStream 尚未读取完的 HTTP 响应体
     * @return 可以按行读取 event、data 和空行分隔符的 reader
     */
    private BufferedReader utf8Reader(InputStream inputStream) {
        return new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    /**
     * 连续读取事件直到遇到 done，保留实际到达顺序供测试检查。
     *
     * @param reader SSE 响应读取器
     * @return 从当前位置到 done 为止的全部事件
     * @throws IOException SSE 连接读取失败或在 done 前意外断开
     */
    private List<ReceivedEvent> readUntilDone(BufferedReader reader) throws IOException {
        List<ReceivedEvent> events = new ArrayList<>();
        ReceivedEvent event;
        while ((event = readNextEvent(reader)) != null) {
            events.add(event);
            if ("done".equals(event.name())) {
                return events;
            }
        }
        throw new IOException("SSE 连接在 done 事件之前结束");
    }

    /**
     * 读取一个由空行分隔的 SSE 事件，并分别提取 event 名称和 data JSON。
     *
     * @param reader SSE 响应读取器
     * @return 下一条完整事件；连接正常结束且没有剩余事件时返回 null
     * @throws IOException 读取连接失败
     */
    private ReceivedEvent readNextEvent(BufferedReader reader) throws IOException {
        String eventName = null;
        StringBuilder data = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (eventName != null) {
                    return new ReceivedEvent(eventName, data.toString());
                }
                continue;
            }
            if (line.startsWith("event:")) {
                eventName = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).trim());
            }
        }

        return eventName == null ? null : new ReceivedEvent(eventName, data.toString());
    }

    /**
     * 测试读取到的一条原始 SSE 事件，保留事件名称和尚未解析的 JSON 文本。
     *
     * @param name event 行中的事件名称
     * @param data data 行中的 JSON 文本
     */
    private record ReceivedEvent(
            String name,
            String data
    ) {
    }
}
