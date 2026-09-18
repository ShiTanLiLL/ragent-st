package com.shitan.ai;

/**
 * 一次上传产生的文档记录，也是查询处理结果时看到的状态快照。
 *
 * @param id              文档唯一编号
 * @param knowledgeBaseId 文档所属知识库编号
 * @param originalFilename 上传时的原始文件名
 * @param storedPath      原文件在服务器上的保存位置
 * @param status          当前处理状态
 * @param chunkCount      成功建立索引的片段数量
 * @param errorMessage    失败原因，成功时为 null
 */
public record KnowledgeDocument(
        String id,
        String knowledgeBaseId,
        String originalFilename,
        String storedPath,
        DocumentStatus status,
        int chunkCount,
        String errorMessage
) {
}
