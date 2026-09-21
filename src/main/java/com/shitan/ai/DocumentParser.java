package com.shitan.ai;

/**
 * 两种真实解析实现之间刚刚出现的最小共同契约：声明 MIME 能力，并把字节转换为结构块。
 */
public interface DocumentParser {

    /**
     * 判断当前解析器是否认识探测出的 MIME。
     *
     * @param mimeType Tika 探测出的标准媒体类型
     * @return 当前实现可以解析时返回 true
     */
    boolean supports(String mimeType);

    /**
     * 解析文件字节，保留对分块有意义的文档结构。
     *
     * @param content    原文件完整字节
     * @param mimeType   已探测的 MIME
     * @param sourceName 浏览器提交的原文件名
     * @return 结构块按原文顺序排列的解析结果
     */
    ParsedDocument parse(byte[] content, String mimeType, String sourceName);
}
