package com.shitan.ai;

import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Function;

/**
 * 统一记录同步问答的 Run、关键节点和反馈，并限制落库摘要长度。
 */
@Service
public class RagTraceService {

    private static final int SUMMARY_LIMIT = 500;
    private static final int FEEDBACK_REASON_LIMIT = 500;

    private final RagTraceRepository repository;

    /**
     * 保存追踪仓库；业务服务只描述步骤，不直接拼追踪 SQL。
     */
    public RagTraceService(RagTraceRepository repository) {
        this.repository = repository;
    }

    /**
     * 创建一次 running Run，并返回后续节点共同使用的编号。
     */
    public String start(String conversationId, String userId, String question) {
        String runId = UUID.randomUUID().toString();
        repository.insertRun(runId, conversationId, userId, shorten(question));
        return runId;
    }

    /**
     * 执行一个真实业务步骤；成功记录输出和耗时，失败记录节点错误后原样抛出。
     *
     * @param runId        所属问答 Run
     * @param nodeName     稳定步骤名
     * @param inputSummary 不保存完整敏感载荷的输入摘要
     * @param operation    真正业务动作
     * @param outputSummary 把业务结果转换成短摘要的函数
     * @return 原业务结果，供后续步骤继续使用
     */
    public <T> T recordNode(
            String runId,
            String nodeName,
            String inputSummary,
            TraceOperation<T> operation,
            Function<T, String> outputSummary
    ) throws Exception {
        long startedAt = System.nanoTime();
        try {
            T result = operation.execute();
            repository.insertNode(
                    runId,
                    nodeName,
                    "completed",
                    shorten(inputSummary),
                    shorten(outputSummary.apply(result)),
                    elapsedMillis(startedAt),
                    null
            );
            return result;
        } catch (Exception exception) {
            repository.insertNode(
                    runId,
                    nodeName,
                    "failed",
                    shorten(inputSummary),
                    null,
                    elapsedMillis(startedAt),
                    shorten(readableMessage(exception))
            );
            throw exception;
        }
    }

    /**
     * 标记整次问答完成，并关联最终回答消息。
     */
    public void complete(String runId, long assistantMessageId) {
        repository.completeRun(runId, assistantMessageId);
    }

    /**
     * 标记整次问答失败；具体失败步骤已经由 recordNode 保存。
     */
    public void fail(String runId, Exception exception) {
        repository.failRun(runId, shorten(readableMessage(exception)));
    }

    /**
     * 查询属于当前用户的 Run、节点和反馈。
     */
    public RagTraceDetail detail(String runId, String userId) {
        RagTraceRun run = requireRun(runId, userId);
        return new RagTraceDetail(
                run,
                repository.findNodes(run.id()),
                repository.findFeedback(run.id()).orElse(null)
        );
    }

    /**
     * 查询一个会话最近的 Run，主要用于请求失败后取得没有返回到响应体的 runId。
     */
    public RagTraceDetail latest(String conversationId, String userId) {
        RagTraceRun run = repository.findLatestRun(conversationId, userId)
                .orElseThrow(() -> new NoSuchElementException("问答追踪不存在"));
        return detail(run.id(), userId);
    }

    /**
     * 保存或修改当前用户对成功回答的反馈，失败 Run 没有回答消息，不能评价。
     */
    public AnswerFeedback saveFeedback(String runId, FeedbackRequest request) {
        RagTraceRun run = requireRun(runId, request.userId());
        if (!"completed".equals(run.status()) || run.assistantMessageId() == null) {
            throw new IllegalStateException("只有已经完成并保存回答的 Run 可以反馈");
        }
        int rating = request.rating();
        if (rating != 1 && rating != -1) {
            throw new IllegalArgumentException("rating 只能是 1（赞）或 -1（踩）");
        }
        String reason = request.reason() == null ? null : request.reason().strip();
        if (reason != null && reason.length() > FEEDBACK_REASON_LIMIT) {
            throw new IllegalArgumentException("反馈原因不能超过 500 个字符");
        }
        repository.saveFeedback(
                run.id(),
                run.assistantMessageId(),
                run.userId(),
                rating,
                reason == null || reason.isBlank() ? null : reason
        );
        return repository.findFeedback(run.id())
                .orElseThrow(() -> new IllegalStateException("反馈保存后无法读取"));
    }

    /**
     * 同时使用 runId 和 userId 校验追踪归属，避免泄露别人的问题与错误。
     */
    private RagTraceRun requireRun(String runId, String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        return repository.findRun(runId, userId.strip())
                .orElseThrow(() -> new NoSuchElementException("问答追踪不存在或不属于当前用户"));
    }

    /**
     * 只保存有限摘要，避免 Trace 复制完整文档、Prompt 或长模型回答。
     */
    private String shorten(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= SUMMARY_LIMIT
                ? normalized
                : normalized.substring(0, SUMMARY_LIMIT) + "…";
    }

    /**
     * 从异常链中选择最靠近业务原因的非空消息。
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

    /**
     * 把纳秒计时转换成节点表使用的毫秒。
     */
    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
