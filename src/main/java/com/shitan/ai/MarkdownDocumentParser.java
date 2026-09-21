package com.shitan.ai;

import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 使用 CommonMark 语法树解析 Markdown，当前只提取本课需要的标题、段落和 GFM 表格。
 */
public class MarkdownDocumentParser implements DocumentParser {

    private static final Set<String> MARKDOWN_MIME_TYPES = Set.of(
            "text/x-web-markdown",
            "text/markdown",
            "text/x-markdown"
    );

    private final Parser parser = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    /**
     * 判断 MIME 是否属于常见 Markdown 类型。
     *
     * @param mimeType Tika 或外部协议提供的媒体类型
     * @return Markdown 解析器可以接管时返回 true
     */
    @Override
    public boolean supports(String mimeType) {
        return MARKDOWN_MIME_TYPES.contains(mimeType);
    }

    /**
     * 先由 CommonMark 生成 AST，再按原文顺序提取标题、顶层段落和表格块。
     *
     * @param content    UTF-8 Markdown 字节
     * @param mimeType   已探测的 Markdown MIME
     * @param sourceName 原文件名
     * @return 不再依赖 Markdown 标记字符串的结构化文档
     */
    @Override
    public ParsedDocument parse(byte[] content, String mimeType, String sourceName) {
        Node document = parser.parse(new String(content, StandardCharsets.UTF_8));
        BlockVisitor visitor = new BlockVisitor();
        document.accept(visitor);
        return new ParsedDocument(mimeType, sourceName, List.copyOf(visitor.blocks));
    }

    /**
     * 顺着 CommonMark AST 收集本课关心的块；进入一个块后不再重复收集其内部行内节点。
     */
    private static final class BlockVisitor extends AbstractVisitor {

        private final List<DocumentBlock> blocks = new ArrayList<>();

        /**
         * 把 Markdown 标题的级别和纯文字保存为 Heading Block。
         *
         * @param heading CommonMark 标题节点
         */
        @Override
        public void visit(Heading heading) {
            blocks.add(new DocumentBlock.Heading(heading.getLevel(), inlineText(heading)));
        }

        /**
         * 把顶层普通段落保存为 Paragraph Block；表格单元格中的段落由表格整体处理。
         *
         * @param paragraph CommonMark 段落节点
         */
        @Override
        public void visit(Paragraph paragraph) {
            if (hasTableAncestor(paragraph)) {
                return;
            }
            String text = inlineText(paragraph).strip();
            if (!text.isEmpty()) {
                blocks.add(new DocumentBlock.Paragraph(text));
            }
        }

        /**
         * 识别 GFM 扩展生成的 TableBlock，并一次性提取表头和数据行。
         *
         * @param customBlock CommonMark 扩展块
         */
        @Override
        public void visit(org.commonmark.node.CustomBlock customBlock) {
            if (customBlock instanceof org.commonmark.ext.gfm.tables.TableBlock tableBlock) {
                blocks.add(readTable(tableBlock));
                return;
            }
            super.visit(customBlock);
        }
    }

    /**
     * 把 GFM 表格节点转换成列名和二维行数据，保持每个单元格的位置。
     *
     * @param tableBlock CommonMark 表格根节点
     * @return 结构化表格块
     */
    private static DocumentBlock.Table readTable(
            org.commonmark.ext.gfm.tables.TableBlock tableBlock
    ) {
        List<String> headers = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();

        for (Node section = tableBlock.getFirstChild(); section != null;
             section = section.getNext()) {
            if (section instanceof TableHead tableHead) {
                Node headerRow = tableHead.getFirstChild();
                if (headerRow instanceof TableRow row) {
                    headers.addAll(readCells(row));
                }
            } else if (section instanceof TableBody tableBody) {
                for (Node rowNode = tableBody.getFirstChild(); rowNode != null;
                     rowNode = rowNode.getNext()) {
                    if (rowNode instanceof TableRow row) {
                        rows.add(readCells(row));
                    }
                }
            }
        }
        return new DocumentBlock.Table(List.copyOf(headers), List.copyOf(rows));
    }

    /**
     * 读取表格一行中的全部单元格文字。
     *
     * @param row GFM 表格行
     * @return 按列顺序排列的单元格文本
     */
    private static List<String> readCells(TableRow row) {
        List<String> cells = new ArrayList<>();
        for (Node node = row.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof TableCell cell) {
                cells.add(inlineText(cell).strip());
            }
        }
        return List.copyOf(cells);
    }

    /**
     * 判断段落是否位于表格内部，防止同一单元格既进入 Table 又成为独立 Paragraph。
     *
     * @param node 当前段落节点
     * @return 任一祖先是 GFM TableBlock 时返回 true
     */
    private static boolean hasTableAncestor(Node node) {
        for (Node parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof org.commonmark.ext.gfm.tables.TableBlock) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把一个块中的行内 AST 还原为纯文字；链接和强调保留文字，代码保留内容，换行保留空格。
     *
     * @param parent 标题、段落或单元格节点
     * @return 适合检索和展示的行内文字
     */
    private static String inlineText(Node parent) {
        StringBuilder text = new StringBuilder();
        appendInlineChildren(text, parent);
        return text.toString().replaceAll("\\s+", " ").strip();
    }

    /**
     * 递归遍历行内节点，只提取可阅读内容，不把 Markdown 符号带到向量文本。
     *
     * @param target 累积文字的缓冲区
     * @param parent 当前父节点
     */
    private static void appendInlineChildren(StringBuilder target, Node parent) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text node) {
                target.append(node.getLiteral());
            } else if (child instanceof Code node) {
                target.append(node.getLiteral());
            } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                target.append(' ');
            } else {
                appendInlineChildren(target, child);
            }
        }
    }
}
