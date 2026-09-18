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
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 9 课数据库集成测试：用临时 pgvector 容器验证跨对象恢复、关系归属和向量 Top-1。
 */
class KnowledgePersistenceIT {

    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer(
            "pgvector/pgvector:pg17"
    ).withDatabaseName("ragent");

    private static DataSource dataSource;
    private static JdbcTemplate jdbcTemplate;

    @TempDir
    Path temporaryStorage;

    /**
     * 第一次运行测试类时显式启动一只 pgvector 容器，并让迁移与所有查询复用它。
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
     * 整个测试类结束后停止并删除临时 PostgreSQL，避免后台残留教学测试数据。
     */
    @AfterAll
    static void stopDatabase() {
        DATABASE.stop();
    }

    /**
     * 每个测试开始前只清空业务数据，保留 Flyway 已创建的表、索引和 vector 扩展。
     */
    @BeforeEach
    void clearBusinessData() {
        jdbcTemplate.execute("TRUNCATE TABLE knowledge_base CASCADE");
    }

    /**
     * 保存一组完整知识数据后重新创建 Repository 和 Service，验证数据仍存在且 pgvector 能选中正确证据。
     */
    @Test
    void shouldRecoverKnowledgeAndSearchVectorsAfterObjectsAreRecreated() {
        KnowledgeRepository firstRepository = newRepository();

        // 第一段模拟第 8 课上传结束时的数据：一个知识库、一份成功文档和两个带向量片段。
        KnowledgeBase knowledgeBase = new KnowledgeBase("kb-company", "公司制度");
        KnowledgeDocument pending = new KnowledgeDocument(
                "doc-rules",
                knowledgeBase.id(),
                "company-rules.txt",
                temporaryStorage.resolve("company-rules.txt").toString(),
                DocumentStatus.PENDING,
                0,
                null
        );
        KnowledgeDocument success = new KnowledgeDocument(
                pending.id(),
                pending.knowledgeBaseId(),
                pending.originalFilename(),
                pending.storedPath(),
                DocumentStatus.SUCCESS,
                2,
                null
        );
        List<KnowledgeChunk> chunks = List.of(
                new KnowledgeChunk(
                        "chunk-leave",
                        knowledgeBase.id(),
                        pending.id(),
                        "年假规则",
                        "员工连续工作满一年后，每年享有 5 天带薪年假。",
                        List.of("年假", "休假"),
                        vector(1.0, 0.0)
                ),
                new KnowledgeChunk(
                        "chunk-visitor",
                        knowledgeBase.id(),
                        pending.id(),
                        "访客规则",
                        "外部访客进入办公室前，需要提前一天预约。",
                        List.of("访客", "预约"),
                        vector(0.0, 1.0)
                )
        );

        firstRepository.insertKnowledgeBase(knowledgeBase);
        firstRepository.saveDocument(pending);
        firstRepository.publishSuccessfulDocument(success, chunks);

        // 丢弃第一批 Java 对象并重新 new，模拟应用重启后内存 Map 已不存在的状态。
        KnowledgeRepository restartedRepository = newRepository();
        KnowledgeManagementService restartedService = new KnowledgeManagementService(
                new BailianClient("unused-in-this-test", URI.create("http://localhost/")),
                restartedRepository,
                temporaryStorage.toString()
        );

        KnowledgeDocument restoredDocument = restartedService.getDocument(pending.id());
        List<KnowledgeChunk> restoredChunks = restartedService.chunksOfDocument(pending.id());

        assertEquals(DocumentStatus.SUCCESS, restoredDocument.status());
        assertEquals(2, restoredDocument.chunkCount());
        assertEquals(2, restoredChunks.size());
        assertEquals(1024, restoredChunks.get(0).vector().length);

        // 问题向量更接近 [1,0,...]，数据库的 <=> 余弦距离应选中“年假规则”。
        KnowledgeEntry evidence = restartedRepository.searchTopOne(
                knowledgeBase.id(),
                vector(0.9, 0.1)
        ).orElseThrow();
        assertEquals("年假规则", evidence.title());

        Integer vectorDimensions = jdbcTemplate.queryForObject(
                "SELECT vector_dims(embedding) FROM knowledge_chunk WHERE id = 'chunk-leave'",
                Integer.class
        );
        assertEquals(1024, vectorDimensions);

        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success",
                Integer.class
        );
        assertTrue(migrationCount != null && migrationCount > 0);

        System.out.println("重建 Service 后恢复的文档：" + restoredDocument);
        System.out.println("重建 Service 后恢复的片段数：" + restoredChunks.size());
        System.out.println("pgvector Top-1 证据：" + evidence.title());
    }

    /**
     * 用同一个容器 DataSource 创建一套新的数据访问对象，模拟 Java 进程内对象被重新创建。
     *
     * @return 指向同一 PostgreSQL 的新 KnowledgeRepository
     */
    private KnowledgeRepository newRepository() {
        return new KnowledgeRepository(
                new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource)
        );
    }

    /**
     * 创建可读的 1024 维测试向量，只设置前两个坐标，其余坐标保持 0。
     *
     * @param first  第一个坐标
     * @param second 第二个坐标
     * @return 与正式 text-embedding-v4 数据库列维度一致的向量
     */
    private double[] vector(double first, double second) {
        double[] vector = new double[1024];
        vector[0] = first;
        vector[1] = second;
        return vector;
    }
}
