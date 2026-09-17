package com.shitan.ai;

import jakarta.validation.constraints.NotBlank;

/**
 * 浏览器或其他程序提交的问答请求；问题不能是 null、空字符串或只含空格。
 *
 * @param question 用户希望知识助手回答的问题
 */
public record QuestionRequest(
        @NotBlank(message = "问题不能为空")
        String question
) {
}
