package com.shitan.ai;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建知识库时提交的 JSON 数据。
 *
 * @param name 知识库名称，不能是空白文字
 */
public record KnowledgeBaseCreateRequest(
        @NotBlank(message = "知识库名称不能为空")
        String name
) {
}
