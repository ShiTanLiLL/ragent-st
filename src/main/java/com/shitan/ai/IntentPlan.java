package com.shitan.ai;

import java.util.List;

/**
 * 意图规划器对一个子问题做出的检索决定。
 *
 * @param question               可以独立检索的子问题
 * @param knowledgeBaseIds       本次检索允许访问的知识库；空列表表示全部知识库
 * @param confidence             规划器对作用域判断的信心，0 表示回落全库
 * @param fallbackToAll          没有足够线索时是否回落到全部知识库
 * @param clarificationRequired 是否存在同分候选，需要用户进一步说明
 * @param clarification         给用户看的澄清问题
 */
public record IntentPlan(
        String question,
        List<String> knowledgeBaseIds,
        double confidence,
        boolean fallbackToAll,
        boolean clarificationRequired,
        String clarification
) {

    /**
     * 让响应中的候选知识库列表不能被调用方随意修改。
     */
    public IntentPlan {
        knowledgeBaseIds = List.copyOf(knowledgeBaseIds);
    }
}
