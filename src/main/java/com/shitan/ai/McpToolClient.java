package com.shitan.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 使用 MCP 2026-07-28 的 Streamable HTTP 最小子集发现并调用远端工具。
 */
@Component
public class McpToolClient {

    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String RETURN_SKILL_CODE = "return_request";

    private final URI endpoint;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong requestIds = new AtomicLong();

    /**
     * 根据配置创建远端客户端；空 URL 表示测试或部署明确关闭 MCP。
     */
    public McpToolClient(@Value("${ragent.mcp.url:}") String endpoint) {
        this.endpoint = endpoint == null || endpoint.isBlank() ? null : URI.create(endpoint.strip());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * 调用 tools/list，把远端 Schema 翻译成当前 AgentTool；远端不可达时保留本地工具并继续启动。
     */
    public List<AgentTool> discoverAgentTools() {
        if (endpoint == null) {
            return List.of();
        }
        try {
            return listTools().stream()
                    .map(tool -> (AgentTool) new McpRemoteAgentTool(this, tool))
                    .toList();
        } catch (Exception exception) {
            return List.of();
        }
    }

    /**
     * 重新请求 tools/list 作为健康探测；不依赖已经缓存的启动期工具列表。
     */
    public McpConnectionHealth health() {
        if (endpoint == null) {
            return new McpConnectionHealth(false, false, 0, "未配置 ragent.mcp.url");
        }
        try {
            List<McpDiscoveredTool> tools = listTools();
            return new McpConnectionHealth(true, true, tools.size(), null);
        } catch (Exception exception) {
            return new McpConnectionHealth(true, false, 0, readableMessage(exception));
        }
    }

    /**
     * 按 MCP tools/call 协议发送一个工具名和参数，并提取第一段文本结果。
     */
    public String callTool(
            String toolName,
            Map<String, String> arguments,
            String userId
    ) throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", objectMapper.valueToTree(arguments));
        params.set("_meta", requestMeta(userId));
        JsonNode result = post("tools/call", toolName, params).path("result");
        JsonNode content = result.path("content");
        StringBuilder text = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(block.path("text").asText());
                }
            }
        }
        if (Boolean.TRUE.equals(result.path("isError").booleanValue())) {
            throw new IllegalStateException(text.isEmpty() ? "MCP 工具返回错误" : text.toString());
        }
        return text.isEmpty() ? "（远端工具没有返回文本）" : text.toString();
    }

    /**
     * 读取远端工具名称、必填参数和 readOnlyHint；写工具由主机绑定对应的 Skill。
     */
    private List<McpDiscoveredTool> listTools() throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.set("_meta", requestMeta(null));
        JsonNode tools = post("tools/list", null, params).path("result").path("tools");
        if (!tools.isArray()) {
            throw new IllegalStateException("MCP tools/list 响应中没有 tools 数组");
        }
        List<McpDiscoveredTool> result = new ArrayList<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText();
            if (name.isBlank()) {
                continue;
            }
            List<String> arguments = new ArrayList<>();
            JsonNode required = tool.path("inputSchema").path("required");
            if (required.isArray()) {
                required.forEach(node -> arguments.add(node.asText()));
            }
            boolean readOnly = tool.path("annotations").path("readOnlyHint").asBoolean(false);
            result.add(new McpDiscoveredTool(
                    name,
                    tool.path("description").asText(""),
                    arguments,
                    readOnly,
                    requiredSkill(name, readOnly)
            ));
        }
        return List.copyOf(result);
    }

    /**
     * 当前只有退货写工具需要专门手册；这是主机策略，不冒充 MCP 标准字段。
     */
    private String requiredSkill(String toolName, boolean readOnly) {
        return !readOnly && "return_request_create".equals(toolName)
                ? RETURN_SKILL_CODE
                : null;
    }

    /**
     * 发送一条自描述 JSON-RPC 请求，并校验 HTTP、JSON-RPC error 和响应编号。
     */
    private JsonNode post(String method, String name, ObjectNode params) throws Exception {
        long id = requestIds.incrementAndGet();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", method);
        body.set("params", params);

        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .header("Mcp-Method", method)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (name != null) {
            request.header("Mcp-Name", name);
        }
        HttpResponse<String> response = httpClient.send(
                request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("MCP HTTP " + response.statusCode() + "：" + response.body());
        }
        JsonNode root = objectMapper.readTree(response.body());
        if (root.has("error")) {
            throw new IllegalStateException("MCP JSON-RPC 错误：" + root.path("error").path("message").asText());
        }
        if (root.path("id").asLong(-1L) != id) {
            throw new IllegalStateException("MCP 响应 id 与请求不一致");
        }
        return root;
    }

    /**
     * 每个现代 MCP 请求都携带协议版本和客户端身份；userId 是本项目的非保留业务元数据。
     */
    private ObjectNode requestMeta(String userId) {
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION);
        ObjectNode clientInfo = meta.putObject("io.modelcontextprotocol/clientInfo");
        clientInfo.put("name", "ragent-st");
        clientInfo.put("version", "1.0.0");
        if (userId != null && !userId.isBlank()) {
            meta.put("userId", userId);
        }
        return meta;
    }

    /**
     * 从异常链提取最接近远端失败原因的文字，供健康接口展示。
     */
    private String readableMessage(Exception exception) {
        Throwable current = exception;
        String message = exception.getClass().getSimpleName();
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message;
    }
}
