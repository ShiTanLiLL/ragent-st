package com.shitan.ai;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 把现有 RAG 问答能力暴露为浏览器和其他程序都能调用的 HTTP API。
 */
@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    private final BailianRagAssistant assistant;
    private final List<KnowledgeEntry> knowledgeEntries = List.of(
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

    /**
     * 保存 Spring 注入的 RAG 助手，后续每个 HTTP 请求都复用同一个助手对象。
     *
     * @param assistant 已经连接百炼客户端的 RAG 助手
     */
    public QuestionController(BailianRagAssistant assistant) {
        this.assistant = assistant;
    }

    /**
     * 接收问题 JSON，校验通过后执行完整 RAG，并把内部回答转换成对外 JSON。
     *
     * @param request 已完成 JSON 反序列化并准备接受校验的请求对象
     * @return 包含生成正文与证据来源的 HTTP 响应数据
     * @throws IOException          百炼网络通信或响应解析失败
     * @throws InterruptedException 等待百炼响应期间当前线程被中断
     */
    @PostMapping
    public QuestionResponse ask(@Valid @RequestBody QuestionRequest request)
            throws IOException, InterruptedException {
        KnowledgeAnswer answer = assistant.answer(request.question(), knowledgeEntries);
        return new QuestionResponse(answer.content(), answer.sourceTitle());
    }
}
