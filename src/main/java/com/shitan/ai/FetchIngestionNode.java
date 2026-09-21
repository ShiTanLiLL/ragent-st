package com.shitan.ai;

import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * 远程流程的第一个节点：下载 URL 内容并保存到文档的本地原文路径。
 */
@Component
public class FetchIngestionNode implements IngestionNode {

    private final RemoteDocumentFetcher fetcher;
    private final KnowledgeManagementService knowledgeManagementService;

    /**
     * 保存下载器和文件管理服务，节点本身只负责编排一次 fetch 动作。
     *
     * @param fetcher                    负责安全发送远程 GET
     * @param knowledgeManagementService 负责把返回字节放到文档路径
     */
    public FetchIngestionNode(
            RemoteDocumentFetcher fetcher,
            KnowledgeManagementService knowledgeManagementService
    ) {
        this.fetcher = fetcher;
        this.knowledgeManagementService = knowledgeManagementService;
    }

    /**
     * 声明本实现处理 FETCH 类型节点。
     *
     * @return FETCH
     */
    @Override
    public IngestionNodeType type() {
        return IngestionNodeType.FETCH;
    }

    /**
     * 从任务 sourceLocation 读取 URL，下载并落盘；上下文中的对象暂时不变。
     *
     * @param context 带 URL 来源和目标文档路径的上下文
     * @return 原上下文，后续 parse 节点会读取刚保存的文件
     * @throws Exception URI 或 HTTP 请求失败
     */
    @Override
    public IngestionContext execute(IngestionContext context) throws Exception {
        if (context.task().sourceType() != IngestionSourceType.URL) {
            throw new IllegalArgumentException("fetch 节点只能处理 URL 来源");
        }
        byte[] content = fetcher.fetch(URI.create(context.task().sourceLocation()));
        knowledgeManagementService.saveFetchedContent(context.document(), content);
        return context;
    }
}
