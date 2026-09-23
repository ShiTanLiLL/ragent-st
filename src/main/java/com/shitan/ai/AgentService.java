package com.shitan.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 执行基础 ReAct 循环：模型决定一个动作，Java 执行工具并把 Observation 送回下一轮。
 */
@Service
public class AgentService {

    private final AgentDecisionModel decisionModel;
    private final AgentRepository repository;
    private final KnowledgeRepository knowledgeRepository;
    private final Map<String, AgentTool> tools;
    private final int maxIterations;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 保存决策模型、持久化仓库和当前真实存在的工具，并建立稳定名称索引。
     */
    public AgentService(
            AgentDecisionModel decisionModel,
            AgentRepository repository,
            KnowledgeRepository knowledgeRepository,
            List<AgentTool> tools,
            McpToolClient mcpToolClient,
            @Value("${ragent.agent.max-iterations:4}") int maxIterations
    ) {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("Agent 最大迭代次数必须大于0");
        }
        this.decisionModel = decisionModel;
        this.repository = repository;
        this.knowledgeRepository = knowledgeRepository;
        this.maxIterations = maxIterations;
        List<AgentTool> allTools = new ArrayList<>(tools);
        allTools.addAll(mcpToolClient.discoverAgentTools());
        Map<String, AgentTool> indexed = new LinkedHashMap<>();
        for (AgentTool tool : allTools) {
            if (indexed.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("Agent 工具名称重复：" + tool.name());
            }
        }
        this.tools = Map.copyOf(indexed);
    }

    /**
     * 新建或恢复 Agent 会话，在本轮迭代上限内反复执行“决定 → 工具 → Observation”。
     */
    public AgentResponse run(AgentRequest request) throws Exception {
        AgentSession session = openSession(request);
        String sessionId = session.id();
        List<AgentStep> history = new ArrayList<>(repository.findSteps(sessionId));
        AgentScope scope = new AgentScope(
                sessionId,
                session.userId(),
                session.latestObjective(),
                session.knowledgeBaseId(),
                maxIterations,
                history
        );

        return runLoop(scope, history);
    }

    /**
     * 查询当前用户的一次 Agent 会话和全部步骤。
     */
    public AgentResponse get(String sessionId, String userId) {
        AgentSession session = repository.findSession(sessionId, normalize(userId))
                .orElseThrow(() -> new NoSuchElementException("Agent 会话不存在或不属于当前用户"));
        return new AgentResponse(
                session.id(),
                session.status(),
                session.finalAnswer(),
                repository.findSteps(session.id()),
                repository.findLatestConfirmation(session.id()).orElse(null)
        );
    }

    /**
     * 处理一张等待中的写工具确认卡；批准时只执行数据库冻结参数，然后恢复 Agent 循环。
     */
    public AgentResponse confirm(String confirmationId, AgentConfirmationRequest request) throws Exception {
        String userId = request.userId().strip();
        AgentConfirmation confirmation = repository.findConfirmation(confirmationId, userId)
                .orElseThrow(() -> new NoSuchElementException("Agent 确认不存在或不属于当前用户"));
        AgentSession session = repository.findSession(confirmation.sessionId(), userId)
                .orElseThrow(() -> new NoSuchElementException("Agent 会话不存在或不属于当前用户"));

        if (!request.approved()) {
            if (!repository.denyConfirmation(confirmation.id(), userId)) {
                throw new IllegalStateException("该确认已经处理，不能重复拒绝");
            }
            repository.finishSession(session.id(), "completed", "用户已取消本次写操作，远端没有发生修改。");
            return response(session.id(), userId);
        }

        Map<String, String> frozenArguments = objectMapper.readValue(
                confirmation.toolInput(), new TypeReference<>() { });
        AgentTool tool = tools.get(confirmation.toolName());
        if (tool == null) {
            throw new IllegalStateException("等待确认的工具已经不可用：" + confirmation.toolName());
        }
        if (tool.readOnly()) {
            throw new IllegalStateException("只读工具不应该进入写操作确认：" + tool.name());
        }
        if (!repository.claimConfirmation(confirmation.id(), userId)) {
            throw new IllegalStateException("该确认已经处理或正在执行，不能重复批准");
        }

        try {
            List<AgentStep> oldHistory = repository.findSteps(session.id());
            AgentScope executionScope = new AgentScope(
                    session.id(), session.userId(), session.latestObjective(),
                    session.knowledgeBaseId(), maxIterations, oldHistory);
            AgentDecision approvedDecision = AgentDecision.tool(
                    confirmation.decisionSummary(), confirmation.toolName(), frozenArguments);
            String observation = executeTool(approvedDecision, executionScope);
            repository.insertStep(
                    session.id(), executionScope.firstIteration(), approvedDecision,
                    confirmation.toolInput(), observation);
            repository.approveConfirmation(confirmation.id(), observation);
            repository.reopenSession(
                    session.id(), session.userId(), session.latestObjective(), session.knowledgeBaseId());

            List<AgentStep> history = new ArrayList<>(repository.findSteps(session.id()));
            AgentScope resumedScope = new AgentScope(
                    session.id(), session.userId(), session.latestObjective(),
                    session.knowledgeBaseId(), maxIterations, history);
            return runLoop(resumedScope, history);
        } catch (Exception exception) {
            repository.failConfirmation(confirmation.id(), readableMessage(exception));
            repository.finishSession(session.id(), "failed", readableMessage(exception));
            throw exception;
        }
    }

    /**
     * 执行当前一轮动态决策；只读工具立即执行，写工具冻结参数并暂停等待用户确认。
     */
    private AgentResponse runLoop(AgentScope scope, List<AgentStep> initialHistory) throws Exception {
        String sessionId = scope.sessionId();
        List<AgentStep> history = new ArrayList<>(initialHistory);
        try {
            int firstIteration = scope.firstIteration();
            for (int offset = 0; offset < scope.maxIterations(); offset++) {
                int iteration = firstIteration + offset;
                AgentDecision decision = decisionModel.decide(
                        scope,
                        List.copyOf(history),
                        availableToolDefinitions(history)
                );
                if ("answer".equals(decision.type())) {
                    String answer = requireAnswer(decision.answer());
                    repository.insertStep(sessionId, iteration, decision, null, answer);
                    repository.finishSession(sessionId, "completed", answer);
                    return response(sessionId, scope.userId());
                }
                if (!"tool".equals(decision.type())) {
                    throw new IllegalStateException("Agent 决策 type 只能是 tool 或 answer");
                }

                String toolInput = objectMapper.writeValueAsString(decision.arguments());
                AgentTool tool = tools.get(decision.toolName());
                String skillCode = tool == null ? null : tool.requiredSkillCode();
                if (skillCode != null && !isSkillLoaded(history, skillCode)) {
                    String observation = "工具 " + decision.toolName()
                            + " 尚未解锁，请先调用 load_skill 加载 " + skillCode;
                    repository.insertStep(sessionId, iteration, decision, toolInput, observation);
                    history = new ArrayList<>(repository.findSteps(sessionId));
                    continue;
                }
                if (tool != null && !tool.readOnly()) {
                    String confirmationId = UUID.randomUUID().toString();
                    repository.insertConfirmation(
                            confirmationId, sessionId, scope.userId(), decision, toolInput);
                    repository.finishSession(
                            sessionId,
                            "waiting_confirmation",
                            "写操作尚未执行，请确认工具 " + decision.toolName() + " 及冻结参数。"
                    );
                    return response(sessionId, scope.userId());
                }

                String observation = executeTool(decision, scope);
                repository.insertStep(sessionId, iteration, decision, toolInput, observation);
                // 数据库是可恢复历史的唯一来源；重新读取能拿到真实 id、时间和规范化后的完整步骤。
                history = new ArrayList<>(repository.findSteps(sessionId));
            }

            String limited = "任务在 " + maxIterations + " 次决定内没有完成，请缩小目标或补充信息。";
            repository.finishSession(sessionId, "limit_reached", limited);
            return response(sessionId, scope.userId());
        } catch (Exception exception) {
            repository.finishSession(sessionId, "failed", readableMessage(exception));
            throw exception;
        }
    }

    /**
     * 手册未加载时隐藏它保护的写工具；加载结果进入历史后，下一轮才把工具 Schema 交给模型。
     */
    private List<AgentToolDefinition> availableToolDefinitions(List<AgentStep> history) {
        return tools.values().stream()
                .filter(tool -> tool.requiredSkillCode() == null
                        || isSkillLoaded(history, tool.requiredSkillCode()))
                .map(AgentTool::definition)
                .toList();
    }

    /**
     * 只有成功保存的 load_skill 步骤才能解锁写工具，模型声称“我看过了”不算。
     */
    private boolean isSkillLoaded(List<AgentStep> history, String skillCode) {
        for (AgentStep step : history) {
            if (!"load_skill".equals(step.toolName()) || step.toolInput() == null) {
                continue;
            }
            try {
                if (skillCode.equals(objectMapper.readTree(step.toolInput()).path("skillCode").asText())
                        && step.observation() != null
                        && step.observation().startsWith("已加载 Skill")) {
                    return true;
                }
            } catch (Exception ignored) {
                // 旧的损坏步骤不能用于解锁有副作用工具。
            }
        }
        return false;
    }

    /**
     * 未提供 sessionId 时创建；提供时按 userId 恢复旧步骤并进入新一轮 running。
     */
    private AgentSession openSession(AgentRequest request) {
        String userId = request.userId().strip();
        String objective = request.objective().strip();
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            String knowledgeBaseId = normalize(request.knowledgeBaseId());
            validateKnowledgeBase(knowledgeBaseId);
            String created = UUID.randomUUID().toString();
            repository.insertSession(created, userId, objective, knowledgeBaseId);
            return repository.findSession(created, userId)
                    .orElseThrow(() -> new IllegalStateException("Agent 会话创建后无法读取"));
        }

        String existing = request.sessionId().strip();
        AgentSession previous = repository.findSession(existing, userId)
                .orElseThrow(() -> new NoSuchElementException("Agent 会话不存在或不属于当前用户"));
        // 追问没有重复传 knowledgeBaseId 时，继续使用会话已经保存的检索边界。
        String effectiveKnowledgeBaseId = normalize(request.knowledgeBaseId()) == null
                ? previous.knowledgeBaseId()
                : normalize(request.knowledgeBaseId());
        validateKnowledgeBase(effectiveKnowledgeBaseId);
        repository.reopenSession(existing, userId, objective, effectiveKnowledgeBaseId);
        return repository.findSession(existing, userId)
                .orElseThrow(() -> new IllegalStateException("Agent 会话恢复后无法读取"));
    }

    /**
     * Java 根据白名单找到工具并执行；未知工具和工具异常都变成下一轮可见 Observation。
     */
    private String executeTool(AgentDecision decision, AgentScope scope) {
        if (!"tool".equals(decision.type())) {
            return "不支持的决定类型：" + decision.type();
        }
        AgentTool tool = tools.get(decision.toolName());
        if (tool == null) {
            return "工具不存在或未授权：" + decision.toolName();
        }
        try {
            return tool.execute(decision.arguments(), scope);
        } catch (Exception exception) {
            return "工具执行失败：" + readableMessage(exception);
        }
    }

    /**
     * 提前拒绝不存在的知识库，让模型不能凭空扩大检索范围。
     */
    private void validateKnowledgeBase(String knowledgeBaseId) {
        String normalized = normalize(knowledgeBaseId);
        if (normalized != null && !knowledgeRepository.knowledgeBaseExists(normalized)) {
            throw new NoSuchElementException("知识库不存在：" + normalized);
        }
    }

    /**
     * 从数据库重新组装响应，确保返回的是已经持久化的真实状态。
     */
    private AgentResponse response(String sessionId, String userId) {
        AgentSession session = repository.findSession(sessionId, userId)
                .orElseThrow(() -> new IllegalStateException("Agent 会话保存后无法读取"));
        return new AgentResponse(
                session.id(), session.status(), session.finalAnswer(), repository.findSteps(session.id()),
                repository.findLatestConfirmation(session.id()).orElse(null)
        );
    }

    /**
     * 最终答案必须非空，否则不能把会话伪装成 completed。
     */
    private String requireAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException("Agent 最终答案不能为空");
        }
        return answer.strip();
    }

    /**
     * 统一清理可选字符串。
     */
    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 为会话 failed 状态提取最接近业务原因的消息。
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
