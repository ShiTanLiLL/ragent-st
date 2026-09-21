package com.shitan.ai;

import java.time.Instant;

/**
 * 一条已经持久化的会话消息。
 *
 * @param id             数据库递增编号，也是稳定消息顺序
 * @param conversationId 所属会话编号
 * @param userId         会话所有者，用于隔离不同用户
 * @param role           user 或 assistant
 * @param content        问题或最终回答正文
 * @param createdAt      数据库写入时间
 */
public record ConversationMessage(
        long id,
        String conversationId,
        String userId,
        ConversationRole role,
        String content,
        Instant createdAt
) {
}
