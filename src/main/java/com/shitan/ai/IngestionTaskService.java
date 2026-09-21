package com.shitan.ai;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 把上传登记和耗时摄取分开：请求线程只创建任务，后台线程负责解析、向量化和发布。
 */
@Service
public class IngestionTaskService {

    private final KnowledgeManagementService knowledgeManagementService;
    private final IngestionTaskRepository taskRepository;
    private final Executor ingestionExecutor;

    /**
     * 保存知识处理服务、任务仓库和专用后台执行器。
     *
     * @param knowledgeManagementService 负责文件、文档、Embedding 和 Chunk 发布
     * @param taskRepository              负责保存任务及阶段状态
     * @param ingestionExecutor           专门执行摄取任务的后台线程池
     */
    public IngestionTaskService(
            KnowledgeManagementService knowledgeManagementService,
            IngestionTaskRepository taskRepository,
            @Qualifier("ingestionExecutor") Executor ingestionExecutor
    ) {
        this.knowledgeManagementService = knowledgeManagementService;
        this.taskRepository = taskRepository;
        this.ingestionExecutor = ingestionExecutor;
    }

    /**
     * 保存上传原文、登记 pending 文档与任务，然后立刻把 taskId 交还给 HTTP 层。
     *
     * @param knowledgeBaseId 文件所属知识库编号
     * @param file            浏览器上传的文本文件
     * @return 可用于查询进度的任务和文档编号
     */
    public IngestionSubmission submit(String knowledgeBaseId, MultipartFile file) {
        KnowledgeDocument document = knowledgeManagementService.prepareUpload(
                knowledgeBaseId,
                file
        );
        IngestionTask task = new IngestionTask(
                UUID.randomUUID().toString(),
                document.id(),
                IngestionTaskStatus.PENDING,
                null,
                1,
                null,
                null,
                null
        );
        taskRepository.insert(task);

        // execute 只把工作交给线程池；当前 HTTP 请求不等待 Runnable 执行完。
        ingestionExecutor.execute(() -> execute(task.id()));
        return new IngestionSubmission(task.id(), document.id(), IngestionTaskStatus.PENDING);
    }

    /**
     * 查询一个后台任务的最新状态、当前阶段和失败原因。
     *
     * @param taskId 任务编号
     * @return 数据库中的当前任务快照
     */
    public IngestionTask get(String taskId) {
        return taskRepository.find(taskId)
                .orElseThrow(() -> new NoSuchElementException("摄取任务不存在：" + taskId));
    }

    /**
     * 返回任务历次尝试的阶段记录；先确认任务存在，以便错误编号返回 404。
     *
     * @param taskId 任务编号
     * @return parse、embedding、publish 阶段状态和耗时
     */
    public List<IngestionTaskStep> steps(String taskId) {
        get(taskId);
        return taskRepository.findSteps(taskId);
    }

    /**
     * 只允许 failed 任务重新回到 pending；条件 UPDATE 防止重复点击产生两个后台执行。
     *
     * @param taskId 要重试的任务编号
     * @return 仍使用原 taskId 和 documentId 的新一次受理结果
     */
    public IngestionSubmission retry(String taskId) {
        IngestionTask beforeRetry = get(taskId);
        if (!taskRepository.resetFailedForRetry(taskId)) {
            throw new IllegalStateException(
                    "只有 failed 任务可以重试，当前状态：" + beforeRetry.status().code()
            );
        }

        ingestionExecutor.execute(() -> execute(taskId));
        return new IngestionSubmission(
                taskId,
                beforeRetry.documentId(),
                IngestionTaskStatus.PENDING
        );
    }

    /**
     * 后台线程依次执行 parse、embedding、publish，并在每一阶段前后写入可查询状态。
     *
     * <p>开头的条件 UPDATE 是最后一道并发保护：即使同一个 Runnable 被重复提交，
     * 也只有一个线程能把 pending 改成 running。</p>
     *
     * @param taskId 要执行的任务编号
     */
    private void execute(String taskId) {
        if (!taskRepository.claimPending(taskId)) {
            return;
        }

        IngestionTask task = get(taskId);
        KnowledgeDocument document = null;
        String activeStep = null;
        long stepStartedAt = 0L;

        try {
            document = knowledgeManagementService.markRunning(task.documentId());

            activeStep = "parse";
            stepStartedAt = System.nanoTime();
            taskRepository.startStep(task.id(), task.attemptCount(), activeStep);
            List<KnowledgeEntry> entries = knowledgeManagementService.parseDocument(document);
            taskRepository.completeStep(
                    task.id(),
                    task.attemptCount(),
                    activeStep,
                    elapsedMillis(stepStartedAt)
            );

            activeStep = "embedding";
            stepStartedAt = System.nanoTime();
            taskRepository.startStep(task.id(), task.attemptCount(), activeStep);
            List<KnowledgeChunk> chunks = knowledgeManagementService.createChunks(document, entries);
            taskRepository.completeStep(
                    task.id(),
                    task.attemptCount(),
                    activeStep,
                    elapsedMillis(stepStartedAt)
            );

            activeStep = "publish";
            stepStartedAt = System.nanoTime();
            taskRepository.startStep(task.id(), task.attemptCount(), activeStep);
            knowledgeManagementService.publish(document, chunks);
            taskRepository.completeStep(
                    task.id(),
                    task.attemptCount(),
                    activeStep,
                    elapsedMillis(stepStartedAt)
            );

            taskRepository.completeTask(task.id());
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String message = knowledgeManagementService.readableMessage(exception);
            if (activeStep != null) {
                taskRepository.failStep(
                        task.id(),
                        task.attemptCount(),
                        activeStep,
                        elapsedMillis(stepStartedAt),
                        message
                );
            }
            knowledgeManagementService.markFailed(task.documentId(), message);
            taskRepository.failTask(task.id(), message);
        }
    }

    /**
     * 把单调递增的纳秒计时转换为更适合 API 展示的毫秒耗时。
     *
     * @param startedAt 阶段开始时的 System.nanoTime 值
     * @return 至少为 0 的已用毫秒数
     */
    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
