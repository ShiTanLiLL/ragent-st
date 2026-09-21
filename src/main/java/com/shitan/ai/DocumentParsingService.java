package com.shitan.ai;

import org.apache.tika.Tika;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文档解析入口：读取一次文件、探测 MIME，并把字节交给唯一支持该 MIME 的解析器。
 */
public class DocumentParsingService {

    private final Tika tika = new Tika();
    private final List<DocumentParser> parsers = List.of(
            new LegacyKnowledgeTextParser(),
            new MarkdownDocumentParser()
    );

    /**
     * 根据文件真实字节和原文件名探测 MIME，再选择对应解析器生成结构块。
     *
     * <p>文件名只是 Tika 的辅助线索，最终路由依据是 Tika 返回的 MIME，
     * 而不是业务代码自己截取扩展名。</p>
     *
     * @param storedPath 已保存到服务器的原文件路径
     * @param sourceName 浏览器提交的原始文件名
     * @return 包含 MIME、来源名称和结构块的解析结果
     * @throws IOException 文件读取或 MIME 探测失败
     */
    public ParsedDocument parse(Path storedPath, String sourceName) throws IOException {
        byte[] content = Files.readAllBytes(storedPath);
        if (content.length == 0) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        String mimeType = tika.detect(content, sourceName);
        DocumentParser parser = parsers.stream()
                .filter(candidate -> candidate.supports(mimeType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "暂不支持此文件类型：" + mimeType
                ));
        return parser.parse(content, mimeType, sourceName);
    }
}
