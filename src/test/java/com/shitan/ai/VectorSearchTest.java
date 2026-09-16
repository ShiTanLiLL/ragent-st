package com.shitan.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VectorSearchTest {

    private final VectorSearch vectorSearch = new VectorSearch();

    @Test
    void shouldExplainDirectionWithCosineSimilarity() {
        // Arrange：二维向量便于直接观察方向；长度不同但方向相同仍应完全相似。
        double[] question = {1.0, 0.0};
        double[] sameDirection = {2.0, 0.0};
        double[] perpendicular = {0.0, 3.0};
        double[] oppositeDirection = {-1.0, 0.0};

        // Act + Assert：同向为 1，垂直为 0，反向为 -1。
        // 第三个参数是浮点误差范围，不要求小数计算结果逐位完全相等。
        assertEquals(1.0, vectorSearch.cosineSimilarity(question, sameDirection), 0.000001);
        assertEquals(0.0, vectorSearch.cosineSimilarity(question, perpendicular), 0.000001);
        assertEquals(-1.0, vectorSearch.cosineSimilarity(question, oppositeDirection), 0.000001);
    }

    @Test
    void shouldRankSemanticKnowledgeAndReturnTopK() {
        // Arrange：这些固定小向量模拟 Embedding 结果，让测试不依赖网络和外部模型。
        KnowledgeEntry returnPolicy = new KnowledgeEntry(
                "退货政策",
                "签收后 7 天内可申请无理由退货。",
                List.of("退货", "退款")
        );
        KnowledgeEntry warrantyPolicy = new KnowledgeEntry(
                "保修政策",
                "电子产品自购买之日起享受 1 年保修。",
                List.of("保修", "质保")
        );
        KnowledgeEntry shippingPolicy = new KnowledgeEntry(
                "物流政策",
                "订单付款后 48 小时内发货。",
                List.of("物流", "发货")
        );

        List<EmbeddedKnowledge> candidates = List.of(
                new EmbeddedKnowledge(returnPolicy, new double[]{0.9, 0.1}),
                new EmbeddedKnowledge(warrantyPolicy, new double[]{0.0, 1.0}),
                new EmbeddedKnowledge(shippingPolicy, new double[]{0.4, 0.6})
        );

        // Act：这个向量代表“东西不想要了，几天能退”，文本没有出现配置关键词。
        List<KnowledgeEntry> results = vectorSearch.search(
                new double[]{1.0, 0.0},
                candidates,
                2
        );

        // Assert：只返回最相似的两条，并严格保持从高分到低分的顺序。
        assertEquals(2, results.size());
        assertEquals("退货政策", results.get(0).title());
        assertEquals("物流政策", results.get(1).title());
    }

    @Test
    void shouldRejectZeroVectorBecauseItHasNoDirection() {
        // Act：assertThrows 中的代码会由 JUnit 执行，并捕获预期异常。
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> vectorSearch.cosineSimilarity(
                        new double[]{1.0, 0.0},
                        new double[]{0.0, 0.0}
                )
        );

        // Assert：错误信息直接说明业务输入为什么无法比较。
        assertEquals("向量不能全部为 0", exception.getMessage());
    }
}
