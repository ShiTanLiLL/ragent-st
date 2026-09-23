package com.shitan.ai;

import jakarta.validation.constraints.NotBlank;

/**
 * 开放任务入口；sessionId 为空时新建 Agent 会话，有值时恢复已有步骤继续处理。
 */
public record AgentRequest(
        @NotBlank(message = "userId 不能为空") String userId,
        @NotBlank(message = "任务目标不能为空") String objective,
        String knowledgeBaseId,
        String sessionId
) {
}
