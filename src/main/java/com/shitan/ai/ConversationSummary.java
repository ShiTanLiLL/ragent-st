package com.shitan.ai;

/**
 * 已滑出近期窗口的旧消息摘要及其水位。
 *
 * @param conversationId 所属会话编号
 * @param userId         会话所有者
 * @param lastMessageId  摘要已经覆盖到的最后一条消息编号
 * @param content        百炼压缩后的摘要正文
 */
public record ConversationSummary(
        String conversationId,
        String userId,
        long lastMessageId,
        String content
) {
}
