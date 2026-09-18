package com.shitan.ai;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理当前进程中的流式问答：后台执行模型调用、发送 SSE，并保存可取消的运行任务。
 */
@Service
public class StreamingQuestionService {

    private static final long SSE_TIMEOUT_MILLIS = 120_000L;

    private final BailianRagAssistant assistant;
    private final ExecutorService streamExecutor;
    private final ConcurrentHashMap<String, ActiveStream> runningStreams = new ConcurrentHashMap<>();

    /**
     * 保存流式问答所需的 RAG 助手和后台线程池。
     *
     * @param assistant      已连接百炼的 RAG 助手
     * @param streamExecutor 专门等待模型流式响应的后台线程池
     */
    public StreamingQuestionService(
            BailianRagAssistant assistant,
            ExecutorService streamExecutor
    ) {
        this.assistant = assistant;
        this.streamExecutor = streamExecutor;
    }

    /**
     * 创建任务和 SSE 连接，先发送 taskId，再让后台线程执行完整流式 RAG。
     *
     * @param question         用户问题
     * @param knowledgeEntries 当前可检索知识
     * @return 已经发送 meta 事件、之后会继续收到 message 与 done 的 SSE 连接
     */
    public SseEmitter start(String question, List<KnowledgeEntry> knowledgeEntries) {
        String taskId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        ActiveStream activeStream = new ActiveStream(taskId, emitter);

        runningStreams.put(taskId, activeStream);
        activeStream.sendMeta();
        streamExecutor.submit(() -> runStream(question, knowledgeEntries, activeStream));

        return emitter;
    }

    /**
     * 为 PostgreSQL 中已经持久化的知识创建流式任务，后台只再向量化问题并查询 pgvector。
     *
     * @param question        用户问题
     * @param knowledgeBaseId 要查询的持久化知识库编号
     * @return 已经发送 meta、随后会收到 message 与 done 的 SSE 连接
     */
    public SseEmitter startFromDatabase(
            String question,
            String knowledgeBaseId
    ) {
        String taskId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        ActiveStream activeStream = new ActiveStream(taskId, emitter);

        runningStreams.put(taskId, activeStream);
        activeStream.sendMeta();
        streamExecutor.submit(() -> runDatabaseStream(question, knowledgeBaseId, activeStream));

        return emitter;
    }

    /**
     * 请求取消指定任务，并立刻向仍连接的调用方发送 cancelled=true 的 done 事件。
     *
     * @param taskId meta 事件提供的任务编号
     * @return true 表示找到并取消了运行任务，false 表示任务已结束或不存在
     */
    public boolean cancel(String taskId) {
        ActiveStream activeStream = runningStreams.remove(taskId);
        if (activeStream == null) {
            return false;
        }

        activeStream.cancel();
        return true;
    }

    /**
     * 在后台线程执行 RAG，将模型片段转成 message 事件，并保证最终只发送一次 done。
     *
     * @param question         用户问题
     * @param knowledgeEntries 当前知识
     * @param activeStream     本次任务的发送器和并发状态
     */
    private void runStream(
            String question,
            List<KnowledgeEntry> knowledgeEntries,
            ActiveStream activeStream
    ) {
        try {
            String sourceTitle = assistant.streamAnswer(
                    question,
                    knowledgeEntries,
                    activeStream::sendMessage,
                    activeStream::isCancelled
            );
            if (!activeStream.isCancelled()) {
                activeStream.complete(sourceTitle);
            }
        } catch (Exception exception) {
            if (!activeStream.isCancelled()) {
                activeStream.fail(exception);
            }
        } finally {
            runningStreams.remove(activeStream.taskId(), activeStream);
        }
    }

    /**
     * 在后台使用 PostgreSQL 向量检索执行 RAG，并沿用原流式任务的成功、失败和取消收尾。
     *
     * @param question        用户问题
     * @param knowledgeBaseId 要查询的持久化知识库编号
     * @param activeStream    本次任务的发送器和并发状态
     */
    private void runDatabaseStream(
            String question,
            String knowledgeBaseId,
            ActiveStream activeStream
    ) {
        try {
            String sourceTitle = assistant.streamAnswerFromDatabase(
                    question,
                    knowledgeBaseId,
                    activeStream::sendMessage,
                    activeStream::isCancelled
            );
            if (!activeStream.isCancelled()) {
                activeStream.complete(sourceTitle);
            }
        } catch (Exception exception) {
            if (!activeStream.isCancelled()) {
                activeStream.fail(exception);
            }
        } finally {
            runningStreams.remove(activeStream.taskId(), activeStream);
        }
    }

    /**
     * 保存一次 SSE 的取消与结束状态；原子布尔值保证取消、成功、异常竞争时只有一方收尾。
     */
    private static final class ActiveStream {

        private final String taskId;
        private final SseEmitter emitter;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);

        /**
         * 创建运行状态，并在客户端断开、超时或传输失败时标记取消。
         *
         * @param taskId 本次生成任务编号
         * @param emitter 向调用方发送事件的 Spring SSE 对象
         */
        private ActiveStream(String taskId, SseEmitter emitter) {
            this.taskId = taskId;
            this.emitter = emitter;
            emitter.onCompletion(() -> cancelled.set(true));
            emitter.onTimeout(this::cancel);
            emitter.onError(error -> markConnectionClosed());
        }

        /**
         * 返回当前任务编号，供任务表在后台工作结束时精确删除同一个对象。
         *
         * @return 当前任务编号
         */
        private String taskId() {
            return taskId;
        }

        /**
         * 发送流的第一条 meta 事件，让调用方取得可用于取消的 taskId。
         */
        private void sendMeta() {
            send("meta", new StreamMeta(taskId));
        }

        /**
         * 将百炼新产生的一段文字包装成 message 事件；取消后不再发送。
         *
         * @param content 本次新增回答片段
         */
        private void sendMessage(String content) {
            if (!cancelled.get()) {
                send("message", new StreamMessage(content));
            }
        }

        /**
         * 返回任务是否已经被取消，供 Embedding 和模型 SSE 读取循环及时停止。
         *
         * @return true 表示不应继续生成或发送数据
         */
        private boolean isCancelled() {
            return cancelled.get();
        }

        /**
         * 正常生成结束时发送包含证据来源的 done 事件并关闭连接。
         *
         * @param sourceTitle 本次回答使用的证据标题
         */
        private void complete(String sourceTitle) {
            finish(new StreamDone(sourceTitle, false, null));
        }

        /**
         * 用户取消时设置取消状态，并发送 cancelled=true 的 done 事件。
         */
        private void cancel() {
            cancelled.set(true);
            finish(new StreamDone(null, true, null));
        }

        /**
         * 模型或网络失败时发送简短错误并关闭连接，避免流永远保持打开。
         *
         * @param exception 导致生成失败的异常
         */
        private void fail(Exception exception) {
            finish(new StreamDone(null, false, "生成回答失败：" + exception.getMessage()));
        }

        /**
         * 用原子比较确保 done 和 complete 只发生一次，然后结束 SSE 响应。
         *
         * @param doneData 最终状态数据
         */
        private void finish(StreamDone doneData) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }

            try {
                emitter.send(SseEmitter.event().name("done").data(doneData));
                emitter.complete();
            } catch (IOException exception) {
                cancelled.set(true);
                emitter.completeWithError(exception);
            }
        }

        /**
         * 发送一条非终态 SSE；写入失败时标记连接关闭并停止后台生成。
         *
         * @param eventName 事件名称
         * @param data      将被 Spring 转成 JSON 的事件数据
         */
        private void send(String eventName, Object data) {
            if (finished.get()) {
                return;
            }

            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException exception) {
                markConnectionClosed();
                emitter.completeWithError(exception);
            }
        }

        /**
         * 客户端断开或 Spring 报告发送错误时只改变本地状态，不再尝试向坏连接写数据。
         */
        private void markConnectionClosed() {
            cancelled.set(true);
            finished.set(true);
        }
    }
}
