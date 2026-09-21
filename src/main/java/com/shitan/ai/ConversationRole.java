package com.shitan.ai;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 会话消息的说话方；本课只保存用户问题和助手最终回答。
 */
public enum ConversationRole {
    USER("user"),
    ASSISTANT("assistant");

    private final String code;

    ConversationRole(String code) {
        this.code = code;
    }

    /**
     * 返回数据库、HTTP JSON 和 OpenAI messages 共同使用的小写角色名。
     *
     * @return user 或 assistant
     */
    @JsonValue
    public String code() {
        return code;
    }

    /**
     * 把数据库角色文字恢复成枚举，未知值立即报告数据问题。
     *
     * @param code 数据库中的角色名
     * @return 对应角色
     */
    public static ConversationRole fromCode(String code) {
        for (ConversationRole role : values()) {
            if (role.code.equals(code)) {
                return role;
            }
        }
        throw new IllegalArgumentException("未知会话角色：" + code);
    }
}
