package com.shitan.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可独立运行的教学 MCP Server，提供一个只读订单工具和一个会创建数据的退货工具。
 */
public final class McpOrderServer implements AutoCloseable {

    public static final String PROTOCOL_VERSION = "2026-07-28";

    private static final Map<String, OrderSnapshot> ORDERS = Map.of(
            "ORD-1001", new OrderSnapshot("ORD-1001", "delivered", 3, "耳机"),
            "ORD-1002", new OrderSnapshot("ORD-1002", "delivered", 12, "键盘")
    );

    private final HttpServer server;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger returnSequence = new AtomicInteger(1000);
    private final Map<String, String> returnRequests = new ConcurrentHashMap<>();

    /**
     * 绑定指定端口；传0时由操作系统选择空闲端口，便于测试并行运行。
     */
    private McpOrderServer(int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.createContext("/mcp", this::handleMcp);
        server.createContext("/health", this::handleHealth);
    }

    /**
     * 创建并启动真实 HTTP Server，返回可查询端口和调用次数的运行实例。
     */
    public static McpOrderServer start(int port) throws IOException {
        McpOrderServer result = new McpOrderServer(port);
        result.server.start();
        return result;
    }

    /**
     * 返回 Agent 主应用需要配置的 Streamable HTTP 地址。
     */
    public URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    /**
     * 返回已经真正创建的退货申请数，测试用它证明确认前没有副作用。
     */
    public int createdReturnCount() {
        return returnRequests.size();
    }

    /**
     * 处理 MCP JSON-RPC 请求；当前只实现本课需要的 discover、tools/list 和 tools/call。
     */
    private void handleMcp(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendPlain(exchange, 405, "只支持 POST");
            return;
        }
        JsonNode request;
        try {
            request = objectMapper.readTree(exchange.getRequestBody());
        } catch (Exception exception) {
            sendJson(exchange, 400, jsonRpcError(null, -32700, "JSON 解析失败"));
            return;
        }

        JsonNode id = request.get("id");
        String method = request.path("method").asText();
        String name = request.path("params").path("name").asText(null);
        String headerError = validateHeaders(exchange, method, name);
        if (headerError != null) {
            sendJson(exchange, 400, jsonRpcError(id, -32020, headerError));
            return;
        }

        try {
            ObjectNode result = switch (method) {
                case "server/discover" -> discoverResult();
                case "tools/list" -> listToolsResult();
                case "tools/call" -> callTool(request.path("params"));
                default -> throw new McpRequestException(-32601, "不支持的方法：" + method);
            };
            sendJson(exchange, 200, jsonRpcResult(id, result));
        } catch (McpRequestException exception) {
            sendJson(exchange, 200, jsonRpcError(id, exception.code(), exception.getMessage()));
        } catch (Exception exception) {
            sendJson(exchange, 200, jsonRpcError(id, -32603, "服务器内部错误：" + exception.getMessage()));
        }
    }

    /**
     * 校验现代 MCP HTTP 的协议、方法和工具名镜像头，防止网关路由信息与正文不一致。
     */
    private String validateHeaders(HttpExchange exchange, String method, String name) {
        String versionHeader = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
        String methodHeader = exchange.getRequestHeaders().getFirst("Mcp-Method");
        String nameHeader = exchange.getRequestHeaders().getFirst("Mcp-Name");
        if (!PROTOCOL_VERSION.equals(versionHeader)) {
            return "MCP-Protocol-Version 必须是 " + PROTOCOL_VERSION;
        }
        if (!method.equals(methodHeader)) {
            return "Mcp-Method 与 JSON-RPC method 不一致";
        }
        if ("tools/call".equals(method) && !name.equals(nameHeader)) {
            return "Mcp-Name 与 params.name 不一致";
        }
        return null;
    }

    /**
     * 返回服务端能力和支持版本；现代协议不再创建隐藏的传输层会话。
     */
    private ObjectNode discoverResult() {
        ObjectNode result = baseResult();
        result.putArray("protocolVersions").add(PROTOCOL_VERSION);
        result.putObject("capabilities").putObject("tools");
        result.put("ttlMs", 60_000);
        result.put("cacheScope", "public");
        return result;
    }

    /**
     * 通过 tools/list 暴露工具 Schema 和风险提示，客户端无需编译期认识服务端 Java 类。
     */
    private ObjectNode listToolsResult() {
        ObjectNode result = baseResult();
        ArrayNode tools = result.putArray("tools");
        tools.add(orderStatusTool());
        tools.add(returnCreateTool());
        result.put("ttlMs", 60_000);
        result.put("cacheScope", "public");
        return result;
    }

    /**
     * 根据 params.name 分派工具；业务错误放进 CallToolResult，而不是伪装成协议错误。
     */
    private ObjectNode callTool(JsonNode params) throws McpRequestException {
        String name = params.path("name").asText();
        JsonNode arguments = params.path("arguments");
        return switch (name) {
            case "order_status" -> orderStatus(arguments);
            case "return_request_create" -> createReturn(arguments);
            default -> throw new McpRequestException(-32602, "工具不存在：" + name);
        };
    }

    /**
     * 查询订单快照，不修改任何远端状态。
     */
    private ObjectNode orderStatus(JsonNode arguments) {
        String orderId = arguments.path("orderId").asText("").strip().toUpperCase();
        OrderSnapshot order = ORDERS.get(orderId);
        if (order == null) {
            return toolResult("订单不存在：" + orderId, true);
        }
        return toolResult("订单号=" + order.id() + "，状态=" + order.status()
                + "，签收天数=" + order.deliveredDays() + "，商品=" + order.product(), false);
    }

    /**
     * 创建一条有副作用的退货申请；是否已经获得用户确认由 Agent 主机负责。
     */
    private ObjectNode createReturn(JsonNode arguments) {
        String orderId = arguments.path("orderId").asText("").strip().toUpperCase();
        String reason = arguments.path("reason").asText("").strip();
        OrderSnapshot order = ORDERS.get(orderId);
        if (order == null) {
            return toolResult("订单不存在：" + orderId, true);
        }
        if (!"delivered".equals(order.status())) {
            return toolResult("订单状态不是 delivered，不能创建退货申请", true);
        }
        if (reason.isBlank()) {
            return toolResult("退货原因不能为空", true);
        }
        String requestId = "RET-" + returnSequence.incrementAndGet();
        returnRequests.put(requestId, orderId + "|" + reason);
        return toolResult("退货申请已创建：申请编号=" + requestId
                + "，订单号=" + orderId + "，原因=" + reason, false);
    }

    /**
     * 描述远端只读订单查询工具。
     */
    private ObjectNode orderStatusTool() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", "order_status");
        tool.put("description", "查询远端订单状态、签收天数和商品；不会修改订单");
        tool.set("inputSchema", inputSchema(Map.of(
                "orderId", "订单编号，例如 ORD-1001"
        )));
        tool.putObject("annotations")
                .put("readOnlyHint", true)
                .put("destructiveHint", false)
                .put("idempotentHint", true)
                .put("openWorldHint", false);
        return tool;
    }

    /**
     * 描述远端写工具，并用 readOnlyHint=false 告诉主机它会产生真实副作用。
     */
    private ObjectNode returnCreateTool() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", "return_request_create");
        tool.put("description", "为已签收订单创建退货申请；会写入远端业务状态，必须先加载退货 Skill 并取得用户确认");
        tool.set("inputSchema", inputSchema(Map.of(
                "orderId", "要退货的订单编号",
                "reason", "用户明确说明的退货原因"
        )));
        tool.putObject("annotations")
                .put("readOnlyHint", false)
                .put("destructiveHint", false)
                .put("idempotentHint", false)
                .put("openWorldHint", false);
        return tool;
    }

    /**
     * 为简单字符串参数构造 JSON Schema；Map 的所有字段都是必填项。
     */
    private ObjectNode inputSchema(Map<String, String> fields) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        fields.forEach((name, description) -> {
            properties.putObject(name).put("type", "string").put("description", description);
            required.add(name);
        });
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 构造 MCP CallToolResult 的文本内容块。
     */
    private ObjectNode toolResult(String text, boolean error) {
        ObjectNode result = baseResult();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        result.put("isError", error);
        return result;
    }

    /**
     * 为现代 MCP 响应添加结果判别和服务端身份元数据。
     */
    private ObjectNode baseResult() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("resultType", "result");
        result.putObject("_meta")
                .putObject("io.modelcontextprotocol/serverInfo")
                .put("name", "ragent-st-order-tools")
                .put("version", "1.0.0");
        return result;
    }

    /**
     * 包装成功 JSON-RPC 响应。
     */
    private ObjectNode jsonRpcResult(JsonNode id, JsonNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? objectMapper.nullNode() : id);
        response.set("result", result);
        return response;
    }

    /**
     * 包装协议级 JSON-RPC 错误。
     */
    private ObjectNode jsonRpcError(JsonNode id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? objectMapper.nullNode() : id);
        response.putObject("error").put("code", code).put("message", message);
        return response;
    }

    /**
     * 提供不依赖 MCP 协议的进程存活检查，供 Docker 和人工排错使用。
     */
    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendPlain(exchange, 405, "只支持 GET");
            return;
        }
        sendPlain(exchange, 200, "{\"status\":\"UP\",\"tools\":2}");
    }

    /**
     * 写出 UTF-8 JSON 响应。
     */
    private void sendJson(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * 写出 UTF-8 简单响应，当前用于健康检查和 HTTP 方法错误。
     */
    private void sendPlain(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * 停止 HTTP 监听并回收工作线程；测试和进程关闭都调用同一条路径。
     */
    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    /**
     * 远端教学订单快照。
     */
    private record OrderSnapshot(String id, String status, int deliveredDays, String product) {
    }

    /**
     * 携带 JSON-RPC 错误码的可预期请求异常。
     */
    private static final class McpRequestException extends Exception {
        private final int code;

        /**
         * 同时保存 JSON-RPC 错误码和可以返回给客户端的可读信息。
         */
        private McpRequestException(int code, String message) {
            super(message);
            this.code = code;
        }

        /**
         * 返回 JSON-RPC 错误码，交给统一错误响应组装方法。
         */
        private int code() {
            return code;
        }
    }
}
