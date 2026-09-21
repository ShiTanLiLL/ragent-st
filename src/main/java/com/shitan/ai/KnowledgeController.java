package com.shitan.ai;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 为运营人员提供创建知识库、上传文本和查看文档处理结果的 HTTP API。
 */
@RestController
@RequestMapping("/api")
public class KnowledgeController {

    private final KnowledgeManagementService knowledgeManagementService;
    private final IngestionTaskService ingestionTaskService;

    /**
     * 保存 Spring 注入的知识管理服务，Controller 只处理 HTTP 数据转换。
     *
     * @param knowledgeManagementService 负责知识库、文档和片段查询的服务
     * @param ingestionTaskService       负责登记、执行和查询后台摄取任务的服务
     */
    public KnowledgeController(
            KnowledgeManagementService knowledgeManagementService,
            IngestionTaskService ingestionTaskService
    ) {
        this.knowledgeManagementService = knowledgeManagementService;
        this.ingestionTaskService = ingestionTaskService;
    }

    /**
     * 接收名称 JSON 并创建一个空知识库。
     *
     * @param request 已校验名称非空的创建请求
     * @return 带唯一编号的新知识库
     */
    @PostMapping("/knowledge-bases")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeBase createKnowledgeBase(
            @Valid @RequestBody KnowledgeBaseCreateRequest request
    ) {
        return knowledgeManagementService.createKnowledgeBase(request.name());
    }

    /**
     * 接收 multipart 文件，保存原文并登记后台任务后立即返回 HTTP 202。
     *
     * @param knowledgeBaseId URL 中的目标知识库编号
     * @param file            multipart 中名称为 file 的文本文件
     * @return 用来继续查询进度的 taskId、documentId 和 pending 状态
     */
    @PostMapping(
            value = "/knowledge-bases/{knowledgeBaseId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestionSubmission upload(
            @PathVariable("knowledgeBaseId") String knowledgeBaseId,
            @RequestPart("file") MultipartFile file
    ) {
        return ingestionTaskService.submit(knowledgeBaseId, file);
    }

    /**
     * 接收远程文档 URL，登记带 fetch 节点的后台任务并立即返回 HTTP 202。
     *
     * @param knowledgeBaseId URL 中的目标知识库编号
     * @param request         已校验 url 非空的 JSON 请求
     * @return 用于轮询进度的任务和文档编号
     */
    @PostMapping("/knowledge-bases/{knowledgeBaseId}/remote-documents")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestionSubmission addRemoteDocument(
            @PathVariable("knowledgeBaseId") String knowledgeBaseId,
            @Valid @RequestBody RemoteDocumentRequest request
    ) {
        return ingestionTaskService.submitRemote(knowledgeBaseId, request.url());
    }

    /**
     * 查询后台摄取任务当前处于等待、运行、完成还是失败。
     *
     * @param taskId 上传接口返回的任务编号
     * @return 当前任务、阶段、尝试次数和错误原因
     */
    @GetMapping("/ingestion-tasks/{taskId}")
    public IngestionTask getTask(@PathVariable("taskId") String taskId) {
        return ingestionTaskService.get(taskId);
    }

    /**
     * 查询任务每次尝试中已经开始过的阶段及耗时。
     *
     * @param taskId 上传接口返回的任务编号
     * @return 按尝试次数和执行顺序排列的阶段记录
     */
    @GetMapping("/ingestion-tasks/{taskId}/steps")
    public List<IngestionTaskStep> getTaskSteps(@PathVariable("taskId") String taskId) {
        return ingestionTaskService.steps(taskId);
    }

    /**
     * 让 failed 任务重新进入 pending；其他状态拒绝重试，避免同一文档并行处理。
     *
     * @param taskId 失败任务编号
     * @return 同一个任务和文档的新一次 pending 受理结果
     */
    @PostMapping("/ingestion-tasks/{taskId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestionSubmission retryTask(@PathVariable("taskId") String taskId) {
        return ingestionTaskService.retry(taskId);
    }

    /**
     * 根据文档编号查看最终处理状态、片段数或失败原因。
     *
     * @param documentId 文档编号
     * @return 当前文档状态快照
     */
    @GetMapping("/documents/{documentId}")
    public KnowledgeDocument getDocument(
            @PathVariable("documentId") String documentId
    ) {
        return knowledgeManagementService.getDocument(documentId);
    }

    /**
     * 查看一份文档成功生成的片段及向量维度，不返回体积很大的完整向量。
     *
     * @param documentId 文档编号
     * @return 仍带知识库和文档归属的片段列表
     */
    @GetMapping("/documents/{documentId}/chunks")
    public List<KnowledgeChunkResponse> getChunks(
            @PathVariable("documentId") String documentId
    ) {
        return knowledgeManagementService.chunksOfDocument(documentId)
                .stream()
                .map(KnowledgeChunkResponse::from)
                .toList();
    }
}
