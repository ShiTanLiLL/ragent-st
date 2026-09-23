package com.shitan.ai;

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
    private final List<AgentToolDefinition> toolDefinitions;
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
            @Value("${ragent.agent.max-iterations:4}") int maxIterations
    ) {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("Agent 最大迭代次数必须大于0");
        }
        this.decisionModel = decisionModel;
        this.repository = repository;
        this.knowledgeRepository = knowledgeRepository;
        this.maxIterations = maxIterations;
        Map<String, AgentTool> indexed = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            if (indexed.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("Agent 工具名称重复：" + tool.name());
            }
        }
        this.tools = Map.copyOf(indexed);
        this.toolDefinitions = indexed.values().stream().map(AgentTool::definition).toList();
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

        try {
            int firstIteration = scope.firstIteration();
            for (int offset = 0; offset < scope.maxIterations(); offset++) {
                int iteration = firstIteration + offset;
                AgentDecision decision = decisionModel.decide(
                        scope,
                        List.copyOf(history),
                        toolDefinitions
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
                String observation = executeTool(decision, scope);
                repository.insertStep(sessionId, iteration, decision, toolInput, observation);
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
     * 查询当前用户的一次 Agent 会话和全部步骤。
     */
    public AgentResponse get(String sessionId, String userId) {
        AgentSession session = repository.findSession(sessionId, normalize(userId))
                .orElseThrow(() -> new NoSuchElementException("Agent 会话不存在或不属于当前用户"));
        return new AgentResponse(
                session.id(),
                session.status(),
                session.finalAnswer(),
                repository.findSteps(session.id())
        );
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
                session.id(), session.status(), session.finalAnswer(), repository.findSteps(session.id())
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
