package com.shitan.ai;

import java.util.List;

/**
 * 文档切分后的一段可检索知识，同时保存上传阶段已经生成的向量。
 *
 * @param id              片段唯一编号
 * @param knowledgeBaseId 所属知识库编号
 * @param documentId      来源文档编号
 * @param chunkIndex      在原文中的稳定顺序，从 0 开始
 * @param title           片段标题
 * @param content         给人查看并作为回答证据的展示正文
 * @param keywords        文件中声明的辅助关键词
 * @param embeddingText   实际发送给 Embedding 模型的检索文本
 * @param vector          百炼 Embedding 返回的向量
 */
public record KnowledgeChunk(
        String id,
        String knowledgeBaseId,
        String documentId,
        int chunkIndex,
        String title,
        String content,
        List<String> keywords,
        String embeddingText,
        double[] vector
) {

    /**
     * 兼容前十课直接创建 Chunk 的写法；没有区分文本时使用“标题 + 正文”生成向量文本。
     *
     * @param id              片段编号
     * @param knowledgeBaseId 所属知识库编号
     * @param documentId      来源文档编号
     * @param title           标题
     * @param content         正文
     * @param keywords        关键词
     * @param vector          已生成向量
     */
    public KnowledgeChunk(
            String id,
            String knowledgeBaseId,
            String documentId,
            String title,
            String content,
            List<String> keywords,
            double[] vector
    ) {
        this(
                id,
                knowledgeBaseId,
                documentId,
                0,
                title,
                content,
                keywords,
                title + "\n" + content,
                vector
        );
    }

    /**
     * 把带有文档归属信息的片段转换成现有向量检索器认识的数据。
     *
     * @return 包含知识正文和已生成向量的检索候选项
     */
    public EmbeddedKnowledge toEmbeddedKnowledge() {
        KnowledgeEntry knowledge = new KnowledgeEntry(title, content, keywords, embeddingText);
        return new EmbeddedKnowledge(knowledge, vector);
    }
}
