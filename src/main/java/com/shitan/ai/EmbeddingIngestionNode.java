package com.shitan.ai;

import org.springframework.stereotype.Component;

/**
 * 把 parse 节点产生的 KnowledgeEntry 交给百炼 Embedding，生成带向量的 Chunk。
 */
@Component
public class EmbeddingIngestionNode implements IngestionNode {

    private final KnowledgeManagementService knowledgeManagementService;

    /**
     * 保存已有知识管理服务，复用逐片段向量化能力。
     *
     * @param knowledgeManagementService 提供 createChunks
     */
    public EmbeddingIngestionNode(KnowledgeManagementService knowledgeManagementService) {
        this.knowledgeManagementService = knowledgeManagementService;
    }

    /**
     * 声明本实现处理 EMBEDDING 类型节点。
     *
     * @return EMBEDDING
     */
    @Override
    public IngestionNodeType type() {
        return IngestionNodeType.EMBEDDING;
    }

    /**
     * 消费上游 entries，调用模型生成向量，并把 KnowledgeChunk 放入新上下文。
     *
     * @param context 已带解析片段的上下文
     * @return 带向量片段的新上下文
     * @throws Exception 百炼 HTTP 调用失败或线程中断
     */
    @Override
    public IngestionContext execute(IngestionContext context) throws Exception {
        return context.withChunks(knowledgeManagementService.createChunks(
                context.document(),
                context.entries()
        ));
    }
}
