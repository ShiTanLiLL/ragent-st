package com.shitan.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 让百炼只负责选择下一步并输出受约束 JSON；Java 仍负责验证和执行工具。
 */
@Service
public class BailianAgentDecisionModel implements AgentDecisionModel {

    private final BailianClient bailianClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 保存真实百炼客户端，沿用已经配置好的国内模型环境。
     */
    public BailianAgentDecisionModel(BailianClient bailianClient) {
        this.bailianClient = bailianClient;
    }

    /**
     * 把目标、工具 Schema 和历史 Observation 交给模型，再把 JSON 转成 Java 决定。
     */
    @Override
    public AgentDecision decide(
            AgentScope scope,
            List<AgentStep> steps,
            List<AgentToolDefinition> tools
    ) throws Exception {
        String systemInstruction = """
                你是企业任务 Agent。每次只能选择一个动作，不得假装已经调用工具。
                decisionSummary 只写一句可展示的选择理由，不要输出隐藏思维过程。
                调用工具时只返回 JSON：
                {"type":"tool","decisionSummary":"...","toolName":"工具名","arguments":{"参数":"值"}}
                已有 Observation 足以回答时只返回 JSON：
                {"type":"answer","decisionSummary":"...","answer":"最终回答"}
                不要输出 Markdown，不要输出 JSON 之外的文字。
                """;
        String state = "目标=" + scope.objective()
                + "\n工具=" + objectMapper.writeValueAsString(tools)
                + "\n历史步骤=" + historyJson(steps);
        String raw = bailianClient.decideAgentStep(systemInstruction, state);
        return parseDecision(raw);
    }

    /**
     * 只把动作、参数和 Observation 送回模型，不保存或要求模型暴露长篇思维链。
     */
    private String historyJson(List<AgentStep> steps) throws Exception {
        ArrayNode history = objectMapper.createArrayNode();
        for (AgentStep step : steps) {
            history.addObject()
                    .put("iteration", step.iteration())
                    .put("decisionType", step.decisionType())
                    .put("toolName", step.toolName())
                    .put("toolInput", step.toolInput())
                    .put("observation", step.observation());
        }
        return objectMapper.writeValueAsString(history);
    }

    /**
     * 清理模型偶尔添加的代码围栏，并严格校验 tool/answer 两种协议分支。
     */
    private AgentDecision parseDecision(String raw) throws Exception {
        String normalized = raw.strip()
                .replaceFirst("^```(?:json)?\\s*", "")
                .replaceFirst("\\s*```$", "")
                .strip();
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("Agent 决策不是 JSON 对象：" + raw);
        }
        JsonNode root = objectMapper.readTree(normalized.substring(start, end + 1));
        String type = root.path("type").asText();
        String summary = root.path("decisionSummary").asText("");
        if ("answer".equals(type)) {
            String answer = root.path("answer").asText();
            if (answer.isBlank()) {
                throw new IllegalStateException("Agent answer 决策没有正文");
            }
            return AgentDecision.answer(summary, answer);
        }
        if (!"tool".equals(type)) {
            throw new IllegalStateException("Agent 决策 type 只能是 tool 或 answer");
        }
        String toolName = root.path("toolName").asText();
        if (toolName.isBlank()) {
            throw new IllegalStateException("Agent tool 决策没有 toolName");
        }
        Map<String, String> arguments = new LinkedHashMap<>();
        JsonNode argumentNode = root.path("arguments");
        if (argumentNode.isObject()) {
            argumentNode.properties().forEach(entry ->
                    arguments.put(entry.getKey(), entry.getValue().asText()));
        }
        return AgentDecision.tool(summary, toolName, arguments);
    }
}
