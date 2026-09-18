package com.shitan.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 用 JDBC 保存和读取知识库、文档、片段及向量；这是当前唯一的 PostgreSQL 数据访问实现。
 */
@Repository
public class KnowledgeRepository {

    private static final String KEYWORD_SEPARATOR = "\u001F";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * 保存 Spring 创建的 JdbcTemplate，后续 SQL 都复用同一个数据库连接池。
     *
     * @param jdbcTemplate       对 JDBC 连接、语句和结果集做最小封装的 Spring 工具
     * @param transactionManager Spring 根据 DataSource 创建的数据库事务管理器
     */
    public KnowledgeRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 新增一个知识库，编号由业务层提前生成。
     *
     * @param knowledgeBase 要持久化的知识库
     */
    public void insertKnowledgeBase(KnowledgeBase knowledgeBase) {
        jdbcTemplate.update(
                "INSERT INTO knowledge_base(id, name) VALUES (?, ?)",
                knowledgeBase.id(),
                knowledgeBase.name()
        );
    }

    /**
     * 检查知识库编号是否真实存在，供上传和查询区分空知识库与错误编号。
     *
     * @param knowledgeBaseId 知识库编号
     * @return 存在时返回 true
     */
    public boolean knowledgeBaseExists(String knowledgeBaseId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_base WHERE id = ?",
                Integer.class,
                knowledgeBaseId
        );
        return count != null && count > 0;
    }

    /**
     * 插入或覆盖同一文档的状态快照，使 pending、running、success/failed 使用同一行。
     *
     * @param document 当前文档状态
     */
    public void saveDocument(KnowledgeDocument document) {
        jdbcTemplate.update("""
                INSERT INTO knowledge_document(
                    id, knowledge_base_id, original_filename, stored_path,
                    status, chunk_count, error_message
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    status = EXCLUDED.status,
                    chunk_count = EXCLUDED.chunk_count,
                    error_message = EXCLUDED.error_message
                """,
                document.id(),
                document.knowledgeBaseId(),
                document.originalFilename(),
                document.storedPath(),
                document.status().code(),
                document.chunkCount(),
                document.errorMessage()
        );
    }

    /**
     * 在一个数据库事务中写入全部片段并把文档改成 success，避免只保存半份索引。
     *
     * @param document 最终 success 文档状态
     * @param chunks   全部已经完成 Embedding 的片段
     */
    public void publishSuccessfulDocument(
            KnowledgeDocument document,
            List<KnowledgeChunk> chunks
    ) {
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            for (KnowledgeChunk chunk : chunks) {
                jdbcTemplate.update("""
                        INSERT INTO knowledge_chunk(
                            id, document_id, title, content, keywords, embedding
                        ) VALUES (?, ?, ?, ?, ?, CAST(? AS vector))
                        """,
                        chunk.id(),
                        chunk.documentId(),
                        chunk.title(),
                        chunk.content(),
                        encodeKeywords(chunk.keywords()),
                        toVectorLiteral(chunk.vector())
                );
            }
            saveDocument(document);
        });
    }

    /**
     * 查询文档状态；数据库中没有对应行时返回空结果。
     *
     * @param documentId 文档编号
     * @return 文档状态快照
     */
    public Optional<KnowledgeDocument> findDocument(String documentId) {
        List<KnowledgeDocument> results = jdbcTemplate.query("""
                SELECT id, knowledge_base_id, original_filename, stored_path,
                       status, chunk_count, error_message
                FROM knowledge_document
                WHERE id = ?
                """, this::mapDocument, documentId);
        return results.stream().findFirst();
    }

    /**
     * 读取一份文档的全部片段和向量，并通过文档表恢复知识库归属。
     *
     * @param documentId 文档编号
     * @return 按数据库读取顺序得到的片段列表
     */
    public List<KnowledgeChunk> findChunksByDocument(String documentId) {
        return jdbcTemplate.query("""
                SELECT c.id, d.knowledge_base_id, c.document_id,
                       c.title, c.content, c.keywords, c.embedding
                FROM knowledge_chunk c
                JOIN knowledge_document d ON d.id = c.document_id
                WHERE c.document_id = ?
                ORDER BY c.id
                """, this::mapChunk, documentId);
    }

    /**
     * 让 pgvector 按余弦距离直接选出指定知识库最接近问题向量的一条证据。
     *
     * @param knowledgeBaseId 目标知识库编号
     * @param questionVector  百炼为当前问题生成的 1024 维向量
     * @return Top-1 证据；知识库没有成功片段时为空
     */
    public Optional<KnowledgeEntry> searchTopOne(
            String knowledgeBaseId,
            double[] questionVector
    ) {
        if (!knowledgeBaseExists(knowledgeBaseId)) {
            throw new NoSuchElementException("知识库不存在：" + knowledgeBaseId);
        }

        List<KnowledgeEntry> results = jdbcTemplate.query("""
                SELECT c.title, c.content, c.keywords
                FROM knowledge_chunk c
                JOIN knowledge_document d ON d.id = c.document_id
                WHERE d.knowledge_base_id = ? AND d.status = 'success'
                ORDER BY c.embedding <=> CAST(? AS vector)
                LIMIT 1
                """,
                (resultSet, rowNumber) -> new KnowledgeEntry(
                        resultSet.getString("title"),
                        resultSet.getString("content"),
                        decodeKeywords(resultSet.getString("keywords"))
                ),
                knowledgeBaseId,
                toVectorLiteral(questionVector)
        );
        return results.stream().findFirst();
    }

    /**
     * 删除指定文档的片段，供失败清理保证数据库中没有半成品。
     *
     * @param documentId 文档编号
     */
    public void deleteChunks(String documentId) {
        jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE document_id = ?", documentId);
    }

    /**
     * 把文档结果集的一行转换成 Java 状态对象。
     *
     * @param resultSet JDBC 当前指向的行
     * @param rowNumber 当前结果行编号，映射本身不依赖它
     * @return 恢复出的文档
     * @throws SQLException 读取列失败
     */
    private KnowledgeDocument mapDocument(ResultSet resultSet, int rowNumber) throws SQLException {
        return new KnowledgeDocument(
                resultSet.getString("id"),
                resultSet.getString("knowledge_base_id"),
                resultSet.getString("original_filename"),
                resultSet.getString("stored_path"),
                DocumentStatus.fromCode(resultSet.getString("status")),
                resultSet.getInt("chunk_count"),
                resultSet.getString("error_message")
        );
    }

    /**
     * 把数据库一行中的文本、归属和 vector 字符串恢复成完整片段。
     *
     * @param resultSet JDBC 当前指向的行
     * @param rowNumber 当前结果行编号，映射本身不依赖它
     * @return 恢复出的知识片段
     * @throws SQLException 读取列失败
     */
    private KnowledgeChunk mapChunk(ResultSet resultSet, int rowNumber) throws SQLException {
        return new KnowledgeChunk(
                resultSet.getString("id"),
                resultSet.getString("knowledge_base_id"),
                resultSet.getString("document_id"),
                resultSet.getString("title"),
                resultSet.getString("content"),
                decodeKeywords(resultSet.getString("keywords")),
                parseVector(resultSet.getString("embedding"))
        );
    }

    /**
     * 把 Java double 数组转成 pgvector 接受的文本形式，例如 [1.0,0.0,0.5]。
     *
     * @param vector Java 向量
     * @return 可以 CAST 为 PostgreSQL vector 的字符串
     */
    private String toVectorLiteral(double[] vector) {
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(vector[index]);
        }
        return literal.append(']').toString();
    }

    /**
     * 把 PostgreSQL 返回的 [数字,数字] 文本重新解析成 Java double 数组。
     *
     * @param literal pgvector 的文本输出
     * @return Java 向量
     */
    private double[] parseVector(String literal) {
        String content = literal.substring(1, literal.length() - 1);
        if (content.isBlank()) {
            return new double[0];
        }

        String[] values = content.split(",");
        double[] vector = new double[values.length];
        for (int index = 0; index < values.length; index++) {
            vector[index] = Double.parseDouble(values[index]);
        }
        return vector;
    }

    /**
     * 用不会出现在普通关键词中的控制字符把关键词列表保存到一个 TEXT 列。
     *
     * @param keywords 关键词列表
     * @return 数据库存储文本
     */
    private String encodeKeywords(List<String> keywords) {
        return String.join(KEYWORD_SEPARATOR, keywords);
    }

    /**
     * 把数据库关键词文本还原为列表；空文本对应空列表。
     *
     * @param encoded 数据库存储文本
     * @return 关键词列表
     */
    private List<String> decodeKeywords(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(Arrays.asList(encoded.split(KEYWORD_SEPARATOR, -1)));
    }
}
