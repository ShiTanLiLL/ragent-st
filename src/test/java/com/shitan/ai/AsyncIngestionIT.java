package com.shitan.ai;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 10 课异步摄取测试：用可控执行器逐步观察 pending、failed、retry 和防重复执行。
 */
class AsyncIngestionIT {

    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer(
            "pgvector/pgvector:pg17"
    ).withDatabaseName("ragent");

    private static DataSource dataSource;
    private static JdbcTemplate jdbcTemplate;

    @TempDir
    Path temporaryStorage;

    private ManualExecutor executor;
    private KnowledgeManagementService knowledgeService;
    private IngestionTaskService taskService;

    /**
     * 为整个测试类启动一只临时 pgvector，并执行 V1、V2 两个迁移版本。
     */
    @BeforeAll
    static void startDatabaseAndMigrate() {
        DATABASE.start();

        DriverManagerDataSource containerDataSource = new DriverManagerDataSource();
        containerDataSource.setUrl(DATABASE.getJdbcUrl());
        containerDataSource.setUsername(DATABASE.getUsername());
        containerDataSource.setPassword(DATABASE.getPassword());
        dataSource = containerDataSource;
        jdbcTemplate = new JdbcTemplate(dataSource);

        Flyway.configure()
                .dataSource(dataSource)
                .load()
                .migrate();
    }

    /**
     * 测试类结束后停止临时数据库，避免保留课堂产生的容器和数据。
     */
    @AfterAll
    static void stopDatabase() {
        DATABASE.stop();
    }

    /**
     * 每次测试前清空业务数据，并重新创建一个没有后台线程竞争的手动执行器。
     */
    @BeforeEach
    void prepareServices() {
        jdbcTemplate.execute("TRUNCATE TABLE knowledge_base CASCADE");

        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        KnowledgeRepository knowledgeRepository = new KnowledgeRepository(
                new JdbcTemplate(dataSource),
                transactionManager
        );
        IngestionTaskRepository taskRepository = new IngestionTaskRepository(
                new JdbcTemplate(dataSource),
                transactionManager
        );
        knowledgeService = new KnowledgeManagementService(
                new BailianClient("unused-before-parse-fails", URI.create("http://localhost/")),
                knowledgeRepository,
                temporaryStorage.toString()
        );
        IngestionPipelineRunner pipelineRunner = new IngestionPipelineRunner(
                new IngestionPipelineCatalog(),
                taskRepository,
                List.of(
                        new FetchIngestionNode(
                                new RemoteDocumentFetcher("localhost"),
                                knowledgeService
                        ),
                        new ParseIngestionNode(knowledgeService),
                        new EmbeddingIngestionNode(knowledgeService),
                        new PublishIngestionNode(knowledgeService)
                )
        );
        executor = new ManualExecutor();
        taskService = new IngestionTaskService(
                knowledgeService,
                taskRepository,
                pipelineRunner,
                executor
        );
    }

    /**
     * 一次跑满“快速受理 → 后台失败 → 查询阶段 → 重试 → 拒绝重复重试”的状态链。
     */
    @Test
    void shouldRunFailedTaskInBackgroundAndRetryOnlyOnce() {
        KnowledgeBase knowledgeBase = knowledgeService.createKnowledgeBase("异步测试知识库");
        MockMultipartFile brokenFile = new MockMultipartFile(
                "file",
                "broken.txt",
                "text/plain",
                "这不是标题、关键词、正文三段式知识".getBytes(StandardCharsets.UTF_8)
        );

        // submit 只保存文件、写入 pending 并把 Runnable 放进队列，此时后台工作尚未执行。
        IngestionSubmission submission = taskService.submit(knowledgeBase.id(), brokenFile);
        IngestionTask pending = taskService.get(submission.taskId());
        KnowledgeDocument pendingDocument = knowledgeService.getDocument(submission.documentId());

        assertEquals(IngestionTaskStatus.PENDING, submission.status());
        assertEquals(IngestionTaskStatus.PENDING, pending.status());
        assertEquals(DocumentStatus.PENDING, pendingDocument.status());
        assertEquals(1, executor.queuedTaskCount());

        // 手动启动唯一的 Runnable；parse 阶段识别格式错误，因此不会调用真实百炼。
        executor.runNext();

        IngestionTask failed = taskService.get(submission.taskId());
        KnowledgeDocument failedDocument = knowledgeService.getDocument(submission.documentId());
        List<IngestionTaskStep> firstAttemptSteps = taskService.steps(submission.taskId());

        assertEquals(IngestionTaskStatus.FAILED, failed.status());
        assertEquals("parse", failed.currentStep());
        assertFalse(failed.errorMessage().isBlank());
        assertEquals(DocumentStatus.FAILED, failedDocument.status());
        assertTrue(Files.exists(Path.of(failedDocument.storedPath())));
        assertEquals(1, firstAttemptSteps.size());
        assertEquals(IngestionTaskStatus.FAILED, firstAttemptSteps.get(0).status());

        // 第一次重试把同一任务改回 pending 并只排入一个 Runnable；立刻重复点击会被拒绝。
        IngestionSubmission retry = taskService.retry(submission.taskId());
        assertEquals(IngestionTaskStatus.PENDING, retry.status());
        assertEquals(2, taskService.get(submission.taskId()).attemptCount());
        assertEquals(1, executor.queuedTaskCount());

        IllegalStateException duplicateRetry = assertThrows(
                IllegalStateException.class,
                () -> taskService.retry(submission.taskId())
        );
        assertTrue(duplicateRetry.getMessage().contains("pending"));
        assertEquals(1, executor.queuedTaskCount());

        // 第二次尝试仍读取同一份原文件并失败；阶段表同时保留 attempt=1 和 attempt=2。
        executor.runNext();
        List<IngestionTaskStep> allSteps = taskService.steps(submission.taskId());
        assertEquals(IngestionTaskStatus.FAILED, taskService.get(submission.taskId()).status());
        assertEquals(2, allSteps.size());
        assertEquals(1, allSteps.get(0).attempt());
        assertEquals(2, allSteps.get(1).attempt());

        System.out.println("首次受理结果：" + submission);
        System.out.println("第一次失败任务：" + failed);
        System.out.println("两次尝试的阶段记录：" + allSteps);
    }

    /**
     * 不立即运行 Runnable，而是先放进队列；测试可以精确观察后台执行前后的数据库状态。
     */
    private static final class ManualExecutor implements Executor {

        private final Queue<Runnable> queuedTasks = new ArrayDeque<>();

        /**
         * 接收后台工作但暂不执行，模拟线程池已经接单、工作线程尚未取走任务。
         *
         * @param command 摄取服务提交的后台工作
         */
        @Override
        public void execute(Runnable command) {
            queuedTasks.add(command);
        }

        /**
         * 由测试主动运行队首任务，让 pending 到 running/failed 的变化发生在确定时刻。
         */
        private void runNext() {
            Runnable task = queuedTasks.remove();
            task.run();
        }

        /**
         * 返回尚未执行的任务数量，用来证明重复重试没有增加第二个后台工作。
         *
         * @return 当前队列长度
         */
        private int queuedTaskCount() {
            return queuedTasks.size();
        }
    }
}
