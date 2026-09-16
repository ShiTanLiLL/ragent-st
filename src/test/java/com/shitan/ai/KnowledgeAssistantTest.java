package com.shitan.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeAssistantTest {

    @Test
    void shouldAnswerReturnPolicyQuestion() {
        // Arrange：准备当前唯一的用户问题，以及需求规定的准确回答。
        KnowledgeAssistant assistant = new KnowledgeAssistant();
        String question = "退货期限是多久？";

        // Act：让知识助手回答问题。
        String answer = assistant.answer(question);

        // Assert：回答内容必须与退货政策完全一致。
        assertEquals("签收后 7 天内可申请无理由退货。", answer);
    }

    @Test
    void shouldAdmitWhenKnowledgeIsMissing() {
        // Arrange：发票问题尚未被收录，属于系统知识边界之外。
        KnowledgeAssistant assistant = new KnowledgeAssistant();
        String question = "如何开具发票？";

        // Act：仍然通过同一个回答入口提问。
        String answer = assistant.answer(question);

        // Assert：不知道时要明确说明，不能编造或返回无关政策。
        assertEquals("暂时没有找到相关知识。", answer);
    }
}
