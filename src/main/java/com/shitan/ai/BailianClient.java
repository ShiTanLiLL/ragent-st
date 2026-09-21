package com.shitan.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 直接调用阿里云百炼的 OpenAI 兼容 Embeddings 与 Chat Completions 接口。
 */
public final class BailianClient {

    private static final String EMBEDDING_MODEL = "text-embedding-v4";
    private static final int EMBEDDING_DIMENSIONS = 1024;
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

    /**
     * 请求百炼把一段文本转换成固定 1024 维向量，与 PostgreSQL vector(1024) 列保持一致。
     *
     * @param text 要表示成语义向量的文本
     * @return 按百炼响应顺序复制出的 1024 维 Java 数组
     * @throws IOException          网络通信或 JSON 解析失败
     * @throws InterruptedException 等待百炼响应时当前线程被中断
     */
    public double[] createEmbedding(String text) throws IOException, InterruptedException {
        // 对应请求 JSON：{"model":"text-embedding-v4","input":"要向量化的文字"}
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", EMBEDDING_MODEL);
        requestBody.put("input", text);
        // 数据库列固定为 vector(1024)，所以请求中也明确维度，不依赖模型默认值。
        requestBody.put("dimensions", EMBEDDING_DIMENSIONS);

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

    /**
     * 根据摘要和最近原文补全省略主语的追问，只输出可独立向量化的一句话。
     *
     * @param question 用户本轮原始追问
     * @param memory   会话服务限制后的摘要和近期消息
     * @return 补齐上下文后的独立检索问题
     * @throws IOException          网络通信或 JSON 解析失败
     * @throws InterruptedException 等待百炼响应时线程被中断
     */
    public String rewriteQuestion(String question, ConversationMemory memory)
            throws IOException, InterruptedException {
        ArrayNode messages = objectMapper.createArrayNode();
        messages.addObject()
                .put("role", "system")
                .put("content", "你是企业知识问答的查询改写器。请结合历史，把当前追问补全成可以独立检索的一句话。"
                        + "保留用户真正想问的动作和限制条件，只输出改写后的问题，不要解释改写过程。");
        addMemoryMessages(messages, memory);
        messages.addObject()
                .put("role", "user")
                .put("content", "当前追问：" + question);
        return completeChat(messages, 120);
    }

    /**
     * 把已经滑出近期窗口的多轮消息压缩成短摘要，供下一次改写继续使用。
     *
     * @param existingSummary 之前保存的摘要，可为空
     * @param messages         本次新滑出的消息，按时间升序排列
     * @param maxChars         摘要最大字符数
     * @return 模型生成的滚动摘要
     * @throws IOException          网络通信或 JSON 解析失败
     * @throws InterruptedException 等待百炼响应时线程被中断
     */
    public String summarizeConversation(
            String existingSummary,
            List<ConversationMessage> messages,
            int maxChars
    ) throws IOException, InterruptedException {
        ArrayNode promptMessages = objectMapper.createArrayNode();
        promptMessages.addObject()
                .put("role", "system")
                .put("content", "你是企业知识助手的会话摘要器。保留用户目标、已确认事实、约束和未完成事项，"
                        + "去掉寒暄和重复内容。只输出一行摘要，严格不超过 " + maxChars + " 个字符。");
        if (existingSummary != null && !existingSummary.isBlank()) {
            promptMessages.addObject()
                    .put("role", "system")
                    .put("content", "已有摘要（只可合并和修正，不要凭空添加事实）：" + existingSummary);
        }
        for (ConversationMessage message : messages) {
            promptMessages.addObject()
                    .put("role", message.role().code())
                    .put("content", message.content());
        }
        promptMessages.addObject()
                .put("role", "user")
                .put("content", "请输出合并后的单行摘要，最多 " + maxChars + " 个字符。");
        return completeChat(promptMessages, Math.max(120, maxChars));
    }

    /**
     * 把有界记忆转换为 Chat Completions 的 messages 数组；摘要用 system 表达，原文保留角色。
     *
     * @param messages 要继续填充的 JSON 数组
     * @param memory   当前会话记忆
     */
    private void addMemoryMessages(ArrayNode messages, ConversationMemory memory) {
        if (memory.summary() != null && !memory.summary().isBlank()) {
            messages.addObject()
                    .put("role", "system")
                    .put("content", "历史摘要：" + memory.summary());
        }
        for (ConversationMessage message : memory.recentMessages()) {
            messages.addObject()
                    .put("role", message.role().code())
                    .put("content", message.content());
        }
    }

    /**
     * 发送一次非流式 Chat Completions，并读取 choices[0].message.content。
     *
     * @param messages 已按 OpenAI 角色格式组织的消息数组
     * @param maxTokens 本次生成上限
     * @return 非空模型正文
     * @throws IOException          网络通信或 JSON 解析失败
     * @throws InterruptedException 等待百炼响应时线程被中断
     */
    private String completeChat(ArrayNode messages, int maxTokens)
            throws IOException, InterruptedException {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", CHAT_MODEL);
        requestBody.set("messages", messages);
        requestBody.put("max_tokens", maxTokens);
        JsonNode responseBody = postJson("chat/completions", requestBody);
        JsonNode content = responseBody.path("choices").path(0).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new IllegalStateException("Chat Completions 响应中没有回答文字");
        }
        return content.asText().strip();
    }

    /**
     * 请求百炼以 OpenAI SSE 格式增量生成答案，并把每个 delta.content 立即交给调用方。
     *
     * @param question  用户问题
     * @param evidence  本地向量检索选出的证据
     * @param onChunk   接收每一段新增回答文字的函数
     * @param cancelled 用于在用户取消后停止读取模型流
     * @throws IOException          网络、SSE 读取或 JSON 解析失败
     * @throws InterruptedException 等待百炼建立流式响应时线程被中断
     */
    public void streamAnswer(
            String question,
            KnowledgeEntry evidence,
            Consumer<String> onChunk,
            BooleanSupplier cancelled
    ) throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) {
            return;
        }

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", CHAT_MODEL);
        requestBody.put("stream", true);

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

        String jsonText = objectMapper.writeValueAsString(requestBody);
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonText))
                .build();

        HttpResponse<InputStream> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );

        try (InputStream responseBody = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = new String(responseBody.readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException(
                        "百炼流式请求失败，HTTP 状态码：" + response.statusCode()
                                + "，响应：" + errorBody
                );
            }

            readChatStream(responseBody, onChunk, cancelled);
        }
    }

    /**
     * 逐行读取百炼 SSE：忽略空行，解析 data 后的 JSON，并取出 choices[0].delta.content。
     *
     * @param responseBody 百炼尚未一次性读完的响应流
     * @param onChunk      接收新增回答片段的函数
     * @param cancelled    用户取消状态
     * @throws IOException 读取响应流或解析某一行 JSON 失败
     */
    private void readChatStream(
            InputStream responseBody,
            Consumer<String> onChunk,
            BooleanSupplier cancelled
    ) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseBody, StandardCharsets.UTF_8)
        )) {
            String line;
            while (!cancelled.getAsBoolean() && (line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }

                String data = line.substring("data:".length()).trim();
                if ("[DONE]".equals(data)) {
                    return;
                }

                JsonNode content = objectMapper.readTree(data)
                        .path("choices")
                        .path(0)
                        .path("delta")
                        .path("content");
                if (content.isTextual() && !content.asText().isEmpty()) {
                    onChunk.accept(content.asText());
                }
            }
        }

        if (!cancelled.getAsBoolean()) {
            throw new IOException("百炼流式响应在 [DONE] 之前结束");
        }
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
