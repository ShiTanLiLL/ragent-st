package com.shitan.ai;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 标明摄取任务的原始内容来自浏览器上传文件，还是一个远程 HTTP 地址。
 */
public enum IngestionSourceType {
    UPLOAD("upload"),
    URL("url");

    private final String code;

    IngestionSourceType(String code) {
        this.code = code;
    }

    /**
     * 返回数据库和 JSON 中使用的稳定小写值。
     *
     * @return upload 或 url
     */
    @JsonValue
    public String code() {
        return code;
    }

    /**
     * 把数据库中的小写值还原成枚举，遇到未知值时立即报告配置错误。
     *
     * @param code 数据库保存的来源类型
     * @return 对应来源枚举
     */
    public static IngestionSourceType fromCode(String code) {
        for (IngestionSourceType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知摄取来源类型：" + code);
    }
}
