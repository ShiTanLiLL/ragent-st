package com.shitan.ai;

import java.util.List;

/**
 * 节点之间传递的一份任务数据包；每个节点读取已有数据，并把自己的结果放回上下文。
 *
 * @param task     当前后台任务及来源信息
 * @param document 当前处理的文档
 * @param entries  parse 节点产生的未向量化片段
 * @param chunks   embedding 节点产生的带向量片段
 */
public record IngestionContext(
        IngestionTask task,
        KnowledgeDocument document,
        List<KnowledgeEntry> entries,
        List<KnowledgeChunk> chunks
) {

    /**
     * 用任务与 running 文档创建空上下文，后续节点再逐步填入中间结果。
     *
     * @param task     当前任务
     * @param document 已标记 running 的文档
     * @return 尚无解析和向量结果的上下文
     */
    public static IngestionContext start(IngestionTask task, KnowledgeDocument document) {
        return new IngestionContext(task, document, List.of(), List.of());
    }

    /**
     * 保留任务和文档，把 parse 节点的结果放入一份新上下文。
     *
     * @param parsedEntries 结构化分块结果
     * @return 携带解析结果的新上下文
     */
    public IngestionContext withEntries(List<KnowledgeEntry> parsedEntries) {
        return new IngestionContext(task, document, List.copyOf(parsedEntries), chunks);
    }

    /**
     * 保留前面数据，把 embedding 节点的结果放入一份新上下文。
     *
     * @param embeddedChunks 已经带向量的片段
     * @return 携带向量片段的新上下文
     */
    public IngestionContext withChunks(List<KnowledgeChunk> embeddedChunks) {
        return new IngestionContext(task, document, entries, List.copyOf(embeddedChunks));
    }
}
