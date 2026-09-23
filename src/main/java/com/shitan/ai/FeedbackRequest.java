package com.shitan.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 提交回答反馈的 HTTP 请求；rating 只接受 1（赞）或 -1（踩）。
 */
public record FeedbackRequest(
        @NotBlank(message = "userId 不能为空") String userId,
        @NotNull(message = "rating 不能为空") Integer rating,
        String reason
) {
}
