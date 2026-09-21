package com.shitan.ai;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
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
    private final IngestionPipelineRunner pipelineRunner;
    private final Executor ingestionExecutor;

    /**
     * 保存知识处理服务、任务仓库和专用后台执行器。
     *
     * @param knowledgeManagementService 负责文件、文档、Embedding 和 Chunk 发布
     * @param taskRepository              负责保存任务及阶段状态
     * @param pipelineRunner              按任务选定流程逐节点执行和记录日志
     * @param ingestionExecutor           专门执行摄取任务的后台线程池
     */
    public IngestionTaskService(
            KnowledgeManagementService knowledgeManagementService,
            IngestionTaskRepository taskRepository,
            IngestionPipelineRunner pipelineRunner,
            @Qualifier("ingestionExecutor") Executor ingestionExecutor
    ) {
        this.knowledgeManagementService = knowledgeManagementService;
        this.taskRepository = taskRepository;
        this.pipelineRunner = pipelineRunner;
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
                IngestionSourceType.UPLOAD,
                document.storedPath(),
                IngestionPipelineCatalog.UPLOAD_PIPELINE,
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
     * 登记一个 URL 来源文档并选择带 fetch 的流程，然后立刻返回任务编号。
     *
     * @param knowledgeBaseId 远程文档所属知识库编号
     * @param url             运营人员提交的完整 HTTP(S) 地址
     * @return 可查询后台进度的任务和文档编号
     */
    public IngestionSubmission submitRemote(String knowledgeBaseId, String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("远程文档地址不能为空");
        }
        URI sourceUri;
        try {
            sourceUri = URI.create(url.strip());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("远程文档地址格式错误", exception);
        }
        KnowledgeDocument document = knowledgeManagementService.prepareRemote(
                knowledgeBaseId,
                sourceUri
        );
        IngestionTask task = new IngestionTask(
                UUID.randomUUID().toString(),
                document.id(),
                IngestionSourceType.URL,
                sourceUri.toString(),
                IngestionPipelineCatalog.URL_PIPELINE,
                IngestionTaskStatus.PENDING,
                null,
                1,
                null,
                null,
                null
        );
        taskRepository.insert(task);
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
     * 后台线程取得任务选定的流程，再把具体节点执行和步骤日志交给流程执行器。
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

        try {
            KnowledgeDocument document = knowledgeManagementService.markRunning(task.documentId());
            pipelineRunner.run(IngestionContext.start(task, document));
            taskRepository.completeTask(task.id());
        } catch (Exception exception) {
            if (exception instanceof InterruptedException
                    || exception.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String message = knowledgeManagementService.readableMessage(exception);
            knowledgeManagementService.markFailed(task.documentId(), message);
            taskRepository.failTask(task.id(), message);
        }
    }
}
