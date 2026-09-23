package com.shitan.ai;

import java.util.List;

/**
 * 返回给用户的一份稳定文档来源；多个命中片段可以归并到同一个 sourceId。
 *
 * @param sourceId 文档编号，文档不变时引用编号保持稳定
 * @param title    排名最高片段的章节标题
 * @param preview  给用户核对原文的短预览
 * @param chunkIds 本次回答实际使用的该文档片段编号
 */
public record SourceReference(
        String sourceId,
        String title,
        String preview,
        List<String> chunkIds
) {

    /**
     * 复制片段编号，避免响应已经生成后又被外部集合修改。
     */
    public SourceReference {
        chunkIds = List.copyOf(chunkIds);
    }
}
