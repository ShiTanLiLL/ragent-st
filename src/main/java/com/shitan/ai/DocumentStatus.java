package com.shitan.ai;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 文档从登记到完成索引会依次经历的处理状态。
 */
public enum DocumentStatus {

    PENDING("pending"),
    RUNNING("running"),
    SUCCESS("success"),
    FAILED("failed");

    private final String code;

    /**
     * 保存对外状态码；枚举常量在类加载时各自调用一次。
     *
     * @param code HTTP JSON 中使用的小写状态文字
     */
    DocumentStatus(String code) {
        this.code = code;
    }

    /**
     * 把枚举转换成对外 JSON 使用的小写状态值。
     *
     * @return pending、running、success 或 failed
     */
    @JsonValue
    public String code() {
        return code;
    }
}
