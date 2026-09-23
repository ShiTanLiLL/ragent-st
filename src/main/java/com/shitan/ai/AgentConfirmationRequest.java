package com.shitan.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 用户确认请求只包含身份和同意/拒绝，不接受可被篡改的工具参数。
 */
public record AgentConfirmationRequest(
        @NotBlank(message = "userId 不能为空") String userId,
        @NotNull(message = "approved 不能为空") Boolean approved
) {
}
