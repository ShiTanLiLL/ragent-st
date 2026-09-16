package com.shitan.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeFileLoaderTest {

    // JUnit 为每个测试准备独立临时目录，测试结束后自动清理，不污染真实项目文件。
    @TempDir
    Path tempDirectory;

    @Test
    void shouldLoadUtf8MarkdownBlocksAndMakeThemSearchable() throws IOException {
        // Arrange：模拟运营人员提供的 UTF-8 Markdown；空行把两条政策分开。
        Path knowledgeFile = tempDirectory.resolve("policies.md");
        Files.writeString(knowledgeFile, """
                # 退货政策
                关键词：退货、退款
                签收后 7 天内可申请无理由退货。

                # 保修政策
                关键词：保修、质保
                电子产品自购买之日起享受 1 年保修。
                """, StandardCharsets.UTF_8);

        // Act：先把文件转换成内存知识，再交给上一课的助手回答问题。
        List<KnowledgeEntry> knowledgeEntries = new KnowledgeFileLoader().load(knowledgeFile);
        KnowledgeAnswer answer = new KnowledgeAssistant(knowledgeEntries)
                .answer("电子产品质保多久？");

        // Assert：文件确实被分成两条知识，中文正文与关键词没有因编码而损坏。
        assertEquals(2, knowledgeEntries.size());
        assertEquals("退货政策", knowledgeEntries.get(0).title());
        assertEquals(List.of("保修", "质保"), knowledgeEntries.get(1).keywords());

        // Assert：从文件产生的知识已经进入现有检索链，并能返回正文与来源。
        assertEquals("电子产品自购买之日起享受 1 年保修。", answer.content());
        assertEquals("保修政策", answer.sourceTitle());
    }

    @Test
    void shouldTreatBlankFileAsNoKnowledge() throws IOException {
        // Arrange：文件存在，但只包含空格和换行，没有任何实际知识。
        Path knowledgeFile = tempDirectory.resolve("empty.txt");
        Files.writeString(knowledgeFile, "  \n\n", StandardCharsets.UTF_8);

        // Act：加载结果为空列表，现有助手继续负责未命中的业务兜底。
        List<KnowledgeEntry> knowledgeEntries = new KnowledgeFileLoader().load(knowledgeFile);
        KnowledgeAnswer answer = new KnowledgeAssistant(knowledgeEntries)
                .answer("退货期限是多久？");

        // Assert：空文件不制造空知识；没有证据时仍然诚实返回未找到。
        assertTrue(knowledgeEntries.isEmpty());
        assertEquals("暂时没有找到相关知识。", answer.content());
        assertNull(answer.sourceTitle());
    }
}
