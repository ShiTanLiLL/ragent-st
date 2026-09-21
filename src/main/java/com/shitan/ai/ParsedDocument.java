package com.shitan.ai;

import java.util.List;

/**
 * 一份文件的结构化解析结果，记录探测到的 MIME、显示文件名和按原顺序排列的结构块。
 */
public record ParsedDocument(
        String mimeType,
        String sourceName,
        List<DocumentBlock> blocks
) {
}
