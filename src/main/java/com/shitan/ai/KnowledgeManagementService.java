package com.shitan.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理当前进程中的知识库、文档、片段和向量索引，并把上传原文件保存到本地目录。
 */
@Service
public class KnowledgeManagementService {

    private final BailianClient bailianClient;
    private final KnowledgeFileLoader fileLoader = new KnowledgeFileLoader();
    private final Path storageRoot;
    private final ConcurrentHashMap<String, KnowledgeBase> knowledgeBases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, KnowledgeDocument> documents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<KnowledgeChunk>> chunksByDocument =
            new ConcurrentHashMap<>();

    /**
     * 保存上传和索引所需对象；存储根目录默认是项目下的 data/uploads，测试可以单独覆盖。
     *
     * @param bailianClient 百炼客户端，用于在上传阶段生成片段向量
     * @param storageRoot   Spring 配置中指定的本地原文件目录
     */
    public KnowledgeManagementService(
            BailianClient bailianClient,
            @Value("${ragent.storage-root:data/uploads}") String storageRoot
    ) {
        this.bailianClient = bailianClient;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    /**
     * 创建一个空知识库，后续上传的文档和片段都通过编号归属于它。
     *
     * @param name 运营人员填写的知识库名称
     * @return 已分配唯一编号的知识库
     */
    public KnowledgeBase createKnowledgeBase(String name) {
        String id = UUID.randomUUID().toString();
        KnowledgeBase knowledgeBase = new KnowledgeBase(id, name.strip());
        knowledgeBases.put(id, knowledgeBase);
        return knowledgeBase;
    }

    /**
     * 同步完成原文件落盘、文本分块和片段向量化，并返回最终文档状态。
     *
     * <p>文档先登记为 pending，真正处理前改为 running；全部片段成功后才一次性发布索引。
     * 任一步失败都会删除残留文件和片段，并保留 failed 文档记录供运营人员查看原因。</p>
     *
     * @param knowledgeBaseId 文件所属知识库编号
     * @param file            Multipart 请求中的上传文件
     * @return success 或 failed 状态的文档记录
     */
    public KnowledgeDocument upload(String knowledgeBaseId, MultipartFile file) {
        requireKnowledgeBase(knowledgeBaseId);

        String documentId = UUID.randomUUID().toString();
        String originalFilename = normalizeFilename(file.getOriginalFilename());
        Path storedFile = storageRoot.resolve(knowledgeBaseId).resolve(documentId + ".txt");

        KnowledgeDocument pending = new KnowledgeDocument(
                documentId,
                knowledgeBaseId,
                originalFilename,
                storedFile.toString(),
                DocumentStatus.PENDING,
                0,
                null
        );
        documents.put(documentId, pending);
        documents.put(documentId, changeStatus(pending, DocumentStatus.RUNNING, 0, null));

        try {
            validateTextFile(file, originalFilename);
            saveOriginalFile(file, storedFile);
            List<KnowledgeChunk> indexedChunks = createIndex(
                    knowledgeBaseId,
                    documentId,
                    storedFile
            );

            // 只有全部 Embedding 都成功后才发布整份文档的片段，避免查询到半份索引。
            chunksByDocument.put(documentId, List.copyOf(indexedChunks));
            KnowledgeDocument success = changeStatus(
                    pending,
                    DocumentStatus.SUCCESS,
                    indexedChunks.size(),
                    null
            );
            documents.put(documentId, success);
            return success;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            cleanupFailedUpload(documentId, storedFile);
            KnowledgeDocument failed = changeStatus(
                    pending,
                    DocumentStatus.FAILED,
                    0,
                    readableMessage(exception)
            );
            documents.put(documentId, failed);
            return failed;
        }
    }

    /**
     * 查询某次上传当前保存的文档状态。
     *
     * @param documentId 文档编号
     * @return 当前状态快照
     * @throws NoSuchElementException 文档不存在时抛出，由 Web 层转换为 404
     */
    public KnowledgeDocument getDocument(String documentId) {
        KnowledgeDocument document = documents.get(documentId);
        if (document == null) {
            throw new NoSuchElementException("文档不存在：" + documentId);
        }
        return document;
    }

    /**
     * 返回指定文档已经成功建立索引的片段，失败或尚未完成时返回空列表。
     *
     * @param documentId 文档编号
     * @return 不允许调用方修改的片段列表
     */
    public List<KnowledgeChunk> chunksOfDocument(String documentId) {
        getDocument(documentId);
        return chunksByDocument.getOrDefault(documentId, List.of());
    }

    /**
     * 汇总一个知识库中所有成功片段，转换成现有向量检索器可直接使用的候选数据。
     *
     * @param knowledgeBaseId 要查询的知识库编号
     * @return 上传阶段已经生成好向量的候选知识
     */
    public List<EmbeddedKnowledge> indexedKnowledge(String knowledgeBaseId) {
        requireKnowledgeBase(knowledgeBaseId);
        List<EmbeddedKnowledge> indexed = new ArrayList<>();

        for (List<KnowledgeChunk> documentChunks : chunksByDocument.values()) {
            for (KnowledgeChunk chunk : documentChunks) {
                if (knowledgeBaseId.equals(chunk.knowledgeBaseId())) {
                    indexed.add(chunk.toEmbeddedKnowledge());
                }
            }
        }
        return List.copyOf(indexed);
    }

    /**
     * 确认知识库存在，并把不存在的编号转换成明确的业务错误。
     *
     * @param knowledgeBaseId 待检查编号
     * @return 找到的知识库
     */
    private KnowledgeBase requireKnowledgeBase(String knowledgeBaseId) {
        KnowledgeBase knowledgeBase = knowledgeBases.get(knowledgeBaseId);
        if (knowledgeBase == null) {
            throw new NoSuchElementException("知识库不存在：" + knowledgeBaseId);
        }
        return knowledgeBase;
    }

    /**
     * 把浏览器可能提交的路径形式文件名收缩为纯文件名；缺失时使用固定名称。
     *
     * @param originalFilename Multipart 中携带的原始文件名
     * @return 不包含目录部分的显示名称
     */
    private String normalizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "knowledge.txt";
        }
        return Path.of(originalFilename).getFileName().toString();
    }

    /**
     * 在落盘和调用百炼之前拒绝空文件及本课暂不支持的非文本格式。
     *
     * @param file             上传文件
     * @param originalFilename 已清理路径部分的文件名
     */
    private void validateTextFile(MultipartFile file, String originalFilename) {
        String lowerName = originalFilename.toLowerCase();
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (!lowerName.endsWith(".txt") && !lowerName.endsWith(".md")) {
            throw new IllegalArgumentException("第 8 课只支持 .txt 和 .md 文本文件");
        }
    }

    /**
     * 创建知识库目录并把上传字节复制到由系统生成的安全文件名中。
     *
     * @param file       Multipart 上传文件
     * @param storedFile 最终本地路径
     * @throws IOException 创建目录或复制字节失败
     */
    private void saveOriginalFile(MultipartFile file, Path storedFile) throws IOException {
        Files.createDirectories(storedFile.getParent());
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, storedFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 用第 3 课格式读取文本块，并逐块调用百炼 Embedding 形成内存向量索引。
     *
     * @param knowledgeBaseId 所属知识库编号
     * @param documentId      来源文档编号
     * @param storedFile      已落盘的 UTF-8 文本文件
     * @return 全部已经带向量的片段；任一片段失败时不会返回半成品
     * @throws IOException          读取文件或调用百炼失败
     * @throws InterruptedException 等待百炼响应时线程被中断
     */
    private List<KnowledgeChunk> createIndex(
            String knowledgeBaseId,
            String documentId,
            Path storedFile
    ) throws IOException, InterruptedException {
        List<KnowledgeEntry> entries = fileLoader.load(storedFile);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("文本中没有可建立索引的知识块");
        }

        List<KnowledgeChunk> indexedChunks = new ArrayList<>();
        for (KnowledgeEntry entry : entries) {
            String embeddingText = entry.title() + "\n" + entry.content();
            double[] vector = bailianClient.createEmbedding(embeddingText);
            indexedChunks.add(new KnowledgeChunk(
                    UUID.randomUUID().toString(),
                    knowledgeBaseId,
                    documentId,
                    entry.title(),
                    entry.content(),
                    entry.keywords(),
                    vector
            ));
        }
        return indexedChunks;
    }

    /**
     * 清除失败上传可能留下的文件和未完成索引；清理失败不会覆盖最初的处理错误。
     *
     * @param documentId 文档编号
     * @param storedFile 可能已经写入的本地文件
     */
    private void cleanupFailedUpload(String documentId, Path storedFile) {
        chunksByDocument.remove(documentId);
        try {
            Files.deleteIfExists(storedFile);
        } catch (IOException ignored) {
            // 文档状态仍优先报告原始失败原因；生产系统会在后续课程增加日志与补偿。
        }
    }

    /**
     * 基于同一份文档数据创建新的状态快照，避免在共享对象上逐字段修改。
     *
     * @param original     初始文档及其归属信息
     * @param status       新状态
     * @param chunkCount   已发布片段数
     * @param errorMessage 失败原因
     * @return 可以整体放回并发 Map 的新文档记录
     */
    private KnowledgeDocument changeStatus(
            KnowledgeDocument original,
            DocumentStatus status,
            int chunkCount,
            String errorMessage
    ) {
        return new KnowledgeDocument(
                original.id(),
                original.knowledgeBaseId(),
                original.originalFilename(),
                original.storedPath(),
                status,
                chunkCount,
                errorMessage
        );
    }

    /**
     * 选择适合返回给运营人员的异常文字，避免响应中出现空错误原因。
     *
     * @param exception 上传、解析或向量化异常
     * @return 简短可读的失败原因
     */
    private String readableMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
