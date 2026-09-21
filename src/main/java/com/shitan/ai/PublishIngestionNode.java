package com.shitan.ai;

import org.springframework.stereotype.Component;

/**
 * 把全部带向量 Chunk 一次发布到数据库，并将文档状态改为 success。
 */
@Component
public class PublishIngestionNode implements IngestionNode {

    private final KnowledgeManagementService knowledgeManagementService;

    /**
     * 保存已有知识管理服务，复用第 9 课的发布事务。
     *
     * @param knowledgeManagementService 提供 publish
     */
    public PublishIngestionNode(KnowledgeManagementService knowledgeManagementService) {
        this.knowledgeManagementService = knowledgeManagementService;
    }

    /**
     * 声明本实现处理 PUBLISH 类型节点。
     *
     * @return PUBLISH
     */
    @Override
    public IngestionNodeType type() {
        return IngestionNodeType.PUBLISH;
    }

    /**
     * 消费上下文中的 chunks，在事务内替换索引并最后标记文档成功。
     *
     * @param context 已带全部向量片段的上下文
     * @return 数据库发布完成后的原上下文
     */
    @Override
    public IngestionContext execute(IngestionContext context) {
        knowledgeManagementService.publish(context.document(), context.chunks());
        return context;
    }
}
