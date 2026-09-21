package com.shitan.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 保存后台摄取任务及阶段进度，并用条件 UPDATE 保证同一任务只有一个线程能开始执行。
 */
@Repository
public class IngestionTaskRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * 保存 JDBC 和事务工具；任务状态与阶段状态需要成组更新时使用同一个事务。
     *
     * @param jdbcTemplate       执行参数化 SQL 的 Spring 工具
     * @param transactionManager 管理当前 PostgreSQL 事务的 Spring 组件
     */
    public IngestionTaskRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 插入首次为 pending 的摄取任务；一个文档只允许对应一个可重试任务。
     *
     * @param task 新登记的任务
     */
    public void insert(IngestionTask task) {
        jdbcTemplate.update("""
                INSERT INTO ingestion_task(
                    id, document_id, status, current_step, attempt_count,
                    error_message, started_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                task.id(),
                task.documentId(),
                task.status().code(),
                task.currentStep(),
                task.attemptCount(),
                task.errorMessage(),
                timestamp(task.startedAt()),
                timestamp(task.completedAt())
        );
    }

    /**
     * 查询任务当前状态；不存在时返回空结果，让 Service 决定如何转换成业务错误。
     *
     * @param taskId 任务编号
     * @return 当前数据库快照
     */
    public Optional<IngestionTask> find(String taskId) {
        List<IngestionTask> tasks = jdbcTemplate.query("""
                SELECT id, document_id, status, current_step, attempt_count,
                       error_message, started_at, completed_at
                FROM ingestion_task
                WHERE id = ?
                """, this::mapTask, taskId);
        return tasks.stream().findFirst();
    }

    /**
     * 用一条带状态条件的 UPDATE 抢占 pending 任务；只有更新成功的线程可以继续执行。
     *
     * @param taskId 任务编号
     * @return true 表示本线程把 pending 改成 running；false 表示任务已被别的线程处理
     */
    public boolean claimPending(String taskId) {
        return jdbcTemplate.update("""
                UPDATE ingestion_task
                SET status = 'running', current_step = NULL, error_message = NULL,
                    started_at = CURRENT_TIMESTAMP, completed_at = NULL
                WHERE id = ? AND status = 'pending'
                """, taskId) == 1;
    }

    /**
     * 把 failed 任务原子地改回 pending 并增加尝试次数；重复点击只有第一次能成功。
     *
     * @param taskId 失败任务编号
     * @return true 表示本次确实创建了新的执行机会
     */
    public boolean resetFailedForRetry(String taskId) {
        return jdbcTemplate.update("""
                UPDATE ingestion_task
                SET status = 'pending', current_step = NULL, error_message = NULL,
                    attempt_count = attempt_count + 1,
                    started_at = NULL, completed_at = NULL
                WHERE id = ? AND status = 'failed'
                """, taskId) == 1;
    }

    /**
     * 开始一个阶段时，同时更新任务的 currentStep 并插入该次尝试的阶段行。
     *
     * @param taskId   任务编号
     * @param attempt  当前尝试次数
     * @param stepName 阶段名称
     */
    public void startStep(String taskId, int attempt, String stepName) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    "UPDATE ingestion_task SET current_step = ? WHERE id = ?",
                    stepName,
                    taskId
            );
            jdbcTemplate.update("""
                    INSERT INTO ingestion_task_step(
                        task_id, attempt, step_name, status,
                        duration_ms, error_message, started_at, completed_at
                    ) VALUES (?, ?, ?, 'running', NULL, NULL, CURRENT_TIMESTAMP, NULL)
                    """, taskId, attempt, stepName);
        });
    }

    /**
     * 把一个阶段标记完成并保存实际耗时。
     *
     * @param taskId    任务编号
     * @param attempt   当前尝试次数
     * @param stepName  阶段名称
     * @param durationMs 从开始到结束的毫秒数
     */
    public void completeStep(String taskId, int attempt, String stepName, long durationMs) {
        jdbcTemplate.update("""
                UPDATE ingestion_task_step
                SET status = 'completed', duration_ms = ?, completed_at = CURRENT_TIMESTAMP
                WHERE task_id = ? AND attempt = ? AND step_name = ?
                """, durationMs, taskId, attempt, stepName);
    }

    /**
     * 把抛出异常的阶段标记失败，并保存耗时和可读错误原因。
     *
     * @param taskId      任务编号
     * @param attempt     当前尝试次数
     * @param stepName    阶段名称
     * @param durationMs  失败前已经花费的毫秒数
     * @param errorMessage 异常中的可读信息
     */
    public void failStep(
            String taskId,
            int attempt,
            String stepName,
            long durationMs,
            String errorMessage
    ) {
        jdbcTemplate.update("""
                UPDATE ingestion_task_step
                SET status = 'failed', duration_ms = ?, error_message = ?,
                    completed_at = CURRENT_TIMESTAMP
                WHERE task_id = ? AND attempt = ? AND step_name = ?
                """, durationMs, errorMessage, taskId, attempt, stepName);
    }

    /**
     * 所有阶段成功后把任务改成 completed，并清空“当前阶段”。
     *
     * @param taskId 任务编号
     */
    public void completeTask(String taskId) {
        jdbcTemplate.update("""
                UPDATE ingestion_task
                SET status = 'completed', current_step = NULL,
                    error_message = NULL, completed_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'running'
                """, taskId);
    }

    /**
     * 后台处理失败时保存任务级错误，供查询接口直接展示。
     *
     * @param taskId       任务编号
     * @param errorMessage 可读错误原因
     */
    public void failTask(String taskId, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE ingestion_task
                SET status = 'failed', error_message = ?, completed_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'running'
                """, errorMessage, taskId);
    }

    /**
     * 按尝试次数和固定阶段顺序返回任务日志，帮助运营人员观察每一步。
     *
     * @param taskId 任务编号
     * @return 已经开始过的全部阶段
     */
    public List<IngestionTaskStep> findSteps(String taskId) {
        return jdbcTemplate.query("""
                SELECT task_id, attempt, step_name, status, duration_ms, error_message
                FROM ingestion_task_step
                WHERE task_id = ?
                ORDER BY attempt,
                    CASE step_name
                        WHEN 'parse' THEN 1
                        WHEN 'embedding' THEN 2
                        WHEN 'publish' THEN 3
                        ELSE 99
                    END
                """, this::mapStep, taskId);
    }

    /**
     * 把任务查询结果的一行恢复成 Java record。
     *
     * @param resultSet JDBC 当前行
     * @param rowNumber 当前行号，本映射不使用
     * @return 摄取任务状态快照
     * @throws SQLException 读取数据库列失败
     */
    private IngestionTask mapTask(ResultSet resultSet, int rowNumber) throws SQLException {
        return new IngestionTask(
                resultSet.getString("id"),
                resultSet.getString("document_id"),
                IngestionTaskStatus.fromCode(resultSet.getString("status")),
                resultSet.getString("current_step"),
                resultSet.getInt("attempt_count"),
                resultSet.getString("error_message"),
                instant(resultSet.getTimestamp("started_at")),
                instant(resultSet.getTimestamp("completed_at"))
        );
    }

    /**
     * 把阶段查询结果的一行恢复成 Java record。
     *
     * @param resultSet JDBC 当前行
     * @param rowNumber 当前行号，本映射不使用
     * @return 某次尝试中的阶段结果
     * @throws SQLException 读取数据库列失败
     */
    private IngestionTaskStep mapStep(ResultSet resultSet, int rowNumber) throws SQLException {
        Long duration = resultSet.getObject("duration_ms", Long.class);
        return new IngestionTaskStep(
                resultSet.getString("task_id"),
                resultSet.getInt("attempt"),
                resultSet.getString("step_name"),
                IngestionTaskStatus.fromCode(resultSet.getString("status")),
                duration,
                resultSet.getString("error_message")
        );
    }

    /**
     * 把可空 Instant 转成 JDBC Timestamp。
     *
     * @param value 可空时间点
     * @return JDBC 可绑定时间；空值保持为 null
     */
    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    /**
     * 把可空 JDBC Timestamp 转回 Instant。
     *
     * @param value 数据库返回的可空时间
     * @return Java 时间点；空值保持为 null
     */
    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
