package com.shitan.ai;

/**
 * 运营人员创建的知识容器，当前只保存编号和名称。
 *
 * @param id   知识库唯一编号
 * @param name 运营人员填写的名称
 */
public record KnowledgeBase(
        String id,
        String name
) {
}
