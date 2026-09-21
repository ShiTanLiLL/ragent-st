package com.shitan.ai;

/**
 * 问答接口返回给调用方的 JSON 数据。
 *
 * @param answer      百炼根据检索证据生成的回答正文
 * @param sourceTitle 本地向量检索选中的证据标题
 * @param conversationId 本轮问答所属会话；旧的无记忆请求为 null
 * @param rewrittenQuestion 带上历史上下文后交给检索的独立问题
 */
public record QuestionResponse(
        String answer,
        String sourceTitle,
        String conversationId,
        String rewrittenQuestion
) {

    /**
     * 兼容前六课不带会话字段的 Controller 返回方式。
     *
     * @param answer      回答正文
     * @param sourceTitle 证据来源标题
     */
    public QuestionResponse(String answer, String sourceTitle) {
        this(answer, sourceTitle, null, null);
    }
}
