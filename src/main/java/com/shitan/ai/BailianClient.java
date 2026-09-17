package com.shitan.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 直接调用阿里云百炼的 OpenAI 兼容 Embeddings 与 Chat Completions 接口。
 */
public final class BailianClient {

    private static final String EMBEDDING_MODEL = "text-embedding-v4";
    private static final String CHAT_MODEL = "qwen-plus-latest";
    private static final URI BAILIAN_BASE_URI =
            URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/");

    private final String apiKey;
    private final URI baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public BailianClient(String apiKey, URI baseUri) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("百炼 API Key 不能为空");
        }

        this.apiKey = apiKey;
        this.baseUri = normalizeBaseUri(baseUri);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 真实联网时从环境变量读取密钥，避免把密钥写进源码或 Git。
     */
    public static BailianClient fromEnvironment() {
        return new BailianClient(System.getenv("DASHSCOPE_API_KEY"), BAILIAN_BASE_URI);
    }

    public double[] createEmbedding(String text) throws IOException, InterruptedException {
        // 对应请求 JSON：{"model":"text-embedding-v4","input":"要向量化的文字"}
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", EMBEDDING_MODEL);
        requestBody.put("input", text);

        JsonNode responseBody = postJson("embeddings", requestBody);

        // 对应响应 JSON 的 data[0].embedding：先进入 data 数组，再取第一项，最后取向量。
        JsonNode embedding = responseBody.path("data").path(0).path("embedding");
        if (!embedding.isArray() || embedding.isEmpty()) {
            throw new IllegalStateException("Embedding 响应中没有向量数据");
        }

        // JSON 数组不能直接参与上一课的数学计算，因此逐个复制成 double[]。
        double[] vector = new double[embedding.size()];
        for (int index = 0; index < embedding.size(); index++) {
            vector[index] = embedding.get(index).asDouble();
        }
        return vector;
    }

    public String generateAnswer(String question, KnowledgeEntry evidence)
            throws IOException, InterruptedException {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", CHAT_MODEL);

        // Chat Completions 要求 messages 是数组，每个元素都包含 role 和 content。
        ArrayNode messages = requestBody.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", "你是企业知识助手。只能根据提供的证据回答；证据不足时必须明确说不知道。");
        messages.addObject()
                .put("role", "user")
                .put(
                        "content",
                        "问题：" + question
                                + "\n\n证据标题：" + evidence.title()
                                + "\n证据正文：" + evidence.content()
                );
        requestBody.put("max_tokens", 300);

        JsonNode responseBody = postJson("chat/completions", requestBody);

        // Chat Completions 把第一条候选回答放在 choices[0].message.content。
        JsonNode content = responseBody.path("choices").path(0).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new IllegalStateException("Chat Completions 响应中没有回答文字");
        }
        return content.asText();
    }

    private JsonNode postJson(String path, JsonNode requestBody)
            throws IOException, InterruptedException {
        // writeValueAsString 才是 Java JSON 树真正变成 HTTP 请求正文的时刻。
        String jsonText = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonText))
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // 百炼错误正文通常还包含错误码和 request_id，保留下来便于真实联网时定位问题。
            String errorBody = response.body();
            if (errorBody.length() > 1000) {
                errorBody = errorBody.substring(0, 1000) + "...";
            }
            throw new IllegalStateException(
                    "百炼请求失败，HTTP 状态码：" + response.statusCode()
                            + "，响应：" + errorBody
            );
        }

        // readTree 执行相反转换：把响应 JSON 字符串还原成可以逐层读取的 JsonNode 树。
        return objectMapper.readTree(response.body());
    }

    private URI normalizeBaseUri(URI originalBaseUri) {
        if (originalBaseUri == null) {
            throw new IllegalArgumentException("百炼服务地址不能为空");
        }

        String value = originalBaseUri.toString();
        return URI.create(value.endsWith("/") ? value : value + "/");
    }
}
