package com.shitan.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KnowledgeAssistantTest {

    // 所有用例共用同一组知识，便于比较不同问题经过相同检索流程后的结果。
    private final KnowledgeAssistant assistant = new KnowledgeAssistant(List.of(
            new KnowledgeEntry(
                    "退货政策",
                    "签收后 7 天内可申请无理由退货。",
                    List.of("退货", "退款")
            ),
            new KnowledgeEntry(
                    "保修政策",
                    "电子产品自购买之日起享受 1 年保修。",
                    List.of("保修", "质保")
            ),
            new KnowledgeEntry(
                    "物流政策",
                    "订单付款后 48 小时内发货。",
                    List.of("物流", "发货", "配送")
            )
    ));

    @Test
    void shouldReturnAnswerAndSourceForReturnQuestion() {
        // Act：使用第一课已有的退货问题，验证旧能力在数据重构后仍然存在。
        KnowledgeAnswer answer = assistant.answer("退货期限是多久？");

        // Assert：不仅要答对正文，还要说明答案来自哪条知识。
        assertEquals("签收后 7 天内可申请无理由退货。", answer.content());
        assertEquals("退货政策", answer.sourceTitle());
    }

    @Test
    void shouldFindAnotherKnowledgeEntryByItsKeywords() {
        // Act：一个问题同时命中“保修”和“质保”，应选中新增的保修知识。
        KnowledgeAnswer answer = assistant.answer("电子产品的保修和质保是多久？");

        // Assert：正文和来源必须来自同一条保修知识，不能与退货政策串线。
        assertEquals("电子产品自购买之日起享受 1 年保修。", answer.content());
        assertEquals("保修政策", answer.sourceTitle());
    }

    @Test
    void shouldChooseTheKnowledgeWithMoreMatchingKeywords() {
        // Act：退货政策命中 2 个关键词，物流政策只命中 1 个关键词。
        KnowledgeAnswer answer = assistant.answer("我想退货退款，也想知道什么时候发货？");

        // Assert：助手应选择得分更高的退货政策，而不是碰到第一个词就停止。
        assertEquals("签收后 7 天内可申请无理由退货。", answer.content());
        assertEquals("退货政策", answer.sourceTitle());
    }

    @Test
    void shouldAdmitWhenNoKnowledgeMatches() {
        // Act：当前知识列表没有任何发票相关关键词。
        KnowledgeAnswer answer = assistant.answer("如何开具发票？");

        // Assert：未知问题返回统一兜底；null 表示没有可引用的知识来源。
        assertEquals("暂时没有找到相关知识。", answer.content());
        assertNull(answer.sourceTitle());
    }
}
