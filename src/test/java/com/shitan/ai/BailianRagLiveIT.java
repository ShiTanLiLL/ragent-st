package com.shitan.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 真实网络人工验收。类名以 IT 结尾，因此普通 mvn test 不会自动运行它。
 */
class BailianRagLiveIT {

    @Test
    void shouldReadPermanentApiKeyFromEnvironment() {
        // 这个检查不访问网络，也不打印密钥，只确认 Java 进程能够读取永久环境变量。
        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        assertFalse(
                apiKey == null || apiKey.isBlank(),
                "Java 没有读到 DASHSCOPE_API_KEY，请检查 WSL/VS Code 的永久环境配置"
        );
    }

    @Test
    void shouldAnswerThroughRealBailianModels() throws Exception {
        // Arrange：密钥只从 WSL 环境变量读取；源码和测试报告都不会打印它。
        BailianRagAssistant assistant = new BailianRagAssistant(
                BailianClient.fromEnvironment()
        );
        List<KnowledgeEntry> knowledgeEntries = List.of(
                new KnowledgeEntry(
                        "退货政策",
                        "签收后 7 天内可申请无理由退货。",
                        List.of("退货", "退款")
                ),
                new KnowledgeEntry(
                        "保修政策",
                        "电子产品自购买之日起享受 1 年保修。",
                        List.of("保修", "质保")
                )
        );

        // Act：这里会真实发出三次 Embedding 请求和一次 Chat Completions 请求。
        KnowledgeAnswer answer = assistant.answer(
                "东西不想要了，几天能退？",
                knowledgeEntries
        );

        // Assert：生成正文允许模型自由表达，但证据来源必须由向量检索稳定选中。
        assertEquals("退货政策", answer.sourceTitle());
        assertFalse(answer.content().isBlank());

        // 方便人工观察真实模型如何根据同一条证据组织语言。
        System.out.println("百炼回答：" + answer.content());
        System.out.println("证据来源：" + answer.sourceTitle());
    }
}
