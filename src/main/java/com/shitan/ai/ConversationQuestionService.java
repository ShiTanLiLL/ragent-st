package com.shitan.ai;

import org.springframework.stereotype.Service;

/**
 * 编排带会话记忆的同步问答：加载上下文、保存两条消息、改写追问、执行 RAG、触发摘要。
 */
@Service
public class ConversationQuestionService {

    private final ConversationMemoryService memoryService;
    private final IntentRoutingService intentRoutingService;

    /**
     * 保存会话记忆层和意图路由编排器；不把 SQL 或模型协议塞进 Controller。
     *
     * @param memoryService 会话、消息、摘要服务
     * @param intentRoutingService 负责拆问、作用域选择和低置信度回落
     */
    public ConversationQuestionService(
            ConversationMemoryService memoryService,
            IntentRoutingService intentRoutingService
    ) {
        this.memoryService = memoryService;
        this.intentRoutingService = intentRoutingService;
    }

    /**
     * 完成一轮有记忆问答，并返回 conversationId 让下一次追问继续使用同一会话。
     *
     * @param request 包含 userId、可选 conversationId 和知识库编号的请求
     * @return 回答、来源、会话编号和模型实际检索问题
     * @throws Exception 数据库或百炼调用失败
     */
    public QuestionResponse ask(QuestionRequest request) throws Exception {
        String conversationId = memoryService.open(
                request.conversationId(),
                request.userId(),
                request.question()
        );
        ConversationMemory memory = memoryService.load(conversationId, request.userId());
        memoryService.append(
                conversationId,
                request.userId(),
                ConversationRole.USER,
                request.question()
        );

        RoutedKnowledgeAnswer routedAnswer = intentRoutingService.answer(
                request.question(),
                request.knowledgeBaseId(),
                memory,
                ModelTier.fromText(request.modelTier())
        );
        memoryService.append(
                conversationId,
                request.userId(),
                ConversationRole.ASSISTANT,
                routedAnswer.answer()
        );
        memoryService.compactIfNeeded(conversationId, request.userId());

        return new QuestionResponse(
                routedAnswer.answer(),
                routedAnswer.sourceTitle(),
                conversationId,
                routedAnswer.rewrittenQuestion(),
                routedAnswer.plans(),
                routedAnswer.evidence()
        );
    }

    /**
     * 返回本课测试需要观察的当前记忆快照。
     *
     * @param conversationId 会话编号
     * @param userId         当前用户
     * @return 摘要水位和近期原文
     */
    public ConversationMemory memory(String conversationId, String userId) {
        return memoryService.load(conversationId, userId);
    }

    /**
     * 返回会话全部消息，帮助调用方确认 user/assistant 交替和顺序。
     *
     * @param conversationId 会话编号
     * @param userId         当前用户
     * @return 完整消息历史
     */
    public java.util.List<ConversationMessage> messages(String conversationId, String userId) {
        return memoryService.messages(conversationId, userId);
    }
}
