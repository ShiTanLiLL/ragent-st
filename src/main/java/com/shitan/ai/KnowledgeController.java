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

    /**
     * 保存 Spring 注入的知识管理服务，Controller 只处理 HTTP 数据转换。
     *
     * @param knowledgeManagementService 负责落盘、分块、向量化和状态保存的服务
     */
    public KnowledgeController(KnowledgeManagementService knowledgeManagementService) {
        this.knowledgeManagementService = knowledgeManagementService;
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
     * 接收 multipart 文件，同步完成原文保存、分块和向量索引后返回最终状态。
     *
     * @param knowledgeBaseId URL 中的目标知识库编号
     * @param file            multipart 中名称为 file 的文本文件
     * @return 本次上传形成的文档及 success/failed 状态
     */
    @PostMapping(
            value = "/knowledge-bases/{knowledgeBaseId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeDocument upload(
            @PathVariable("knowledgeBaseId") String knowledgeBaseId,
            @RequestPart("file") MultipartFile file
    ) {
        return knowledgeManagementService.upload(knowledgeBaseId, file);
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
