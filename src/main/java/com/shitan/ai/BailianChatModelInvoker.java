package com.shitan.ai;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * 把模型路由层的调用转换成百炼 Chat Completions 调用。
 */
@Component
public class BailianChatModelInvoker implements ChatModelInvoker {

    private final BailianClient bailianClient;

    /**
     * 保存真实百炼客户端；路由层不再直接依赖 JSON 和 HTTP。
     */
    public BailianChatModelInvoker(BailianClient bailianClient) {
        this.bailianClient = bailianClient;
    }

    /**
     * 交给百炼客户端用指定模型生成答案。
     */
    @Override
    public String invoke(String model, String question, List<KnowledgeEntry> evidences)
            throws IOException, InterruptedException {
        return bailianClient.generateAnswer(question, evidences, model);
    }
}
