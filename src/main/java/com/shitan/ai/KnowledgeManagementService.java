package com.shitan.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 管理知识上传流程：原文件仍保存到本地，业务数据和向量通过 Repository 持久化到 PostgreSQL。
 */
@Service
public class KnowledgeManagementService {

    private final BailianClient bailianClient;
    private final KnowledgeRepository knowledgeRepository;
    private final DocumentParsingService documentParsingService = new DocumentParsingService();
    private final StructuredDocumentChunker documentChunker = new StructuredDocumentChunker();
    private final Path storageRoot;

    /**
     * 保存上传、数据库持久化和本地文件存储所需对象；测试可以单独覆盖存储根目录。
     *
     * @param bailianClient 百炼客户端，用于在上传阶段生成片段向量
     * @param knowledgeRepository 负责 PostgreSQL 文档、片段和向量读写的具体仓库
     * @param storageRoot   Spring 配置中指定的本地原文件目录
     */
    public KnowledgeManagementService(
            BailianClient bailianClient,
            KnowledgeRepository knowledgeRepository,
            @Value("${ragent.storage-root:data/uploads}") String storageRoot
    ) {
        this.bailianClient = bailianClient;
        this.knowledgeRepository = knowledgeRepository;
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
        knowledgeRepository.insertKnowledgeBase(knowledgeBase);
        return knowledgeBase;
    }

    /**
     * 在 HTTP 请求线程中完成必要的轻量准备：校验文件、保存原文并登记 pending 文档。
     *
     * <p>解析、Embedding 和发布索引不在这里执行，而是由后台摄取任务继续处理。</p>
     *
     * @param knowledgeBaseId 文件所属知识库编号
     * @param file            Multipart 请求中的上传文件
     * @return 已保存原文、状态为 pending 的文档
     */
    public KnowledgeDocument prepareUpload(String knowledgeBaseId, MultipartFile file) {
        requireKnowledgeBase(knowledgeBaseId);

        String documentId = UUID.randomUUID().toString();
        String originalFilename = normalizeFilename(file.getOriginalFilename());
        Path storedFile = storageRoot.resolve(knowledgeBaseId).resolve(documentId + ".txt");

        validateTextFile(file, originalFilename);
        try {
            saveOriginalFile(file, storedFile);
        } catch (IOException exception) {
            throw new UncheckedIOException("保存上传文件失败：" + readableMessage(exception), exception);
        }

        KnowledgeDocument pending = new KnowledgeDocument(
                documentId,
                knowledgeBaseId,
                originalFilename,
                storedFile.toString(),
                DocumentStatus.PENDING,
                0,
                null
        );
        try {
            knowledgeRepository.saveDocument(pending);
            return pending;
        } catch (RuntimeException exception) {
            deleteStoredFile(storedFile);
            throw exception;
        }
    }

    /**
     * 后台线程真正开始工作时，把文档状态从 pending 改为 running。
     *
     * @param documentId 要处理的文档编号
     * @return 更新后的 running 文档
     */
    public KnowledgeDocument markRunning(String documentId) {
        KnowledgeDocument document = getDocument(documentId);
        KnowledgeDocument running = changeStatus(document, DocumentStatus.RUNNING, 0, null);
        knowledgeRepository.saveDocument(running);
        return running;
    }

    /**
     * 探测原文件 MIME、选择解析器并按文档结构产生知识片段；这一阶段不调用模型也不写 Chunk 表。
     *
     * @param document 要读取的文档
     * @return 文件中解析出的知识条目
     * @throws IOException 文件读取失败
     */
    public List<KnowledgeEntry> parseDocument(KnowledgeDocument document) throws IOException {
        ParsedDocument parsedDocument = documentParsingService.parse(
                Path.of(document.storedPath()),
                document.originalFilename()
        );
        List<KnowledgeEntry> entries = documentChunker.chunk(parsedDocument);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("文档中没有可建立索引的内容");
        }
        return entries;
    }

    /**
     * 逐条调用百炼 Embedding，把解析结果变成等待发布的带向量 Chunk。
     *
     * @param document 所属文档及知识库信息
     * @param entries  parse 阶段得到的知识条目
     * @return 全部完成向量化的片段；此时尚未写入数据库
     * @throws IOException          百炼 HTTP 调用失败
     * @throws InterruptedException 等待百炼响应时线程被中断
     */
    public List<KnowledgeChunk> createChunks(
            KnowledgeDocument document,
            List<KnowledgeEntry> entries
    ) throws IOException, InterruptedException {
        List<KnowledgeChunk> indexedChunks = new ArrayList<>();
        for (int chunkIndex = 0; chunkIndex < entries.size(); chunkIndex++) {
            KnowledgeEntry entry = entries.get(chunkIndex);
            double[] vector = bailianClient.createEmbedding(entry.embeddingText());
            indexedChunks.add(new KnowledgeChunk(
                    UUID.randomUUID().toString(),
                    document.knowledgeBaseId(),
                    document.id(),
                    chunkIndex,
                    entry.title(),
                    entry.content(),
                    entry.keywords(),
                    entry.embeddingText(),
                    vector
            ));
        }
        return indexedChunks;
    }

    /**
     * 在第 9 课事务中发布全部 Chunk，并最后把文档改成 success。
     *
     * @param document 正在处理的文档
     * @param chunks   全部已经生成向量的片段
     * @return 已保存到数据库的 success 文档状态
     */
    public KnowledgeDocument publish(
            KnowledgeDocument document,
            List<KnowledgeChunk> chunks
    ) {
        KnowledgeDocument success = changeStatus(
                document,
                DocumentStatus.SUCCESS,
                chunks.size(),
                null
        );
        knowledgeRepository.publishSuccessfulDocument(success, chunks);
        return success;
    }

    /**
     * 后台任务失败时删除可能残留的 Chunk 并保存 failed 文档，但保留原文件供安全重试。
     *
     * @param documentId   失败文档编号
     * @param errorMessage 任务可以展示的错误原因
     */
    public void markFailed(String documentId, String errorMessage) {
        KnowledgeDocument document = getDocument(documentId);
        knowledgeRepository.deleteChunks(documentId);
        knowledgeRepository.saveDocument(
                changeStatus(document, DocumentStatus.FAILED, 0, errorMessage)
        );
    }

    /**
     * 查询某次上传当前保存的文档状态。
     *
     * @param documentId 文档编号
     * @return 当前状态快照
     * @throws NoSuchElementException 文档不存在时抛出，由 Web 层转换为 404
     */
    public KnowledgeDocument getDocument(String documentId) {
        return knowledgeRepository.findDocument(documentId)
                .orElseThrow(() -> new NoSuchElementException("文档不存在：" + documentId));
    }

    /**
     * 返回指定文档已经成功建立索引的片段，失败或尚未完成时返回空列表。
     *
     * @param documentId 文档编号
     * @return 不允许调用方修改的片段列表
     */
    public List<KnowledgeChunk> chunksOfDocument(String documentId) {
        getDocument(documentId);
        return knowledgeRepository.findChunksByDocument(documentId);
    }

    /**
     * 确认知识库存在，并把不存在的编号转换成明确的业务错误。
     *
     * @param knowledgeBaseId 待检查编号
     * @return 找到的知识库
     */
    private void requireKnowledgeBase(String knowledgeBaseId) {
        if (!knowledgeRepository.knowledgeBaseExists(knowledgeBaseId)) {
            throw new NoSuchElementException("知识库不存在：" + knowledgeBaseId);
        }
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
            throw new IllegalArgumentException("第 11 课当前支持 .txt 和 .md 文件");
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
     * 登记 pending 文档失败时删除刚保存的本地文件，避免产生没有数据库归属的孤立原文。
     *
     * @param storedFile 刚才尝试保存的文件
     */
    private void deleteStoredFile(Path storedFile) {
        try {
            Files.deleteIfExists(storedFile);
        } catch (IOException ignored) {
            // 优先保留数据库异常；生产环境还应记录清理失败日志并安排补偿任务。
        }
    }

    /**
     * 基于同一份文档数据创建新的状态快照，避免在共享对象上逐字段修改。
     *
     * @param original     初始文档及其归属信息
     * @param status       新状态
     * @param chunkCount   已发布片段数
     * @param errorMessage 失败原因
     * @return 可以整体写回数据库的新文档记录
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
    public String readableMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
