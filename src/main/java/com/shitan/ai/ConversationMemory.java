package com.shitan.ai;

import java.util.List;

/**
 * 一次问答实际加载的有界记忆：较早内容用摘要表示，最近内容保留原始角色和顺序。
 *
 * @param conversationId      当前会话编号
 * @param summary             旧历史摘要；尚未生成时为 null
 * @param lastSummaryMessageId 摘要水位；尚未生成时为 null
 * @param recentMessages      按消息编号升序排列的近期原文
 */
public record ConversationMemory(
        String conversationId,
        String summary,
        Long lastSummaryMessageId,
        List<ConversationMessage> recentMessages
) {

    /**
     * 判断本次是否有任何可帮助消解追问的旧上下文。
     *
     * @return 摘要或近期消息至少存在一种时为 true
     */
    public boolean hasContext() {
        return (summary != null && !summary.isBlank()) || !recentMessages.isEmpty();
    }
}
