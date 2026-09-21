package com.shitan.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 直接使用 JDBC 保存会话、消息与摘要，并始终同时使用 conversationId 和 userId 查询。
 */
@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * 保存 JDBC 与事务工具；追加消息和刷新会话时间需要作为一次数据库动作完成。
     *
     * @param jdbcTemplate       Spring 参数化 SQL 工具
     * @param transactionManager 当前 PostgreSQL 事务管理器
     */
    public ConversationRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 创建新会话并保存所有者和首问标题；调用方已经生成全局唯一 UUID。
     *
     * @param conversationId 新会话编号
     * @param userId         会话所有者
     * @param title          从首问截取的显示标题
     */
    public void insertConversation(String conversationId, String userId, String title) {
        jdbcTemplate.update("""
                INSERT INTO conversation_session(id, user_id, title)
                VALUES (?, ?, ?)
                """, conversationId, userId, title);
    }

    /**
     * 确认会话确实属于当前用户；只按会话编号查询会泄露或串用别人的历史。
     *
     * @param conversationId 会话编号
     * @param userId         当前请求声明的用户
     * @return 该用户拥有此会话时为 true
     */
    public boolean isOwnedBy(String conversationId, String userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM conversation_session
                WHERE id = ? AND user_id = ?
                """, Integer.class, conversationId, userId);
        return count != null && count > 0;
    }

    /**
     * 在同一事务中插入消息并刷新会话最后活动时间，返回数据库生成的稳定顺序编号。
     *
     * @param conversationId 所属会话
     * @param userId         会话所有者
     * @param role           消息说话方
     * @param content        消息正文
     * @return BIGSERIAL 生成的消息编号
     */
    public long appendMessage(
            String conversationId,
            String userId,
            ConversationRole role,
            String content
    ) {
        Long messageId = transactionTemplate.execute(status -> {
            Long createdId = jdbcTemplate.queryForObject("""
                    INSERT INTO conversation_message(
                        conversation_id, user_id, role, content
                    ) VALUES (?, ?, ?, ?)
                    RETURNING id
                    """, Long.class, conversationId, userId, role.code(), content);
            jdbcTemplate.update("""
                    UPDATE conversation_session
                    SET last_active_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND user_id = ?
                    """, conversationId, userId);
            return createdId;
        });
        if (messageId == null) {
            throw new IllegalStateException("数据库没有返回消息编号");
        }
        return messageId;
    }

    /**
     * 先从数据库取最新 N 条，再翻转为模型需要的从旧到新顺序。
     *
     * @param conversationId 会话编号
     * @param userId         会话所有者
     * @param limit          最多保留的消息数
     * @return 按 id 升序排列的近期消息
     */
    public List<ConversationMessage> findRecentMessages(
            String conversationId,
            String userId,
            int limit
    ) {
        List<ConversationMessage> newestFirst = jdbcTemplate.query("""
                SELECT id, conversation_id, user_id, role, content, created_at
                FROM conversation_message
                WHERE conversation_id = ? AND user_id = ?
                ORDER BY id DESC
                LIMIT ?
                """, this::mapMessage, conversationId, userId, limit);
        List<ConversationMessage> ordered = new ArrayList<>(newestFirst);
        Collections.reverse(ordered);
        return List.copyOf(ordered);
    }

    /**
     * 返回会话的全部消息，供学习阶段直接观察持久化顺序。
     *
     * @param conversationId 会话编号
     * @param userId         会话所有者
     * @return 按 id 升序排列的消息
     */
    public List<ConversationMessage> findAllMessages(String conversationId, String userId) {
        return jdbcTemplate.query("""
                SELECT id, conversation_id, user_id, role, content, created_at
                FROM conversation_message
                WHERE conversation_id = ? AND user_id = ?
                ORDER BY id
                """, this::mapMessage, conversationId, userId);
    }

    /**
     * 查询一份会话当前最新摘要；主键确保每个用户会话只有一个滚动摘要。
     *
     * @param conversationId 会话编号
     * @param userId         会话所有者
     * @return 摘要不存在时为空
     */
    public Optional<ConversationSummary> findSummary(String conversationId, String userId) {
        List<ConversationSummary> summaries = jdbcTemplate.query("""
                SELECT conversation_id, user_id, last_message_id, content
                FROM conversation_summary
                WHERE conversation_id = ? AND user_id = ?
                """, this::mapSummary, conversationId, userId);
        return summaries.stream().findFirst();
    }

    /**
     * 统计已经完成提交的用户消息轮数，用来判断是否达到摘要阈值。
     *
     * @param conversationId 会话编号
     * @param userId         会话所有者
     * @return role=user 的消息数量
     */
    public long countUserMessages(String conversationId, String userId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM conversation_message
                WHERE conversation_id = ? AND user_id = ? AND role = 'user'
                """, Long.class, conversationId, userId);
        return count == null ? 0L : count;
    }

    /**
     * 读取“上次摘要水位之后、近期原文窗口之前”的消息，这部分正好需要被压缩。
     *
     * @param conversationId 会话编号
     * @param userId         会话所有者
     * @param afterId        已摘要水位；没有旧摘要时为 0
     * @param beforeId       近期窗口最早消息编号，不包含该消息
     * @return 按 id 升序排列的待摘要消息
     */
    public List<ConversationMessage> findMessagesBetween(
            String conversationId,
            String userId,
            long afterId,
            long beforeId
    ) {
        return jdbcTemplate.query("""
                SELECT id, conversation_id, user_id, role, content, created_at
                FROM conversation_message
                WHERE conversation_id = ? AND user_id = ?
                  AND id > ? AND id < ?
                ORDER BY id
                """, this::mapMessage, conversationId, userId, afterId, beforeId);
    }

    /**
     * 新建或替换滚动摘要，同时推进 lastMessageId 水位，防止重复摘要同一批旧消息。
     *
     * @param summary 新摘要正文及覆盖水位
     */
    public void saveSummary(ConversationSummary summary) {
        jdbcTemplate.update("""
                INSERT INTO conversation_summary(
                    conversation_id, user_id, last_message_id, content
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (conversation_id, user_id) DO UPDATE SET
                    last_message_id = EXCLUDED.last_message_id,
                    content = EXCLUDED.content,
                    updated_at = CURRENT_TIMESTAMP
                """,
                summary.conversationId(),
                summary.userId(),
                summary.lastMessageId(),
                summary.content()
        );
    }

    /**
     * 把消息结果集当前行转换为领域 record。
     *
     * @param resultSet JDBC 当前行
     * @param rowNumber 当前行号，本映射不依赖它
     * @return 完整会话消息
     * @throws SQLException 读取数据库列失败
     */
    private ConversationMessage mapMessage(ResultSet resultSet, int rowNumber)
            throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        return new ConversationMessage(
                resultSet.getLong("id"),
                resultSet.getString("conversation_id"),
                resultSet.getString("user_id"),
                ConversationRole.fromCode(resultSet.getString("role")),
                resultSet.getString("content"),
                createdAt.toInstant()
        );
    }

    /**
     * 把摘要查询结果恢复为正文和水位对象。
     *
     * @param resultSet JDBC 当前行
     * @param rowNumber 当前行号，本映射不依赖它
     * @return 会话摘要
     * @throws SQLException 读取数据库列失败
     */
    private ConversationSummary mapSummary(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new ConversationSummary(
                resultSet.getString("conversation_id"),
                resultSet.getString("user_id"),
                resultSet.getLong("last_message_id"),
                resultSet.getString("content")
        );
    }
}
