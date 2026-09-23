package com.shitan.ai;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 把已经成熟的数据库 RAG 问答包装成 Agent 可调用的知识工具。
 */
@Component
public class KnowledgeSearchAgentTool implements AgentTool {

    private final BailianRagAssistant assistant;

    /**
     * 保存既有 RAG 助手；工具不重新实现 Embedding、检索和生成。
     */
    public KnowledgeSearchAgentTool(BailianRagAssistant assistant) {
        this.assistant = assistant;
    }

    @Override
    public String name() {
        return "knowledge_search";
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                name(),
                "查询服务端限定知识库中的企业政策，参数 question 是要查询的完整问题",
                List.of("question")
        );
    }

    /**
     * 使用 Scope 中的知识库编号执行 RAG；模型不能通过 arguments 换到其他知识库。
     */
    @Override
    public String execute(Map<String, String> arguments, AgentScope scope) throws Exception {
        String question = requireArgument(arguments, "question");
        if (scope.knowledgeBaseId() == null || scope.knowledgeBaseId().isBlank()) {
            throw new IllegalArgumentException("知识工具需要由请求指定 knowledgeBaseId");
        }
        KnowledgeAnswer answer = assistant.answerFromDatabase(
                question,
                scope.knowledgeBaseId(),
                ModelTier.STANDARD
        );
        return "知识回答：" + answer.content() + "；来源：" + answer.sourceTitle();
    }

    /**
     * 拒绝模型遗漏的必填参数，避免空问题继续调用外部模型。
     */
    private String requireArgument(Map<String, String> arguments, String name) {
        String value = arguments.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("工具参数不能为空：" + name);
        }
        return value.strip();
    }
}
