package com.shitan.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 持久化 Agent 会话和每次“决定 → 工具 → Observation”步骤。
 */
@Repository
public class AgentRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 保存 Spring JDBC 工具。
     */
    public AgentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 创建新的 running Agent 会话。
     */
    public void insertSession(
            String id,
            String userId,
            String objective,
            String knowledgeBaseId
    ) {
        jdbcTemplate.update("""
                INSERT INTO agent_session(
                    id, user_id, latest_objective, knowledge_base_id, status
                ) VALUES (?, ?, ?, ?, 'running')
                """, id, userId, objective, knowledgeBaseId);
    }

    /**
     * 恢复已有会话处理新目标；保留旧步骤，只重置本轮终态和最终答案。
     */
    public void reopenSession(String id, String userId, String objective, String knowledgeBaseId) {
        int changed = jdbcTemplate.update("""
                UPDATE agent_session
                SET latest_objective = ?, knowledge_base_id = ?, status = 'running',
                    final_answer = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ?
                """, objective, knowledgeBaseId, id, userId);
        if (changed == 0) {
            throw new java.util.NoSuchElementException("Agent 会话不存在或不属于当前用户");
        }
    }

    /**
     * 同时按会话和用户查询，避免恢复别人 Agent 的工具历史。
     */
    public Optional<AgentSession> findSession(String id, String userId) {
        return jdbcTemplate.query("""
                SELECT id, user_id, latest_objective, knowledge_base_id, status,
                       final_answer, created_at, updated_at
                FROM agent_session
                WHERE id = ? AND user_id = ?
                """, this::mapSession, id, userId).stream().findFirst();
    }

    /**
     * 保存一次工具决定或最终回答，iteration 在同一会话中保持递增。
     */
    public void insertStep(
            String sessionId,
            int iteration,
            AgentDecision decision,
            String toolInput,
            String observation
    ) {
        jdbcTemplate.update("""
                INSERT INTO agent_step(
                    session_id, iteration, decision_type, decision_summary,
                    tool_name, tool_input, observation
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, sessionId, iteration, decision.type(), decision.decisionSummary(),
                decision.toolName(), toolInput, observation);
    }

    /**
     * 读取全部历史步骤，供下一轮模型恢复之前的工具结果。
     */
    public List<AgentStep> findSteps(String sessionId) {
        return jdbcTemplate.query("""
                SELECT id, session_id, iteration, decision_type, decision_summary,
                       tool_name, tool_input, observation, created_at
                FROM agent_step
                WHERE session_id = ?
                ORDER BY iteration
                """, this::mapStep, sessionId);
    }

    /**
     * 保存 completed、limit_reached 或 failed 终态和用户可见收尾文字。
     */
    public void finishSession(String id, String status, String finalAnswer) {
        jdbcTemplate.update("""
                UPDATE agent_session
                SET status = ?, final_answer = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, status, finalAnswer, id);
    }

    /**
     * 冻结一次待确认写操作的工具名和 JSON 参数，后续确认请求不能重新提交这些字段。
     */
    public void insertConfirmation(
            String id,
            String sessionId,
            String userId,
            AgentDecision decision,
            String toolInput
    ) {
        jdbcTemplate.update("""
                INSERT INTO agent_confirmation(
                    id, session_id, user_id, tool_name, tool_input,
                    decision_summary, status
                ) VALUES (?, ?, ?, ?, ?, ?, 'pending')
                """, id, sessionId, userId, decision.toolName(), toolInput,
                decision.decisionSummary());
    }

    /**
     * 按确认编号和用户读取冻结动作，避免其他用户查看或批准该操作。
     */
    public Optional<AgentConfirmation> findConfirmation(String id, String userId) {
        return jdbcTemplate.query("""
                SELECT id, session_id, user_id, tool_name, tool_input,
                       decision_summary, status, observation, created_at, updated_at
                FROM agent_confirmation
                WHERE id = ? AND user_id = ?
                """, this::mapConfirmation, id, userId).stream().findFirst();
    }

    /**
     * 返回会话最近确认，供 waiting 和完成响应展示同一张确认卡的最终状态。
     */
    public Optional<AgentConfirmation> findLatestConfirmation(String sessionId) {
        return jdbcTemplate.query("""
                SELECT id, session_id, user_id, tool_name, tool_input,
                       decision_summary, status, observation, created_at, updated_at
                FROM agent_confirmation
                WHERE session_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, this::mapConfirmation, sessionId).stream().findFirst();
    }

    /**
     * 原子地把 pending 改成 executing；并发点击确认时只有一个请求能取得执行权。
     */
    public boolean claimConfirmation(String id, String userId) {
        return jdbcTemplate.update("""
                UPDATE agent_confirmation
                SET status = 'executing', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'pending'
                """, id, userId) == 1;
    }

    /**
     * 记录用户拒绝，返回值用于识别重复确认或已被另一请求处理的情况。
     */
    public boolean denyConfirmation(String id, String userId) {
        return jdbcTemplate.update("""
                UPDATE agent_confirmation
                SET status = 'denied', observation = '用户拒绝执行',
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'pending'
                """, id, userId) == 1;
    }

    /**
     * 保存已经获批动作的远端 Observation，确认状态描述授权结果而不是模型决定。
     */
    public void approveConfirmation(String id, String observation) {
        jdbcTemplate.update("""
                UPDATE agent_confirmation
                SET status = 'approved', observation = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'executing'
                """, observation, id);
    }

    /**
     * 执行恢复路径自身失败时结束 executing，避免确认卡永久显示仍在执行。
     */
    public void failConfirmation(String id, String observation) {
        jdbcTemplate.update("""
                UPDATE agent_confirmation
                SET status = 'failed', observation = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'executing'
                """, observation, id);
    }

    /**
     * 映射 Agent 会话数据库行。
     */
    private AgentSession mapSession(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AgentSession(
                resultSet.getString("id"),
                resultSet.getString("user_id"),
                resultSet.getString("latest_objective"),
                resultSet.getString("knowledge_base_id"),
                resultSet.getString("status"),
                resultSet.getString("final_answer"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    /**
     * 映射一次已经持久化的 Agent 步骤。
     */
    private AgentStep mapStep(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AgentStep(
                resultSet.getLong("id"),
                resultSet.getString("session_id"),
                resultSet.getInt("iteration"),
                resultSet.getString("decision_type"),
                resultSet.getString("decision_summary"),
                resultSet.getString("tool_name"),
                resultSet.getString("tool_input"),
                resultSet.getString("observation"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    /**
     * 映射一张写操作确认卡及其冻结参数和最终处理状态。
     */
    private AgentConfirmation mapConfirmation(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AgentConfirmation(
                resultSet.getString("id"),
                resultSet.getString("session_id"),
                resultSet.getString("user_id"),
                resultSet.getString("tool_name"),
                resultSet.getString("tool_input"),
                resultSet.getString("decision_summary"),
                resultSet.getString("status"),
                resultSet.getString("observation"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }
}
