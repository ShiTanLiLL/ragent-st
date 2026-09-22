package com.shitan.ai;

import jakarta.validation.constraints.NotBlank;

/**
 * 浏览器或其他程序提交的问答请求；问题不能是 null、空字符串或只含空格。
 *
 * @param question        用户希望知识助手回答的问题
 * @param knowledgeBaseId 可选的知识库编号；会话问答不传时由意图规划器选择作用域
 * @param conversationId  可选的已有会话编号；不传时为本次请求创建新会话
 * @param userId          可选的教学用户标识；填写后启用持久化会话记忆
 * @param modelTier       可选的模型档位：fast、standard、deep；空值使用 standard
 */
public record QuestionRequest(
        @NotBlank(message = "问题不能为空")
        String question,
        String knowledgeBaseId,
        String conversationId,
        String userId,
        String modelTier
) {
}
