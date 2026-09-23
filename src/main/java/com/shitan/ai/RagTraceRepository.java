package com.shitan.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 用 JDBC 保存和查询问答 Run、节点步骤与用户反馈。
 */
@Repository
public class RagTraceRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 保存 Spring 提供的参数化 SQL 工具。
     */
    public RagTraceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 在业务步骤开始前登记 running Run，使后续失败也有归属编号。
     */
    public void insertRun(String id, String conversationId, String userId, String questionSummary) {
        jdbcTemplate.update("""
                INSERT INTO rag_trace_run(id, conversation_id, user_id, question_summary, status)
                VALUES (?, ?, ?, ?, 'running')
                """, id, conversationId, userId, questionSummary);
    }

    /**
     * 保存一个已经完成或失败的节点；节点按数据库递增 id 保持执行顺序。
     */
    public void insertNode(
            String runId,
            String nodeName,
            String status,
            String inputSummary,
            String outputSummary,
            long durationMillis,
            String errorMessage
    ) {
        jdbcTemplate.update("""
                INSERT INTO rag_trace_node(
                    run_id, node_name, status, input_summary, output_summary,
                    duration_ms, error_message
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, runId, nodeName, status, inputSummary, outputSummary,
                durationMillis, errorMessage);
    }

    /**
     * 把 Run 标记为完成，并关联最终 assistant 消息，供反馈准确指向回答。
     */
    public void completeRun(String runId, long assistantMessageId) {
        jdbcTemplate.update("""
                UPDATE rag_trace_run
                SET status = 'completed', assistant_message_id = ?,
                    completed_at = CURRENT_TIMESTAMP, error_message = NULL
                WHERE id = ?
                """, assistantMessageId, runId);
    }

    /**
     * 把业务异常写到 Run 终态；节点表保留具体失败位置。
     */
    public void failRun(String runId, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE rag_trace_run
                SET status = 'failed', completed_at = CURRENT_TIMESTAMP, error_message = ?
                WHERE id = ?
                """, errorMessage, runId);
    }

    /**
     * 按 runId 和 userId 一起读取，防止知道编号的人查看别人的追踪。
     */
    public Optional<RagTraceRun> findRun(String runId, String userId) {
        return jdbcTemplate.query("""
                SELECT id, conversation_id, user_id, question_summary, status,
                       assistant_message_id, error_message, started_at, completed_at
                FROM rag_trace_run
                WHERE id = ? AND user_id = ?
                """, this::mapRun, runId, userId).stream().findFirst();
    }

    /**
     * 查找同一用户会话的最近一次 Run，便于请求失败后定位追踪编号。
     */
    public Optional<RagTraceRun> findLatestRun(String conversationId, String userId) {
        return jdbcTemplate.query("""
                SELECT id, conversation_id, user_id, question_summary, status,
                       assistant_message_id, error_message, started_at, completed_at
                FROM rag_trace_run
                WHERE conversation_id = ? AND user_id = ?
                ORDER BY started_at DESC, id DESC
                LIMIT 1
                """, this::mapRun, conversationId, userId).stream().findFirst();
    }

    /**
     * 按执行顺序返回一个 Run 的所有节点。
     */
    public List<RagTraceNode> findNodes(String runId) {
        return jdbcTemplate.query("""
                SELECT id, run_id, node_name, status, input_summary, output_summary,
                       duration_ms, error_message, created_at
                FROM rag_trace_node
                WHERE run_id = ?
                ORDER BY id
                """, this::mapNode, runId);
    }

    /**
     * 每个回答保留一份当前反馈；用户再次提交时更新赞踩和原因。
     */
    public void saveFeedback(
            String runId,
            long assistantMessageId,
            String userId,
            int rating,
            String reason
    ) {
        jdbcTemplate.update("""
                INSERT INTO answer_feedback(
                    run_id, assistant_message_id, user_id, rating, reason
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (run_id) DO UPDATE SET
                    rating = EXCLUDED.rating,
                    reason = EXCLUDED.reason,
                    updated_at = CURRENT_TIMESTAMP
                """, runId, assistantMessageId, userId, rating, reason);
    }

    /**
     * 返回一次回答当前保存的反馈，没有反馈时为空。
     */
    public Optional<AnswerFeedback> findFeedback(String runId) {
        return jdbcTemplate.query("""
                SELECT run_id, assistant_message_id, user_id, rating, reason, updated_at
                FROM answer_feedback
                WHERE run_id = ?
                """, this::mapFeedback, runId).stream().findFirst();
    }

    /**
     * 把 Run 查询行转换成 Java 快照。
     */
    private RagTraceRun mapRun(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp completedAt = resultSet.getTimestamp("completed_at");
        long messageId = resultSet.getLong("assistant_message_id");
        boolean messageIdWasNull = resultSet.wasNull();
        return new RagTraceRun(
                resultSet.getString("id"),
                resultSet.getString("conversation_id"),
                resultSet.getString("user_id"),
                resultSet.getString("question_summary"),
                resultSet.getString("status"),
                messageIdWasNull ? null : messageId,
                resultSet.getString("error_message"),
                resultSet.getTimestamp("started_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant()
        );
    }

    /**
     * 把节点查询行转换成按顺序展示的数据。
     */
    private RagTraceNode mapNode(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RagTraceNode(
                resultSet.getLong("id"),
                resultSet.getString("run_id"),
                resultSet.getString("node_name"),
                resultSet.getString("status"),
                resultSet.getString("input_summary"),
                resultSet.getString("output_summary"),
                resultSet.getLong("duration_ms"),
                resultSet.getString("error_message"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    /**
     * 把反馈查询行转换成返回对象。
     */
    private AnswerFeedback mapFeedback(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AnswerFeedback(
                resultSet.getString("run_id"),
                resultSet.getLong("assistant_message_id"),
                resultSet.getString("user_id"),
                resultSet.getInt("rating"),
                resultSet.getString("reason"),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }
}
