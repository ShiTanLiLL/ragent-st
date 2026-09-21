package com.shitan.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 按结构块分块：标题维护章节路径，段落按字符预算切分，表格按完整数据行打包。
 */
public class StructuredDocumentChunker {

    private static final int DEFAULT_MAX_CHARS = 500;

    private final int maxChars;

    /**
     * 使用课堂默认字符预算创建分块器。
     */
    public StructuredDocumentChunker() {
        this(DEFAULT_MAX_CHARS);
    }

    /**
     * 使用指定字符预算创建分块器，较小预算便于测试观察切分边界。
     *
     * @param maxChars 单个向量文本的目标最大字符数，至少为 40
     */
    public StructuredDocumentChunker(int maxChars) {
        if (maxChars < 40) {
            throw new IllegalArgumentException("分块字符预算不能小于 40");
        }
        this.maxChars = maxChars;
    }

    /**
     * 按原顺序遍历结构块，把标题路径带入后续段落和表格，生成稳定的待向量化知识。
     *
     * @param document MIME 解析后的结构化文档
     * @return 同时具有展示正文和向量文本的知识片段
     */
    public List<KnowledgeEntry> chunk(ParsedDocument document) {
        List<KnowledgeEntry> chunks = new ArrayList<>();
        List<String> outline = new ArrayList<>();

        for (DocumentBlock block : document.blocks()) {
            if (block instanceof DocumentBlock.Heading heading) {
                updateOutline(outline, heading);
            } else if (block instanceof DocumentBlock.Paragraph paragraph) {
                addParagraphChunks(chunks, document.sourceName(), outline, paragraph.text(), List.of());
            } else if (block instanceof DocumentBlock.Table table) {
                addTableChunks(chunks, document.sourceName(), outline, table);
            } else if (block instanceof DocumentBlock.LegacyKnowledge knowledge) {
                addParagraphChunks(
                        chunks,
                        document.sourceName(),
                        List.of(knowledge.title()),
                        knowledge.text(),
                        knowledge.keywords()
                );
            }
        }
        return List.copyOf(chunks);
    }

    /**
     * 按标题级别更新当前章节路径；遇到同级标题时替换，回到上级时移除更深标题。
     *
     * @param outline 当前遍历位置的标题路径
     * @param heading 新遇到的标题块
     */
    private void updateOutline(List<String> outline, DocumentBlock.Heading heading) {
        int targetIndex = heading.level() - 1;
        while (outline.size() > targetIndex) {
            outline.remove(outline.size() - 1);
        }
        while (outline.size() < targetIndex) {
            outline.add("");
        }
        outline.add(heading.text());
    }

    /**
     * 将普通段落按剩余字符预算切成一个或多个片段，并为每片补上当前章节路径。
     *
     * @param chunks    当前输出列表
     * @param sourceName 无标题时使用的文件名
     * @param outline   当前章节路径
     * @param text      段落正文
     * @param keywords  旧格式显式关键词，新 Markdown 为空
     */
    private void addParagraphChunks(
            List<KnowledgeEntry> chunks,
            String sourceName,
            List<String> outline,
            String text,
            List<String> keywords
    ) {
        String title = displayTitle(sourceName, outline);
        String embeddingPrefix = embeddingTitle(sourceName, outline) + "\n";
        int bodyBudget = Math.max(1, maxChars - embeddingPrefix.length());

        for (int start = 0; start < text.length(); start += bodyBudget) {
            String fragment = text.substring(start, Math.min(text.length(), start + bodyBudget));
            chunks.add(new KnowledgeEntry(
                    title,
                    fragment,
                    keywords,
                    embeddingPrefix + fragment
            ));
        }
    }

    /**
     * 以“整行”为不可拆单位向预算中装表格，分块后每块都重复表头，避免列含义丢失。
     *
     * <p>若单行本身已经超过预算，本课仍保留完整行，不在单元格中间截断。</p>
     *
     * @param chunks     当前输出列表
     * @param sourceName 无标题时使用的文件名
     * @param outline    当前章节路径
     * @param table      表头和数据行
     */
    private void addTableChunks(
            List<KnowledgeEntry> chunks,
            String sourceName,
            List<String> outline,
            DocumentBlock.Table table
    ) {
        List<List<String>> currentRows = new ArrayList<>();
        for (List<String> row : table.rows()) {
            List<List<String>> candidate = new ArrayList<>(currentRows);
            candidate.add(row);
            String candidateEmbedding = renderTableEmbedding(sourceName, outline, table.headers(), candidate);

            if (!currentRows.isEmpty() && candidateEmbedding.length() > maxChars) {
                addTableChunk(chunks, sourceName, outline, table.headers(), currentRows);
                currentRows = new ArrayList<>();
            }
            currentRows.add(row);
        }

        if (!currentRows.isEmpty()) {
            addTableChunk(chunks, sourceName, outline, table.headers(), currentRows);
        }
    }

    /**
     * 把一组完整表格行同时渲染为 Markdown 展示文本和更明确的“列名：值”向量文本。
     *
     * @param chunks     当前输出列表
     * @param sourceName 无标题时使用的文件名
     * @param outline    当前章节路径
     * @param headers    表头
     * @param rows       当前分块包含的完整数据行
     */
    private void addTableChunk(
            List<KnowledgeEntry> chunks,
            String sourceName,
            List<String> outline,
            List<String> headers,
            List<List<String>> rows
    ) {
        chunks.add(new KnowledgeEntry(
                displayTitle(sourceName, outline),
                renderMarkdownTable(headers, rows),
                List.of(),
                renderTableEmbedding(sourceName, outline, headers, rows)
        ));
    }

    /**
     * 用 Markdown 管道表格保存适合运营人员查看的正文。
     *
     * @param headers 表头
     * @param rows    数据行
     * @return 可直接阅读的 Markdown 表格
     */
    private String renderMarkdownTable(List<String> headers, List<List<String>> rows) {
        StringBuilder display = new StringBuilder();
        display.append("| ").append(String.join(" | ", headers)).append(" |\n");
        display.append("| ");
        for (int index = 0; index < headers.size(); index++) {
            if (index > 0) {
                display.append(" | ");
            }
            display.append("---");
        }
        display.append(" |\n");
        for (List<String> row : rows) {
            display.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        return display.toString().stripTrailing();
    }

    /**
     * 将表格每一行展开为带列名的语句，防止向量模型只看到值而不知道每列含义。
     *
     * @param sourceName 无标题时使用的文件名
     * @param outline    当前章节路径
     * @param headers    表头
     * @param rows       数据行
     * @return 章节路径加“列名：值”的向量输入文本
     */
    private String renderTableEmbedding(
            String sourceName,
            List<String> outline,
            List<String> headers,
            List<List<String>> rows
    ) {
        StringBuilder embedding = new StringBuilder(embeddingTitle(sourceName, outline));
        for (List<String> row : rows) {
            embedding.append('\n');
            for (int column = 0; column < row.size(); column++) {
                if (column > 0) {
                    embedding.append("；");
                }
                String header = column < headers.size() ? headers.get(column) : "第" + (column + 1) + "列";
                embedding.append(header).append("：").append(row.get(column));
            }
        }
        return embedding.toString();
    }

    /**
     * 生成给用户展示的片段标题，章节之间使用斜杠；没有标题时退回文件名。
     *
     * @param sourceName 原文件名
     * @param outline    当前章节路径
     * @return 片段展示标题
     */
    private String displayTitle(String sourceName, List<String> outline) {
        List<String> names = outline.stream().filter(name -> !name.isBlank()).toList();
        return names.isEmpty() ? withoutExtension(sourceName) : String.join(" / ", names);
    }

    /**
     * 生成给 Embedding 使用的章节路径，使用大于号表达父子层级。
     *
     * @param sourceName 原文件名
     * @param outline    当前章节路径
     * @return 适合进入向量文本的章节路径
     */
    private String embeddingTitle(String sourceName, List<String> outline) {
        List<String> names = outline.stream().filter(name -> !name.isBlank()).toList();
        return names.isEmpty() ? withoutExtension(sourceName) : String.join(" > ", names);
    }

    /**
     * 无章节标题时去掉文件扩展名，提供比完整路径更干净的兜底标题。
     *
     * @param sourceName 原文件名
     * @return 去掉最后一个扩展名的文件名
     */
    private String withoutExtension(String sourceName) {
        int dot = sourceName.lastIndexOf('.');
        return dot > 0 ? sourceName.substring(0, dot) : sourceName;
    }
}
