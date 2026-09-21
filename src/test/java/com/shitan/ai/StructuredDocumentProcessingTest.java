package com.shitan.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 11 课结构化文档测试：观察 MIME、Block、章节路径、表格和两种 Chunk 文本的完整变化。
 */
class StructuredDocumentProcessingTest {

    @TempDir
    Path temporaryDirectory;

    /**
     * 用一份真实 Markdown 跑满“探测 MIME → 解析 Block → 按结构分块”的主流程。
     *
     * @throws IOException 测试文件写入或读取失败
     */
    @Test
    void shouldPreserveMarkdownHeadingsAndTableMeaningWhenChunking() throws IOException {
        Path document = temporaryDirectory.resolve("employee-handbook.md");
        Files.writeString(document, """
                # 员工手册

                ## 年假规则

                员工连续工作满一年后，每年享有 5 天带薪年假。

                ## 差旅住宿标准

                | 城市级别 | 每晚住宿上限 |
                | --- | --- |
                | 一线城市 | 600 元 |
                | 其他城市 | 400 元 |
                """, StandardCharsets.UTF_8);

        // 解析层只关心文件类型和结构，不决定向量，也不访问数据库。
        ParsedDocument parsed = new DocumentParsingService().parse(
                document,
                "employee-handbook.md"
        );

        assertTrue(parsed.mimeType().contains("markdown"));
        assertEquals(5, parsed.blocks().size());
        assertEquals(new DocumentBlock.Heading(1, "员工手册"), parsed.blocks().get(0));
        assertEquals(new DocumentBlock.Heading(2, "年假规则"), parsed.blocks().get(1));
        assertInstanceOf(DocumentBlock.Paragraph.class, parsed.blocks().get(2));
        assertInstanceOf(DocumentBlock.Table.class, parsed.blocks().get(4));

        // 分块层沿标题路径工作。这里使用较小预算，但不会从表格行中间截断数据。
        List<KnowledgeEntry> chunks = new StructuredDocumentChunker(140).chunk(parsed);

        assertEquals(2, chunks.size());
        assertEquals("员工手册 / 年假规则", chunks.get(0).title());
        assertEquals(
                "员工手册 > 年假规则\n员工连续工作满一年后，每年享有 5 天带薪年假。",
                chunks.get(0).embeddingText()
        );

        KnowledgeEntry tableChunk = chunks.get(1);
        assertEquals("员工手册 / 差旅住宿标准", tableChunk.title());
        assertTrue(tableChunk.content().contains("| 城市级别 | 每晚住宿上限 |"));
        assertTrue(tableChunk.embeddingText().contains("城市级别：一线城市；每晚住宿上限：600 元"));
        assertTrue(tableChunk.embeddingText().contains("城市级别：其他城市；每晚住宿上限：400 元"));

        System.out.println("探测到的 MIME：" + parsed.mimeType());
        System.out.println("解析出的 Blocks：" + parsed.blocks());
        System.out.println("年假片段：" + chunks.get(0));
        System.out.println("表格展示文本：\n" + tableChunk.content());
        System.out.println("表格向量文本：\n" + tableChunk.embeddingText());
    }

    /**
     * 证明扩展名只是探测线索：把 PDF 字节伪装成 .md，仍应在解析阶段拒绝而不是当文本切块。
     *
     * @throws IOException 测试文件写入或读取失败
     */
    @Test
    void shouldRejectPdfBytesDisguisedWithMarkdownExtension() throws IOException {
        Path disguisedPdf = temporaryDirectory.resolve("disguised.md");
        Files.write(disguisedPdf, "%PDF-1.7\n% fake lesson fixture".getBytes(StandardCharsets.US_ASCII));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new DocumentParsingService().parse(disguisedPdf, "disguised.md")
        );

        assertTrue(error.getMessage().contains("application/pdf"));
    }
}
