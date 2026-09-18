package com.shitan.ai;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

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

    /**
     * 把数据库保存的小写状态码恢复成 Java 枚举。
     *
     * @param code 数据库中的 pending、running、success 或 failed
     * @return 对应状态枚举
     * @throws IllegalArgumentException 状态码不属于当前业务集合
     */
    public static DocumentStatus fromCode(String code) {
        return Arrays.stream(values())
                .filter(status -> status.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知文档状态：" + code));
    }
}
