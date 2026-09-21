package com.shitan.ai;

import org.springframework.stereotype.Component;

/**
 * 读取本地原文，完成 MIME 探测、结构解析和分块，并把 KnowledgeEntry 放入上下文。
 */
@Component
public class ParseIngestionNode implements IngestionNode {

    private final KnowledgeManagementService knowledgeManagementService;

    /**
     * 保存已有知识管理服务，复用第 11 课的结构化解析能力。
     *
     * @param knowledgeManagementService 提供 parseDocument
     */
    public ParseIngestionNode(KnowledgeManagementService knowledgeManagementService) {
        this.knowledgeManagementService = knowledgeManagementService;
    }

    /**
     * 声明本实现处理 PARSE 类型节点。
     *
     * @return PARSE
     */
    @Override
    public IngestionNodeType type() {
        return IngestionNodeType.PARSE;
    }

    /**
     * 解析当前文档，并返回携带未向量化知识片段的新上下文。
     *
     * @param context 已经能从本地路径读取原文的上下文
     * @return 带 KnowledgeEntry 列表的新上下文
     * @throws Exception 文件读取或格式解析失败
     */
    @Override
    public IngestionContext execute(IngestionContext context) throws Exception {
        return context.withEntries(
                knowledgeManagementService.parseDocument(context.document())
        );
    }
}
