package com.shitan.ai;

/**
 * 提供给运营接口查看的片段数据；只返回向量维度，不传输整组浮点数。
 *
 * @param id              片段编号
 * @param knowledgeBaseId 所属知识库编号
 * @param documentId      来源文档编号
 * @param title           片段标题
 * @param content         片段正文
 * @param vectorDimension 已生成向量的维度
 */
public record KnowledgeChunkResponse(
        String id,
        String knowledgeBaseId,
        String documentId,
        String title,
        String content,
        int vectorDimension
) {

    /**
     * 从内部片段生成适合 HTTP 返回的简洁数据，避免把体积很大的完整向量暴露出去。
     *
     * @param chunk 内部保存的完整片段
     * @return 不包含向量值、只包含向量维度的响应
     */
    public static KnowledgeChunkResponse from(KnowledgeChunk chunk) {
        return new KnowledgeChunkResponse(
                chunk.id(),
                chunk.knowledgeBaseId(),
                chunk.documentId(),
                chunk.title(),
                chunk.content(),
                chunk.vector().length
        );
    }
}
