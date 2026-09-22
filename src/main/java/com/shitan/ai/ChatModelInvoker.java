package com.shitan.ai;

import java.io.IOException;
import java.util.List;

/**
 * 只描述“用指定模型生成一次答案”的最小调用契约，隐藏供应商 HTTP 细节。
 */
@FunctionalInterface
public interface ChatModelInvoker {

    /**
     * 调用一个具体模型；返回空正文也视为失败，由上层尝试下一候选。
     */
    String invoke(String model, String question, List<KnowledgeEntry> evidences)
            throws IOException, InterruptedException;
}
