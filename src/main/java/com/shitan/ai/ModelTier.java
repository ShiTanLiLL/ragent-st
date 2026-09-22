package com.shitan.ai;

/**
 * 问答对模型速度和能力的抽象要求；它不是某个供应商的模型名。
 */
public enum ModelTier {
    FAST,
    STANDARD,
    DEEP;

    /**
     * 把请求中的可选文字转换成档位；空值和未知值回到稳定的 STANDARD。
     */
    public static ModelTier fromText(String text) {
        if (text == null || text.isBlank()) {
            return STANDARD;
        }
        try {
            return valueOf(text.strip().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return STANDARD;
        }
    }
}
