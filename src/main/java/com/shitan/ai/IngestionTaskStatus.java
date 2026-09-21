package com.shitan.ai;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 后台摄取任务从登记到结束会经历的状态；任务完成使用 completed，与文档的 success 分开命名。
 */
public enum IngestionTaskStatus {

    PENDING("pending"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed");

    private final String code;

    /**
     * 保存数据库和 HTTP JSON 共用的小写状态码。
     *
     * @param code 对外可见的状态文字
     */
    IngestionTaskStatus(String code) {
        this.code = code;
    }

    /**
     * 把枚举序列化成 API 和数据库使用的小写状态码。
     *
     * @return pending、running、completed 或 failed
     */
    @JsonValue
    public String code() {
        return code;
    }

    /**
     * 把数据库状态文字恢复成 Java 枚举，遇到未知数据时尽早报告结构问题。
     *
     * @param code 数据库保存的状态码
     * @return 对应任务状态
     */
    public static IngestionTaskStatus fromCode(String code) {
        return Arrays.stream(values())
                .filter(status -> status.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知摄取任务状态：" + code));
    }
}
