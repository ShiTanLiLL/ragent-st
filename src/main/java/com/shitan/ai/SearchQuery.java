package com.shitan.ai;

import java.util.List;

/**
 * 所有召回通道共享的检索输入。
 *
 * @param question        可独立检索的问题
 * @param knowledgeBaseIds 第 14 课已经决定好的知识库作用域；空列表表示全库
 * @param limit           每个通道最多召回多少条
 */
public record SearchQuery(
        String question,
        List<String> knowledgeBaseIds,
        int limit
) {

    /**
     * 固定本轮检索作用域，避免某个通道偷偷修改知识库列表。
     */
    public SearchQuery {
        knowledgeBaseIds = List.copyOf(knowledgeBaseIds);
    }
}
