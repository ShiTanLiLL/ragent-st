package com.shitan.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BailianRagAssistantTest {

    @Test
    void shouldSendEmbeddingRequestAndParseReturnedVector() throws Exception {
        // Arrange：本机桩使用百炼 OpenAI 兼容接口的路径和 JSON 形状，但不访问公网、不产生费用。
        try (StubBailianServer stubServer = new StubBailianServer()) {
            BailianClient client = new BailianClient("test-key", stubServer.baseUri());

            // Act：把自然语言交给具体客户端，并接收桩服务返回的二维向量。
            double[] vector = client.createEmbedding("东西不想要了，几天能退？");

            // Assert：既检查解析结果，也检查发出的模型名、原始文本和认证头。
            assertArrayEquals(new double[]{1.0, 0.0}, vector, 0.000001);
            assertEquals(1, stubServer.embeddingRequests.size());
            assertEquals(
                    "text-embedding-v4",
                    stubServer.embeddingRequests.get(0).path("model").asText()
            );
            assertEquals(
                    "东西不想要了，几天能退？",
                    stubServer.embeddingRequests.get(0).path("input").asText()
            );
            assertEquals("Bearer test-key", stubServer.authorizationHeaders.get(0));
        }
    }

    @Test
    void shouldRetrieveOneEvidenceAndGenerateAnAnswerFromIt() throws Exception {
        // Arrange：两条知识的文本都会被向量化；桩服务让问题更接近退货政策。
        List<KnowledgeEntry> knowledgeEntries = sampleKnowledge();

        try (StubBailianServer stubServer = new StubBailianServer()) {
            BailianRagAssistant assistant = new BailianRagAssistant(
                    new BailianClient("test-key", stubServer.baseUri())
            );

            // Act：执行完整链路：知识向量化、问题向量化、Top-1 检索、按证据生成回答。
            KnowledgeAnswer answer = assistant.answer(
                    "东西不想要了，几天能退？",
                    knowledgeEntries
            );

            // Assert：最终正文来自生成接口，来源则必须是向量检索选中的退货政策。
            assertEquals("您可以在签收后 7 天内申请无理由退货。", answer.content());
            assertEquals("退货政策", answer.sourceTitle());

            // 两条知识加一个问题共调用三次 Embedding，随后只调用一次 Chat Completions。
            assertEquals(3, stubServer.embeddingRequests.size());
            assertEquals(1, stubServer.chatRequests.size());

            JsonNode chatRequest = stubServer.chatRequests.get(0);
            JsonNode messages = chatRequest.path("messages");
            String systemMessage = messages.path(0).path("content").asText();
            String userMessage = messages.path(1).path("content").asText();

            // system 消息保存长期回答规则，user 消息携带本次问题和检索证据。
            assertEquals("system", messages.path(0).path("role").asText());
            assertTrue(systemMessage.contains("只能根据提供的证据回答"));
            assertEquals("user", messages.path(1).path("role").asText());

            // 生成模型只能看到问题和选中的退货证据，不能看到未选中的保修正文。
            assertTrue(userMessage.contains("东西不想要了，几天能退？"));
            assertTrue(userMessage.contains("签收后 7 天内可申请无理由退货。"));
            assertFalse(userMessage.contains("电子产品自购买之日起享受 1 年保修。"));
            assertEquals("qwen-plus-latest", chatRequest.path("model").asText());

            // 四次 HTTP 请求都必须带认证头，但自动测试只使用假 Key。
            assertEquals(4, stubServer.authorizationHeaders.size());
            assertTrue(stubServer.authorizationHeaders.stream()
                    .allMatch("Bearer test-key"::equals));
        }
    }

    private List<KnowledgeEntry> sampleKnowledge() {
        return List.of(
                new KnowledgeEntry(
                        "退货政策",
                        "签收后 7 天内可申请无理由退货。",
                        List.of("退货", "退款")
                ),
                new KnowledgeEntry(
                        "保修政策",
                        "电子产品自购买之日起享受 1 年保修。",
                        List.of("保修", "质保")
                )
        );
    }

    /**
     * 这是自动测试基础设施，不是本课业务代码；当前只需知道它会记录请求并返回固定 JSON。
     */
    private static final class StubBailianServer implements AutoCloseable {

        private final ObjectMapper objectMapper = new ObjectMapper();
        private final HttpServer server;
        private final List<JsonNode> embeddingRequests = new ArrayList<>();
        private final List<JsonNode> chatRequests = new ArrayList<>();
        private final List<String> authorizationHeaders = new ArrayList<>();

        private StubBailianServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/compatible-mode/v1/embeddings", this::handleEmbedding);
            server.createContext("/compatible-mode/v1/chat/completions", this::handleChat);
            server.start();
        }

        private URI baseUri() {
            return URI.create(
                    "http://localhost:" + server.getAddress().getPort() + "/compatible-mode/v1/"
            );
        }

        private void handleEmbedding(HttpExchange exchange) throws IOException {
            JsonNode request = readRequest(exchange);
            embeddingRequests.add(request);
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));

            String input = request.path("input").asText();
            double[] vector;

            // 固定向量让自动测试结果可重复；真实联网验收不会执行这里。
            if (input.contains("东西不想要")) {
                vector = new double[]{1.0, 0.0};
            } else if (input.contains("退货政策")) {
                vector = new double[]{0.9, 0.1};
            } else {
                vector = new double[]{0.0, 1.0};
            }

            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode embedding = body.putArray("data")
                    .addObject()
                    .put("object", "embedding")
                    .put("index", 0)
                    .putArray("embedding");
            for (double value : vector) {
                embedding.add(value);
            }

            sendJson(exchange, body);
        }

        private void handleChat(HttpExchange exchange) throws IOException {
            JsonNode request = readRequest(exchange);
            chatRequests.add(request);
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));

            ObjectNode body = objectMapper.createObjectNode();
            body.putArray("choices")
                    .addObject()
                    .put("index", 0)
                    .putObject("message")
                    .put("role", "assistant")
                    .put("content", "您可以在签收后 7 天内申请无理由退货。");

            sendJson(exchange, body);
        }

        private JsonNode readRequest(HttpExchange exchange) throws IOException {
            return objectMapper.readTree(exchange.getRequestBody());
        }

        private void sendJson(HttpExchange exchange, JsonNode body) throws IOException {
            byte[] responseBytes = objectMapper.writeValueAsString(body)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
