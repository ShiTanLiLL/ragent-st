package com.shitan.ai;

/**
 * 问答接口返回给调用方的 JSON 数据。
 *
 * @param answer      百炼根据检索证据生成的回答正文
 * @param sourceTitle 本地向量检索选中的证据标题
 */
public record QuestionResponse(
        String answer,
        String sourceTitle
) {
}
