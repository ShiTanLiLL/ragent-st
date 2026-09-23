package com.shitan.ai;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排带会话记忆的同步问答：加载上下文、保存两条消息、改写追问、执行 RAG、触发摘要。
 */
@Service
public class ConversationQuestionService {

    private final ConversationMemoryService memoryService;
    private final IntentRoutingService intentRoutingService;
    private final RagTraceService traceService;

    /**
     * 保存会话记忆层和意图路由编排器；不把 SQL 或模型协议塞进 Controller。
     *
     * @param memoryService 会话、消息、摘要服务
     * @param intentRoutingService 负责拆问、作用域选择和低置信度回落
     * @param traceService         负责保存一次 Run 和关键步骤结果
     */
    public ConversationQuestionService(
            ConversationMemoryService memoryService,
            IntentRoutingService intentRoutingService,
            RagTraceService traceService
    ) {
        this.memoryService = memoryService;
        this.intentRoutingService = intentRoutingService;
        this.traceService = traceService;
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
        String runId = traceService.start(conversationId, request.userId(), request.question());
        try {
            ConversationMemory memory = traceService.recordNode(
                    runId,
                    "memory_load",
                    "conversationId=" + conversationId,
                    () -> memoryService.load(conversationId, request.userId()),
                    loaded -> "recentMessages=" + loaded.recentMessages().size()
                            + ", hasSummary=" + (loaded.summary() != null)
            );
            traceService.recordNode(
                    runId,
                    "user_message_save",
                    "role=user",
                    () -> memoryService.append(
                            conversationId,
                            request.userId(),
                            ConversationRole.USER,
                            request.question()
                    ),
                    messageId -> "messageId=" + messageId
            );

            ModelTier modelTier = traceService.recordNode(
                    runId,
                    "model_tier",
                    "requested=" + request.modelTier(),
                    () -> ModelTier.fromText(request.modelTier()),
                    tier -> "selected=" + tier.name().toLowerCase()
            );
            RoutedKnowledgeAnswer routedAnswer = intentRoutingService.answer(
                    request.question(),
                    request.knowledgeBaseId(),
                    memory,
                    modelTier,
                    runId
            );
            long assistantMessageId = traceService.recordNode(
                    runId,
                    "assistant_message_save",
                    "role=assistant, answerChars=" + routedAnswer.answer().length(),
                    () -> memoryService.append(
                            conversationId,
                            request.userId(),
                            ConversationRole.ASSISTANT,
                            routedAnswer.answer()
                    ),
                    messageId -> "messageId=" + messageId
            );
            traceService.recordNode(
                    runId,
                    "memory_compact",
                    "conversationId=" + conversationId,
                    () -> {
                        memoryService.compactIfNeeded(conversationId, request.userId());
                        return true;
                    },
                    ignored -> "checked"
            );
            traceService.complete(runId, assistantMessageId);

            return new QuestionResponse(
                    routedAnswer.answer(),
                    routedAnswer.sourceTitle(),
                    conversationId,
                    routedAnswer.rewrittenQuestion(),
                    routedAnswer.plans(),
                    routedAnswer.evidence(),
                    buildSources(routedAnswer.evidence()),
                    runId,
                    assistantMessageId
            );
        } catch (Exception exception) {
            traceService.fail(runId, exception);
            throw exception;
        }
    }

    /**
     * 把多个 Chunk 证据按 documentId 归并成用户能理解的文档来源，并保留实际片段编号。
     */
    private List<SourceReference> buildSources(List<RetrievedEvidence> evidence) {
        Map<String, List<RetrievedEvidence>> byDocument = new LinkedHashMap<>();
        for (RetrievedEvidence item : evidence) {
            String sourceId = item.documentId() == null || item.documentId().isBlank()
                    ? "chunk:" + item.id()
                    : item.documentId();
            byDocument.computeIfAbsent(sourceId, ignored -> new java.util.ArrayList<>()).add(item);
        }
        return byDocument.entrySet().stream()
                .map(entry -> {
                    List<RetrievedEvidence> chunks = entry.getValue();
                    RetrievedEvidence first = chunks.get(0);
                    String normalized = first.content().replaceAll("\\s+", " ").strip();
                    String preview = normalized.length() <= 160
                            ? normalized
                            : normalized.substring(0, 160) + "…";
                    return new SourceReference(
                            entry.getKey(),
                            first.title(),
                            preview,
                            chunks.stream().map(RetrievedEvidence::id).toList()
                    );
                })
                .toList();
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
