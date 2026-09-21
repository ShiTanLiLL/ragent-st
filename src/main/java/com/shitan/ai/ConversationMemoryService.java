package com.shitan.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 管理会话身份、消息窗口和滚动摘要；模型问答本身仍由 BailianRagAssistant 负责。
 */
@Service
public class ConversationMemoryService {

    private final ConversationRepository repository;
    private final BailianClient bailianClient;
    private final int recentTurns;
    private final int summaryTriggerTurns;
    private final int summaryMaxChars;

    /**
     * 保存会话仓库、摘要模型和本课的有限记忆配置。
     *
     * @param repository          会话、消息、摘要数据库入口
     * @param bailianClient       负责生成摘要的百炼客户端
     * @param recentTurns         每次原文保留的最近 user+assistant 轮数
     * @param summaryTriggerTurns 达到多少用户轮次后开始摘要
     * @param summaryMaxChars     摘要正文最大字符数
     */
    public ConversationMemoryService(
            ConversationRepository repository,
            BailianClient bailianClient,
            @Value("${ragent.memory.recent-turns:2}") int recentTurns,
            @Value("${ragent.memory.summary-trigger-turns:4}") int summaryTriggerTurns,
            @Value("${ragent.memory.summary-max-chars:300}") int summaryMaxChars
    ) {
        if (recentTurns < 1 || summaryTriggerTurns <= recentTurns || summaryMaxChars < 50) {
            throw new IllegalArgumentException("会话记忆配置必须满足：recentTurns >= 1、summaryTriggerTurns > recentTurns、summaryMaxChars >= 50");
        }
        this.repository = repository;
        this.bailianClient = bailianClient;
        this.recentTurns = recentTurns;
        this.summaryTriggerTurns = summaryTriggerTurns;
        this.summaryMaxChars = summaryMaxChars;
    }

    /**
     * 新请求没有 conversationId 时创建会话；有编号时只允许它的所有者继续使用。
     *
     * @param conversationId 可选的已有会话编号
     * @param userId         当前用户标识
     * @param firstQuestion  新会话标题来源
     * @return 可以安全交给后续查询的会话编号
     */
    public String open(String conversationId, String userId, String firstQuestion) {
        String normalizedUser = requireUser(userId);
        if (conversationId == null || conversationId.isBlank()) {
            String createdId = UUID.randomUUID().toString();
            repository.insertConversation(
                    createdId,
                    normalizedUser,
                    shortenTitle(firstQuestion)
            );
            return createdId;
        }
        if (!repository.isOwnedBy(conversationId, normalizedUser)) {
            throw new NoSuchElementException("会话不存在或不属于当前用户");
        }
        return conversationId;
    }

    /**
     * 读取模型本轮真正允许看到的记忆：摘要加最近若干轮原文。
     *
     * @param conversationId 会话编号
     * @param userId         当前用户
     * @return 有界且按时间升序排列的记忆快照
     */
    public ConversationMemory load(String conversationId, String userId) {
        String normalizedUser = requireUser(userId);
        requireOwnership(conversationId, normalizedUser);
        ConversationSummary summary = repository.findSummary(conversationId, normalizedUser)
                .orElse(null);
        List<ConversationMessage> recent = repository.findRecentMessages(
                conversationId,
                normalizedUser,
                recentTurns * 2
        );
        return new ConversationMemory(
                conversationId,
                summary == null ? null : summary.content(),
                summary == null ? null : summary.lastMessageId(),
                recent
        );
    }

    /**
     * 保存一条 user 或 assistant 消息；调用前已确认会话归属，数据库事务同时刷新活跃时间。
     *
     * @param conversationId 会话编号
     * @param userId         当前用户
     * @param role           消息角色
     * @param content        消息正文
     * @return 数据库生成的消息编号
     */
    public long append(
            String conversationId,
            String userId,
            ConversationRole role,
            String content
    ) {
        String normalizedUser = requireUser(userId);
        requireOwnership(conversationId, normalizedUser);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("会话消息不能为空");
        }
        return repository.appendMessage(
                conversationId,
                normalizedUser,
                role,
                content.strip()
        );
    }

    /**
     * 在助手回答落库后检查水位；把已经滑出近期窗口的旧消息交给模型压缩成一行摘要。
     *
     * @param conversationId 会话编号
     * @param userId         当前用户
     */
    public void compactIfNeeded(String conversationId, String userId) {
        String normalizedUser = requireUser(userId);
        requireOwnership(conversationId, normalizedUser);
        if (repository.countUserMessages(conversationId, normalizedUser) < summaryTriggerTurns) {
            return;
        }

        List<ConversationMessage> recent = repository.findRecentMessages(
                conversationId,
                normalizedUser,
                recentTurns * 2
        );
        if (recent.size() < recentTurns * 2) {
            return;
        }

        ConversationSummary oldSummary = repository.findSummary(conversationId, normalizedUser)
                .orElse(null);
        long afterId = oldSummary == null ? 0L : oldSummary.lastMessageId();
        long beforeId = recent.get(0).id();
        List<ConversationMessage> toSummarize = repository.findMessagesBetween(
                conversationId,
                normalizedUser,
                afterId,
                beforeId
        );
        if (toSummarize.isEmpty()) {
            return;
        }

        // 摘要是“缩短未来上下文”的次要动作；即使摘要模型临时失败，也不能让已经生成的答案变成 HTTP 失败。
        String summary;
        try {
            summary = bailianClient.summarizeConversation(
                    oldSummary == null ? null : oldSummary.content(),
                    toSummarize,
                    summaryMaxChars
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception ignored) {
            return;
        }
        if (summary == null || summary.isBlank()) {
            return;
        }
        repository.saveSummary(new ConversationSummary(
                conversationId,
                normalizedUser,
                toSummarize.get(toSummarize.size() - 1).id(),
                summary.strip()
        ));
    }

    /**
     * 返回一份用户可观察的完整消息列表，便于本课测试检查顺序和隔离。
     *
     * @param conversationId 会话编号
     * @param userId         当前用户
     * @return 按数据库 id 升序排列的消息
     */
    public List<ConversationMessage> messages(String conversationId, String userId) {
        String normalizedUser = requireUser(userId);
        requireOwnership(conversationId, normalizedUser);
        return repository.findAllMessages(conversationId, normalizedUser);
    }

    /**
     * 拒绝空用户标识；本课还没有登录系统，因此 API 暂时把它作为请求字段传入。
     *
     * @param userId 请求中的用户标识
     * @return 去掉首尾空格后的用户标识
     */
    private String requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        return userId.strip();
    }

    /**
     * 在访问消息、摘要前统一检查会话归属，避免某个 SQL 忘记 user 条件。
     *
     * @param conversationId 会话编号
     * @param userId         已规范化的当前用户
     */
    private void requireOwnership(String conversationId, String userId) {
        if (conversationId == null || conversationId.isBlank()
                || !repository.isOwnedBy(conversationId, userId)) {
            throw new NoSuchElementException("会话不存在或不属于当前用户");
        }
    }

    /**
     * 把首问压缩为列表标题；正文仍完整保存到第一条 user 消息。
     *
     * @param question 首次问题
     * @return 最多 40 个字符的标题
     */
    private String shortenTitle(String question) {
        String title = question == null || question.isBlank() ? "新会话" : question.strip();
        return title.length() <= 40 ? title : title.substring(0, 40);
    }
}
