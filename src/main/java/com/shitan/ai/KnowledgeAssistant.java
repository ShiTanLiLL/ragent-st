package com.shitan.ai;

public class KnowledgeAssistant {

    public String answer(String question) {
        // 只有精确命中当前唯一知识时，才能返回对应政策。
        if ("退货期限是多久？".equals(question)) {
            return "签收后 7 天内可申请无理由退货。";
        }

        // 没有证据支持的问题必须诚实兜底，不能编造答案。
        return "暂时没有找到相关知识。";
    }
}
