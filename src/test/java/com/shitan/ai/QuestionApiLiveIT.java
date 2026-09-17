package com.shitan.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 第 6 课真实 Web 集成测试：启动真实 Tomcat，通过 HTTP 进入 Controller，Happy Path 继续访问真实百炼。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QuestionApiLiveIT {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 用一个请求跑完整链路，验证外部 JSON 能穿过真实 Web 服务、RAG 和百炼后返回回答与来源。
     *
     * @throws Exception 本地 HTTP、百炼网络或 JSON 解析失败时让测试直接报告真实原因
     */
    @Test
    void shouldAnswerThroughRealHttpAndBailianFlow() throws Exception {
        // 输入就是浏览器会发送的原始 JSON，不从 Java 内部直接调用 Controller。
        HttpResponse<String> response = postJson("""
                {"question":"东西不想要了，几天能退？"}
                """);

        // 先检查 HTTP 边界，再读取实际返回给浏览器的 JSON 中间数据。
        assertEquals(200, response.statusCode());
        JsonNode responseBody = objectMapper.readTree(response.body());
        assertFalse(responseBody.path("answer").asText().isBlank());
        assertEquals("退货政策", responseBody.path("sourceTitle").asText());

        System.out.println("HTTP 回答：" + responseBody.path("answer").asText());
        System.out.println("HTTP 证据来源：" + responseBody.path("sourceTitle").asText());
    }

    /**
     * 用同一个真实 Web 服务提交空问题，验证请求在调用百炼之前就以 HTTP 400 结束。
     *
     * @throws Exception 本地 HTTP 或 JSON 解析失败时让测试报告真实原因
     */
    @Test
    void shouldRejectBlankQuestionBeforeCallingBailian() throws Exception {
        HttpResponse<String> response = postJson("""
                {"question":"   "}
                """);

        assertEquals(400, response.statusCode());
        JsonNode responseBody = objectMapper.readTree(response.body());
        assertEquals("问题不能为空", responseBody.path("message").asText());
    }

    /**
     * 向随机端口上的真实应用发送 JSON POST，并把状态码与原始响应正文一起交给测试检查。
     *
     * @param jsonBody 浏览器准备发送的原始 JSON 字符串
     * @return 本地 Spring Boot 服务器返回的完整 HTTP 响应
     * @throws IOException          本地 HTTP 通信失败
     * @throws InterruptedException 等待本地 HTTP 响应时线程被中断
     */
    private HttpResponse<String> postJson(String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/questions")
                )
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
