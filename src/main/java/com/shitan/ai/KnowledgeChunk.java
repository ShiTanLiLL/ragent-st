package com.shitan.ai;

import java.util.List;

/**
 * 文档切分后的一段可检索知识，同时保存上传阶段已经生成的向量。
 *
 * @param id              片段唯一编号
 * @param knowledgeBaseId 所属知识库编号
 * @param documentId      来源文档编号
 * @param title           片段标题
 * @param content         片段正文
 * @param keywords        文件中声明的辅助关键词
 * @param vector          百炼 Embedding 返回的向量
 */
public record KnowledgeChunk(
        String id,
        String knowledgeBaseId,
        String documentId,
        String title,
        String content,
        List<String> keywords,
        double[] vector
) {

    /**
     * 把带有文档归属信息的片段转换成现有向量检索器认识的数据。
     *
     * @return 包含知识正文和已生成向量的检索候选项
     */
    public EmbeddedKnowledge toEmbeddedKnowledge() {
        KnowledgeEntry knowledge = new KnowledgeEntry(title, content, keywords);
        return new EmbeddedKnowledge(knowledge, vector);
    }
}
